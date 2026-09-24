package com.cloudforgeci.localstack;

import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalStackServiceCapability;
import com.cloudforge.core.local.LocalDeployResult;
import com.cloudforge.core.local.LocalDeployer;
import com.cloudforge.core.local.LocalDeploymentCatalog;
import com.cloudforge.core.local.LocalResourceChange;
import com.cloudforge.core.local.LocalSameApplicationStackReplacer;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetStatus;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.CreateChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.ExecuteChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.GetTemplateRequest;
import software.amazon.awssdk.services.cloudformation.model.ListStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.ResourceChange;
import software.amazon.awssdk.services.cloudformation.model.StackSummary;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.cloudformation.model.Tag;

import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Creates and incrementally updates CloudFormation stacks against LocalStack.
 * All calls use the configured local endpoint; no host AWS CLI or AWS account is used.
 */
public final class LocalStackDeployer implements LocalDeployer {
    /** Disable same-app stack replacement when set to {@code false} or {@code 0}. */
    public static final String REPLACE_SAME_APP_ENV = "CFC_LOCALSTACK_REPLACE_SAME_APP";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration OPERATION_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    /** CloudFormation inline template body limit (LocalStack matches AWS). */
    private static final int MAX_INLINE_TEMPLATE_BYTES = 51_200;
    private static final String TEMPLATE_BUCKET = "cfc-localstack-cfn-templates";

    private final CloudFormationClient cloudFormation;
    private final S3Client s3;
    private final String endpoint;
    private final String region;

    public LocalStackDeployer() {
        this(
            resolveEndpoint(),
            System.getenv().getOrDefault("AWS_DEFAULT_REGION", "us-east-1")
        );
    }

    /**
     * LocalStack gateway URL from {@code LOCALSTACK_ENDPOINT}, then {@code AWS_ENDPOINT_URL},
     * defaulting to {@code http://localhost:4566}.
     */
    public static String resolveEndpoint() {
        String localstackEndpoint = System.getenv("LOCALSTACK_ENDPOINT");
        if (localstackEndpoint != null && !localstackEndpoint.isBlank()) {
            return localstackEndpoint.trim();
        }
        String awsEndpoint = System.getenv("AWS_ENDPOINT_URL");
        if (awsEndpoint != null && !awsEndpoint.isBlank()) {
            return awsEndpoint.trim();
        }
        return "http://localhost:4566";
    }

