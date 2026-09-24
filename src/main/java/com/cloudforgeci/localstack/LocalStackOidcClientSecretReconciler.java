package com.cloudforgeci.localstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.GetTemplateRequest;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Corrects the {@code *_OIDC_CLIENT_SECRET} environment placeholder {@link
 * LocalStackTemplateAdapter#inlineOidcSecretForLocalStack} bakes into each ECS task's environment
 * at adapt time — LocalStack ECS cannot resolve a real {@code Secrets}/{@code ValueFrom} reference
 * to Secrets Manager, so the adapter inlines the literal string {@code "pending-localstack-sync"}
 * instead and leaves the sync to a post-deploy step, matching {@link
 * LocalStackCognitoSecretReconciler}'s own doc comment.
 *
 * <p>That existing reconciler only covers the Cognito-auto-provisioned client secret (discovered
 * via the stack's {@code AWS::Cognito::UserPoolClient}/{@code CognitoClientSecret} resources). It
 * has no path for the generic, manually-supplied {@code oidcClientSecretName} a deployer sets for
 * {@code external-idp}/{@code cloudforge-manager} OIDC providers. Without this reconciler, the
 * secret's value is correct in Secrets Manager but the running container keeps the placeholder,
 * because nothing re-reads the secret and patches it in.
 *
 * <p>Same "adapt-time placeholder, deploy-time reconcile" shape as {@link
 * LocalStackMysqlPortReconciler} — the placeholder is a literal baked into the task definition,
 * not something the container re-reads live, so fixing it needs a new task definition revision
 * and a service redeploy onto it, not just a bare {@code forceNewDeployment} on the old revision.
 *
 * <p>The secret's real name is recovered from the deployed template itself: {@link
 * com.cloudforgeci.api.storage.ContainerFactory} always attaches the IAM policy statement granting
 * read access to it under the fixed Sid {@code AllowReadOidcClientSecret} — a stable, reusable
 * anchor rather than trying to parse whichever of the two different ways the secret's *value* got
 * created (a deployer-supplied pre-existing secret, or {@link
 * com.cloudforgeci.api.security.ApplicationOidcFactory}'s own CDK-managed placeholder secret).
 */
final class LocalStackOidcClientSecretReconciler {

    private static final String PLACEHOLDER = "pending-localstack-sync";
    private static final String SID = "AllowReadOidcClientSecret";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LocalStackOidcClientSecretReconciler() {
    }

    static boolean reconcileAfterDeploy(
            CloudFormationClient cloudFormation,
            String stackName,
            String endpoint,
            String region) {
        List<StackResource> resources = cloudFormation.describeStackResources(
                DescribeStackResourcesRequest.builder().stackName(stackName).build())
            .stackResources();

        List<StackResource> services = resources.stream()
            .filter(r -> "AWS::ECS::Service".equals(r.resourceType()))
            .filter(r -> r.physicalResourceId() != null)
            .toList();
        if (services.isEmpty()) {
            return false;
        }

        Map<String, String> physicalIds = new HashMap<>();
        for (StackResource resource : resources) {
            if (resource.logicalResourceId() != null && resource.physicalResourceId() != null) {
                physicalIds.put(resource.logicalResourceId(), resource.physicalResourceId());
            }
        }

        String secretName = resolveOidcSecretName(cloudFormation, stackName, physicalIds);
        if (secretName == null) {
            return false;
        }

        String realValue = resolveRealSecretValue(endpoint, region, secretName);
        if (realValue == null) {
            return false;
        }

        try (EcsClient ecs = EcsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")))
                .build()) {
            // Discovered off each service's own currently-running task definition rather than the
            // stack's own AWS::ECS::TaskDefinition resources — DescribeStackResources doesn't
            // reliably surface that resource type on LocalStack, even for a stack whose
            // ECS::Service resource, and the task definition it points at, both demonstrably
            // exist and just updated. The service is the one thing guaranteed to name a real,
            // current revision.
            List<String> taskDefinitionIds = services.stream()
                .map(r -> currentTaskDefinitionArn(ecs, r))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
            if (taskDefinitionIds.isEmpty()) {
                return false;
            }

            String correctedRevisionArn = null;
            for (String taskDefinitionId : taskDefinitionIds) {
                String arn = correctTaskDefinition(ecs, taskDefinitionId, realValue);
                if (arn != null) {
                    correctedRevisionArn = arn;
                }
            }
            if (correctedRevisionArn == null) {
                return false;
            }
            redeployServicesOntoRevision(ecs, resources, correctedRevisionArn);
            System.out.println("   ✅ Synced OIDC client secret into LocalStack task definition "
                + correctedRevisionArn + " (from " + secretName + ")");
            return true;
        } catch (Exception e) {
            System.out.println("   ⚠️  LocalStack OIDC client secret reconcile skipped: " + e.getMessage());
            return false;
        }
    }

    /** Fetches the deployed template and walks every {@code AWS::IAM::Policy}'s statements for
     *  the fixed {@link #SID}, extracting the secret name from its {@code Resource} ARN — built by
     *  CDK as an {@code Fn::Join} of literal segments (partition ref, region/account, then the
     *  literal {@code secret:<name>} suffix, sometimes with a trailing {@code -??????} version
     *  wildcard from the L1 escape hatch) rather than a plain string, so it's reassembled from the
     *  join's parts instead of read as text directly. */
    private static String resolveOidcSecretName(
            CloudFormationClient cloudFormation, String stackName, Map<String, String> physicalIds) {
        try {
            JsonNode template = MAPPER.readTree(
                cloudFormation.getTemplate(GetTemplateRequest.builder().stackName(stackName).build())
                    .templateBody());
            for (JsonNode resource : template.path("Resources")) {
                if (!"AWS::IAM::Policy".equals(resource.path("Type").asText())) {
                    continue;
                }
                for (JsonNode statement : resource.path("Properties").path("PolicyDocument").path("Statement")) {
                    if (!SID.equals(statement.path("Sid").asText())) {
                        continue;
                    }
                    String name = secretNameFromResourceArn(statement.path("Resource"), physicalIds);
                    if (name != null) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("   ⚠️  LocalStack OIDC secret name lookup skipped: " + e.getMessage());
        }
        return null;
    }

    /** A Cognito-provisioned client secret is granted by {@code Ref} to the secret resource, which
     *  has no literal {@code :secret:<name>} text to extract, so that case resolves to the
     *  secret's physical id (its ARN) instead. */
    static String secretNameFromResourceArn(JsonNode resourceNode, Map<String, String> physicalIds) {
        JsonNode ref = resourceNode.path("Ref");
        if (ref.isTextual()) {
            return physicalIds.get(ref.asText());
        }
        JsonNode joinParts = resourceNode.path("Fn::Join").path(1);
        if (!joinParts.isArray()) {
            return resourceNode.isTextual() ? afterSecretMarker(resourceNode.asText()) : null;
        }
        for (JsonNode part : joinParts) {
            if (part.isTextual()) {
                String extracted = afterSecretMarker(part.asText());
                if (extracted != null) {
                    return extracted;
                }
            }
        }
        return null;
    }

    private static String afterSecretMarker(String text) {
        int idx = text.indexOf(":secret:");
        if (idx < 0) {
            return null;
        }
        String name = text.substring(idx + ":secret:".length());
        // CDK's L1 ARN form sometimes appends a "-??????" 6-char version wildcard suffix.
        return name.endsWith("-??????") ? name.substring(0, name.length() - "-??????".length()) : name;
    }

    private static String resolveRealSecretValue(String endpoint, String region, String secretName) {
        try (SecretsManagerClient secrets = SecretsManagerClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")))
                .build()) {
            String value = secrets.getSecretValue(GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build())
                .secretString();
            boolean isPlaceholder = value == null || value.isBlank()
                || "PLACEHOLDER-UPDATE-WITH-ACTUAL-CLIENT-SECRET".equals(value);
            return isPlaceholder ? null : value;
        } catch (Exception e) {
            System.out.println("   ⚠️  LocalStack OIDC client secret lookup skipped: " + e.getMessage());
            return null;
        }
    }

    /** Registers a corrected revision when {@code taskDefinitionId}'s environment still carries
     *  the {@link #PLACEHOLDER}, and returns its ARN — {@code null} if nothing needed correcting.
     *  Matches {@link LocalStackMysqlPortReconciler#correctTaskDefinition}'s exact shape: the value
     *  is baked directly into the task definition, so only a fresh revision (not a live re-read)
     *  picks up the correction. */
    private static String correctTaskDefinition(EcsClient ecs, String taskDefinitionId, String realValue) {
        TaskDefinition current = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition(taskDefinitionId).build())
            .taskDefinition();

        List<ContainerDefinition> updatedContainers = new ArrayList<>();
        boolean changed = false;
        for (ContainerDefinition container : current.containerDefinitions()) {
            List<KeyValuePair> updatedEnv = new ArrayList<>();
            boolean containerChanged = false;
            for (KeyValuePair variable : container.environment()) {
                if (variable.name() != null && variable.name().endsWith("OIDC_CLIENT_SECRET")
                        && PLACEHOLDER.equals(variable.value())) {
                    containerChanged = true;
                    updatedEnv.add(KeyValuePair.builder().name(variable.name()).value(realValue).build());
                } else {
                    updatedEnv.add(variable);
                }
            }
            if (containerChanged) {
                changed = true;
                updatedContainers.add(container.toBuilder().environment(updatedEnv).build());
            } else {
                updatedContainers.add(container);
            }
        }
        if (!changed) {
            return null;
        }

        var registered = ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
            .family(current.family())
            .taskRoleArn(current.taskRoleArn())
            .executionRoleArn(current.executionRoleArn())
            .networkMode(current.networkMode())
            .containerDefinitions(updatedContainers)
            .volumes(current.volumes())
            .placementConstraints(current.placementConstraints())
            .requiresCompatibilities(current.requiresCompatibilities())
            .cpu(current.cpu())
            .memory(current.memory())
            .build());
        return registered.taskDefinition().taskDefinitionArn();
    }

    /** The task definition a service is *actually* running right now, per ECS itself — see the
     *  class javadoc on why this is queried instead of trusted from the CFN stack resources. */
    private static String currentTaskDefinitionArn(
            EcsClient ecs, StackResource serviceResource) {
        String serviceArn = serviceResource.physicalResourceId();
        String clusterArn = clusterArnForService(serviceArn);
        if (clusterArn == null) {
            return null;
        }
        try {
            var described = ecs.describeServices(software.amazon.awssdk.services.ecs.model.DescribeServicesRequest
                .builder().cluster(clusterArn).services(serviceArn).build());
            return described.services().isEmpty() ? null : described.services().get(0).taskDefinition();
        } catch (Exception e) {
            System.out.println("   ⚠️  LocalStack OIDC client secret task lookup skipped for "
                + serviceArn + ": " + e.getMessage());
            return null;
        }
    }

    private static String clusterArnForService(String serviceArn) {
        if (serviceArn == null || !serviceArn.contains(":service/")) {
            return null;
        }
        int clusterEnd = serviceArn.indexOf('/', serviceArn.indexOf(":service/") + ":service/".length());
        if (clusterEnd < 0) {
            return null;
        }
        return serviceArn.substring(0, clusterEnd).replaceFirst(":service/", ":cluster/");
    }

    /** Points every ECS service in this stack at {@code revisionArn} — see {@link
     *  LocalStackMysqlPortReconciler#redeployServicesOntoRevision}'s own comment on why full ARNs
     *  (not bare names) are required for LocalStack's {@code UpdateService} to take effect. */
    private static void redeployServicesOntoRevision(
            EcsClient ecs, List<StackResource> resources, String revisionArn) {
        for (StackResource resource : resources) {
            if (!"AWS::ECS::Service".equals(resource.resourceType())) {
                continue;
            }
            String serviceArn = resource.physicalResourceId();
            String clusterArn = clusterArnForService(serviceArn);
            if (clusterArn == null) {
                continue;
            }
            try {
                ecs.updateService(UpdateServiceRequest.builder()
                    .cluster(clusterArn)
                    .service(serviceArn)
                    .taskDefinition(revisionArn)
                    .forceNewDeployment(true)
                    .build());
            } catch (Exception e) {
                System.out.println("   ⚠️  LocalStack OIDC client secret service redeploy skipped for "
                    + serviceArn + ": " + e.getMessage());
            }
        }
    }
}