    /** Visible for tests and custom endpoint wiring. */
    public LocalStackDeployer(String endpoint, String region) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.region = region;
        URI endpointUri = URI.create(this.endpoint);
        cloudFormation = CloudFormationClient.builder()
            .endpointOverride(endpointUri)
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();
        s3 = S3Client.builder()
            .endpointOverride(endpointUri)
            .region(Region.of(region))
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();
    }

    @Override
    public LocalDeployResult deploy(String stackName, Path template) throws IOException {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault(
                "CFC_LOCALSTACK_SKIP_PREFLIGHT", "false"))) {
            LocalStackDeployPreflight.require(
                LocalStackCapabilityProbe.probe(endpoint),
                LocalStackServiceCapability.ECS,
                LocalStackServiceCapability.ELBV2);
        }
        String contextStackName = contextStackNameFromLocalStack(stackName);
        // LocalStack's fixed default-mode test account, regardless of which literal access key
        // string was used to authenticate — same account every manifest's ${AWS::AccountId}
        // token (when account-agnostic synthesis leaves one unresolved) needs to resolve to here.
        LocalStackCdkAssetPublisher.publish(template.getParent(), contextStackName, s3, "000000000000", true);

        if (stackExists(stackName) && !stackIsDeployable(stackName)) {
            delete(stackName);
        }
        boolean exists = stackExists(stackName) && stackIsDeployable(stackName);
        String templateBody = Files.readString(template);
        if (LocalStackPostgresDatasourceReconciler.requiresDatasourceParameters(templateBody)) {
            LocalStackPostgresCompanion.ensureRunning();
        }
        if (exists && templateMatches(stackName, templateBody)) {
            reconcilePostDeployState(stackName, templateBody);
            return new LocalDeployResult(stackName, false, true, List.of(), outputs(stackName));
        }
        String changeSetName = "cfc-" + UUID.randomUUID().toString().substring(0, 8);

        CreateChangeSetRequest.Builder changeSetBuilder = CreateChangeSetRequest.builder()
            .stackName(stackName)
            .changeSetName(changeSetName)
            .changeSetType(exists ? ChangeSetType.UPDATE : ChangeSetType.CREATE)
            .capabilities(Capability.CAPABILITY_IAM, Capability.CAPABILITY_NAMED_IAM);

        if (templateBody.getBytes(StandardCharsets.UTF_8).length > MAX_INLINE_TEMPLATE_BYTES) {
            changeSetBuilder.templateURL(uploadTemplateToS3(stackName, templateBody));
        } else {
            changeSetBuilder.templateBody(templateBody);
        }

        List<LocalResourceChange> changes;
        try {
            cloudFormation.createChangeSet(changeSetBuilder.build());

            var changeSet = waitForChangeSet(stackName, changeSetName);
            if (changeSet.status() == ChangeSetStatus.FAILED) {
                String reason = changeSet.statusReason() == null ? "" : changeSet.statusReason();
                deleteChangeSet(stackName, changeSetName);
                if (isNoOp(reason)) {
                    reconcilePostDeployState(stackName, templateBody);
                    return new LocalDeployResult(stackName, false, true, List.of(), outputs(stackName));
                }
                throw new IOException("LocalStack change set failed: " + reason);
            }

            changes = changeSet.changes().stream()
                .map(change -> toChange(change.resourceChange()))
                .toList();
            cloudFormation.executeChangeSet(ExecuteChangeSetRequest.builder()
                .stackName(stackName)
                .changeSetName(changeSetName)
                .build());
        } catch (CloudFormationException e) {
            if (!isLocalStackInternalError(e)) {
                throw e;
            }
            // LocalStack's own change-set emulation (CreateChangeSet/DescribeChangeSet) can break
            // outright on larger/more deeply-nested templates -- a Python "maximum recursion depth
            // exceeded" 500 from LocalStack itself, not a real template or business-logic problem
            // (CreateChangeSet already succeeded; it's LocalStack's own dependency-graph walk over
            // the result that recurses without a base case). Change sets are a preview/review
            // feature with no purpose against a throwaway local emulator anyway, so fall back to
            // creating/updating the stack directly, which exercises a much simpler LocalStack code
            // path. The only real cost is the itemized per-resource change list this run won't
            // have, which no caller currently treats as more than informational.
            System.out.println("   ⚠️  LocalStack change-set emulation failed internally ("
                + e.getMessage() + ") -- falling back to a direct stack create/update");
            deleteChangeSetQuietly(stackName, changeSetName);
            changes = List.of();
            deployStackDirectly(stackName, templateBody, exists);
        }

        awaitStackSettled(stackName, exists);
        reconcilePostDeployState(stackName, templateBody);
        return new LocalDeployResult(stackName, !exists, false, changes, outputs(stackName));
    }

    /** Whether {@code e} is LocalStack's own CloudFormation emulation breaking internally (HTTP
     *  500 / {@code InternalError}), as opposed to a real, meaningful failure (validation error,
     *  resource-creation failure, etc.) that should propagate and fail the deploy normally.
     *  Package-private, not private: unit-tested directly since exercising the real fallback
     *  requires a live (or faked) CloudFormation endpoint that this module's other tests avoid. */
    static boolean isLocalStackInternalError(CloudFormationException e) {
        return e.statusCode() == 500
            || "InternalError".equals(e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode());
    }

    private void deleteChangeSetQuietly(String stackName, String changeSetName) {
        try {
            deleteChangeSet(stackName, changeSetName);
        } catch (CloudFormationException ignored) {
            // Best-effort cleanup of a change set LocalStack itself couldn't fully process --
            // nothing meaningful to do if even the delete fails the same way.
        }
    }

    /** {@link #deploy}'s change-set-based path, minus the change set: creates or updates the
     *  stack directly via {@code CreateStack}/{@code UpdateStack}, the fallback {@link #deploy}
     *  reaches when LocalStack's own change-set emulation breaks (see {@link
     *  #isLocalStackInternalError}). */
    private void deployStackDirectly(String stackName, String templateBody, boolean exists) throws IOException {
        if (exists) {
            UpdateStackRequest.Builder updateBuilder = UpdateStackRequest.builder()
                .stackName(stackName)
                .capabilities(Capability.CAPABILITY_IAM, Capability.CAPABILITY_NAMED_IAM);
            if (templateBody.getBytes(StandardCharsets.UTF_8).length > MAX_INLINE_TEMPLATE_BYTES) {
                updateBuilder.templateURL(uploadTemplateToS3(stackName, templateBody));
            } else {
                updateBuilder.templateBody(templateBody);
            }
            cloudFormation.updateStack(updateBuilder.build());
        } else {
            CreateStackRequest.Builder createBuilder = CreateStackRequest.builder()
                .stackName(stackName)
                .capabilities(Capability.CAPABILITY_IAM, Capability.CAPABILITY_NAMED_IAM);
            if (templateBody.getBytes(StandardCharsets.UTF_8).length > MAX_INLINE_TEMPLATE_BYTES) {
                createBuilder.templateURL(uploadTemplateToS3(stackName, templateBody));
            } else {
                createBuilder.templateBody(templateBody);
            }
            cloudFormation.createStack(createBuilder.build());
        }
    }

    private void awaitStackSettled(String stackName, boolean exists) throws IOException {
        var request = DescribeStacksRequest.builder().stackName(stackName).build();
        try {
            if (exists) {
                cloudFormation.waiter().waitUntilStackUpdateComplete(request);
            } else {
                cloudFormation.waiter().waitUntilStackCreateComplete(request);
            }
        } catch (Exception e) {
            throw new IOException(
                "LocalStack deployment failed for " + stackName + ":\n" + recentEvents(stackName),
                e
            );
        }
    }

    private void reconcilePostDeployState(String stackName, String adaptedTemplateBody) {
        boolean cognitoSynced = LocalStackCognitoSecretReconciler.reconcileAfterDeploy(
            cloudFormation, stackName, endpoint, region, adaptedTemplateBody);
        boolean datasourceSynced = LocalStackPostgresDatasourceReconciler.reconcileAfterDeploy(
            cloudFormation, stackName, endpoint, region, adaptedTemplateBody);
        if (cognitoSynced || datasourceSynced) {
            restartEcsServices(stackName);
        }
        // Both register their own corrected task definition revision and redeploy services onto
        // it directly — unlike the two reconcilers above, forceNewDeployment on the OLD revision
        // (what restartEcsServices does) wouldn't pick up a baked-in env var correction at all.
        LocalStackMysqlPortReconciler.reconcileAfterDeploy(cloudFormation, stackName, endpoint, region);
        LocalStackOidcClientSecretReconciler.reconcileAfterDeploy(cloudFormation, stackName, endpoint, region);
    }

    private void restartEcsServices(String stackName) {
        List<StackResource> resources = cloudFormation.describeStackResources(
                DescribeStackResourcesRequest.builder().stackName(stackName).build())
            .stackResources();
        try (EcsClient ecs = EcsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")))
                .build()) {
            for (StackResource resource : resources) {
                if (!"AWS::ECS::Service".equals(resource.resourceType())) {
                    continue;
                }
                String physicalId = resource.physicalResourceId();
                if (physicalId == null || !physicalId.contains("/service/")) {
                    continue;
                }
                int serviceIdx = physicalId.indexOf("/service/") + "/service/".length();
                int clusterEnd = physicalId.indexOf('/', serviceIdx);
                if (clusterEnd < 0) {
                    continue;
                }
                String cluster = physicalId.substring(serviceIdx, clusterEnd);
                String service = physicalId.substring(clusterEnd + 1);
                ecs.updateService(UpdateServiceRequest.builder()
                    .cluster(cluster)
                    .service(service)
                    .forceNewDeployment(true)
                    .build());
                System.out.println("   ✅ Restarted ECS service " + service + " to refresh OIDC secrets");
            }
        } catch (Exception e) {
            System.out.println("   ⚠️  ECS service restart skipped: " + e.getMessage());
        }
    }

    @Override
    public void delete(String stackName) throws IOException {
        if (!stackExists(stackName)) {
            return;
        }
        try {
            cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
            cloudFormation.waiter().waitUntilStackDeleteComplete(
                DescribeStacksRequest.builder().stackName(stackName).build());
        } catch (Exception e) {
            if (stackExists(stackName)) {
                throw new IOException("Failed to delete LocalStack stack " + stackName, e);
            }
        }
    }

    private boolean stackIsDeployable(String stackName) {
        try {
            String status = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder().stackName(stackName).build())
                .stacks().getFirst().stackStatusAsString();
            if (status == null) {
                return false;
            }
            String normalized = status.toUpperCase(Locale.ROOT);
            return normalized.contains("COMPLETE") && !normalized.contains("ROLLBACK");
        } catch (CloudFormationException e) {
            return false;
        }
    }

    /**
     * Active {@code *-localstack} stacks (excludes DELETE*).
     */
    public List<String> listActiveLocalstackStacks() {
        List<String> names = new ArrayList<>();
        String nextToken = null;
        do {
            var response = cloudFormation.listStacks(ListStacksRequest.builder()
                .nextToken(nextToken)
                .build());
            for (StackSummary summary : response.stackSummaries()) {
                String name = summary.stackName();
                String status = summary.stackStatusAsString();
                if (name == null || !name.endsWith("-localstack")) {
                    continue;
                }
                if (status != null && status.toUpperCase(Locale.ROOT).contains("DELETE")) {
                    continue;
                }
                names.add(name);
            }
            nextToken = response.nextToken();
        } while (nextToken != null);
        return List.copyOf(names);
    }

    /** Read stack tags; empty when stack missing or has none. */
    public Map<String, String> stackTags(String stackName) {
        try {
            List<Tag> tags = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder().stackName(stackName).build())
                .stacks().getFirst().tags();
            Map<String, String> result = new LinkedHashMap<>();
            if (tags != null) {
                for (Tag tag : tags) {
                    if (tag.key() != null) {
                        result.put(tag.key(), tag.value());
                    }
                }
            }
            return Map.copyOf(result);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * When replacing an app under a new stack name, delete other active LocalStack stacks
     * for the same {@code applicationId} (tag or {@code deployment-contexts} catalog).
     */
    public LocalSameApplicationStackReplacer.Result replaceSameApplicationStacks(
            String applicationId,
            String keepCfnStack,
            Path catalogDirectory) {
        return LocalSameApplicationStackReplacer.replace(
            applicationId,
            keepCfnStack,
            REPLACE_SAME_APP_ENV,
            DeploymentTarget.LOCALSTACK,
            this::listActiveLocalstackStacks,
            this::stackTags,
            stack -> LocalDeploymentCatalog.applicationIdForCfnStack(
                catalogDirectory, stack, DeploymentTarget.LOCALSTACK),
            this::delete);
    }

    /**
     * Confirm the stack exists and return its outputs.
     *
     * @throws IOException when the stack is missing or outputs cannot be read
     */
    public Map<String, String> verifyDeployment(String stackName) throws IOException {
        if (!stackExists(stackName)) {
            throw new IOException("Stack does not exist: " + stackName);
        }
        return outputs(stackName);
    }

    @Override
    public boolean stackExists(String stackName) {
        try {
            var stacks = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder().stackName(stackName).build())
                .stacks();
            if (stacks == null || stacks.isEmpty()) {
                return false;
            }
            String status = stacks.getFirst().stackStatusAsString();
            // DELETE_COMPLETE (and in-progress deletes) must not short-circuit redeploy.
            return status == null || !status.toUpperCase(Locale.ROOT).contains("DELETE");
        } catch (CloudFormationException e) {
            if (e.statusCode() == 400 || e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Map<String, String> outputs(String stackName) {
        List<Output> stackOutputs = cloudFormation.describeStacks(
            DescribeStacksRequest.builder().stackName(stackName).build())
            .stacks().getFirst().outputs();
        Map<String, String> result = new LinkedHashMap<>();
        for (Output output : stackOutputs) {
            result.put(output.outputKey(), output.outputValue());
        }
        return Map.copyOf(result);
    }

    private software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse
            waitForChangeSet(String stackName, String changeSetName) throws IOException {
        Instant deadline = Instant.now().plus(OPERATION_TIMEOUT);
        DescribeChangeSetRequest request = DescribeChangeSetRequest.builder()
            .stackName(stackName)
            .changeSetName(changeSetName)
            .build();
        while (Instant.now().isBefore(deadline)) {
            var response = cloudFormation.describeChangeSet(request);
            if (response.status() == ChangeSetStatus.CREATE_COMPLETE
                    || response.status() == ChangeSetStatus.FAILED) {
                return response;
            }
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for LocalStack change set", e);
            }
        }
        throw new IOException("Timed out waiting for LocalStack change set " + changeSetName);
    }

    private void deleteChangeSet(String stackName, String changeSetName) {
        cloudFormation.deleteChangeSet(DeleteChangeSetRequest.builder()
            .stackName(stackName)
            .changeSetName(changeSetName)
            .build());
    }

    private boolean templateMatches(String stackName, String candidate) throws IOException {
        try {
            String deployed = cloudFormation.getTemplate(
                GetTemplateRequest.builder().stackName(stackName).build()).templateBody();
            if (deployed == null || deployed.isBlank()) {
                return false;
            }
            return MAPPER.readTree(deployed).equals(MAPPER.readTree(candidate));
        } catch (CloudFormationException e) {
            // Incomplete / missing template — force a real create/update path
            return false;
        } catch (Exception e) {
            throw new IOException("Unable to compare deployed LocalStack template", e);
        }
    }

    private String recentEvents(String stackName) {
        try {
            StringBuilder summary = new StringBuilder();
            var events = cloudFormation.describeStackEvents(
                DescribeStackEventsRequest.builder().stackName(stackName).build())
                .stackEvents();

            events.stream()
                .filter(event -> event.resourceStatusAsString() != null
                    && (event.resourceStatusAsString().contains("FAILED")
                        || event.resourceStatusAsString().contains("ROLLBACK")))
                .limit(5)
                .forEach(event -> summary.append(formatEvent(event)).append('\n'));

            if (summary.isEmpty()) {
                events.stream().limit(15).forEach(event ->
                    summary.append(formatEvent(event)).append('\n'));
            } else {
                summary.append("\nRecent events:\n");
                events.stream().limit(10).forEach(event ->
                    summary.append(formatEvent(event)).append('\n'));
            }
            return summary.toString();
        } catch (Exception e) {
            return "Unable to read stack events: " + e.getMessage();
        }
    }

    private static String formatEvent(
            software.amazon.awssdk.services.cloudformation.model.StackEvent event) {
        return "%s %s %s %s".formatted(
            event.resourceStatusAsString(),
            event.resourceType(),
            event.logicalResourceId(),
            event.resourceStatusReason() == null ? "" : event.resourceStatusReason());
    }

    private static String contextStackNameFromLocalStack(String localStackName) {
        if (localStackName != null && localStackName.endsWith("-localstack")) {
            return localStackName.substring(0, localStackName.length() - "-localstack".length());
        }
        return localStackName;
    }

    private String uploadTemplateToS3(String stackName, String templateBody) throws IOException {
        ensureTemplateBucket();
        String key = stackName + ".template.json";
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(TEMPLATE_BUCKET)
                .key(key)
                .contentType("application/json")
                .build(),
            RequestBody.fromString(templateBody, StandardCharsets.UTF_8));
        return endpoint + "/" + TEMPLATE_BUCKET + "/" + key;
    }

    private void ensureTemplateBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(TEMPLATE_BUCKET).build());
        } catch (NoSuchBucketException e) {
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(TEMPLATE_BUCKET).build());
            } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
                // concurrent create
            }
        }
    }

    private static boolean isNoOp(String reason) {
        String normalized = reason.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("didn't contain changes")
            || normalized.contains("no updates")
            || normalized.contains("no changes");
    }

    private static LocalResourceChange toChange(ResourceChange resource) {
        return new LocalResourceChange(
            resource.actionAsString(),
            resource.logicalResourceId(),
            resource.resourceType(),
            resource.replacementAsString()
        );
    }

    @Override
    public void close() {
        cloudFormation.close();
        s3.close();
    }

    /** @deprecated use {@link LocalResourceChange}. */
    @Deprecated
    public record Change(
            String action,
            String logicalResourceId,
            String resourceType,
            String replacement) {
    }

    /** @deprecated use {@link LocalDeployResult}. */
    @Deprecated
    public record DeploymentResult(
            String stackName,
            boolean created,
            boolean noOp,
            List<Change> changes,
            Map<String, String> outputs) {
    }
}
