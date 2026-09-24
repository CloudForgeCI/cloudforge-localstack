package com.cloudforgeci.localstack;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;
import software.amazon.awssdk.services.rds.RdsClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Corrects the MySQL/MariaDB host:port {@link LocalStackTemplateAdapter} bakes into each ECS
 * task's environment at adapt time — necessarily a guess, per that method's own comment: the
 * adapter runs before the real RDS instance (and the LocalStack-assigned port it gets) exist. A
 * second MySQL/MariaDB instance already alive in the same LocalStack session — an unrelated
 * app's leftover, say — claims the guessed port first, silently wiring a fresh deploy's task to
 * the wrong database.
 *
 * <p>Runs after the stack's own RDS instance is up, looks up its real port, and — only when that
 * differs from the guess — registers a corrected task definition revision and updates the ECS
 * service to it. Same "adapt-time guess, deploy-time reconcile" shape as {@link
 * LocalStackCognitoSecretReconciler}/{@link LocalStackPostgresDatasourceReconciler}, just needing
 * a task definition revision (the value is a literal baked into the task, not something the
 * container re-reads live) rather than an SSM parameter update.</p>
 */
final class LocalStackMysqlPortReconciler {

    /** The port {@link LocalStackTemplateAdapter#rewriteDatabaseTaskEndpointsForLocalStack}
     *  always bakes in — see its own comment on why it can't do better at adapt time. */
    private static final int ASSUMED_PORT = 4510;

    private LocalStackMysqlPortReconciler() {
    }

    static boolean reconcileAfterDeploy(
            CloudFormationClient cloudFormation,
            String stackName,
            String endpoint,
            String region) {
        List<StackResource> resources = cloudFormation.describeStackResources(
                DescribeStackResourcesRequest.builder().stackName(stackName).build())
            .stackResources();

        List<String> dbInstanceIds = resources.stream()
            .filter(r -> "AWS::RDS::DBInstance".equals(r.resourceType()))
            .map(StackResource::physicalResourceId)
            .filter(Objects::nonNull)
            .toList();
        if (dbInstanceIds.isEmpty()) {
            return false;
        }

        Integer realPort = resolveRealMysqlPort(endpoint, region, dbInstanceIds);
        if (realPort == null || realPort == ASSUMED_PORT) {
            return false;
        }

        List<String> taskDefinitionIds = resources.stream()
            .filter(r -> "AWS::ECS::TaskDefinition".equals(r.resourceType()))
            .map(StackResource::physicalResourceId)
            .filter(Objects::nonNull)
            .toList();
        if (taskDefinitionIds.isEmpty()) {
            return false;
        }

        try (EcsClient ecs = EcsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")))
                .build()) {
            String correctedRevisionArn = null;
            for (String taskDefinitionId : taskDefinitionIds) {
                String arn = correctTaskDefinition(ecs, taskDefinitionId, realPort);
                if (arn != null) {
                    correctedRevisionArn = arn;
                }
            }
            if (correctedRevisionArn == null) {
                return false;
            }
            redeployServicesOntoRevision(ecs, resources, correctedRevisionArn);
            System.out.println("   ✅ Corrected MySQL port in LocalStack task definition "
                + correctedRevisionArn + " (" + ASSUMED_PORT + " → " + realPort + ")");
            return true;
        } catch (Exception e) {
            System.out.println("   ⚠️  LocalStack MySQL port reconcile skipped: " + e.getMessage());
            return false;
        }
    }

    private static Integer resolveRealMysqlPort(String endpoint, String region, List<String> dbInstanceIds) {
        try (RdsClient rds = RdsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")))
                .build()) {
            for (var instance : rds.describeDBInstances().dbInstances()) {
                String engine = instance.engine();
                if (dbInstanceIds.contains(instance.dbInstanceIdentifier())
                        && engine != null
                        && (engine.startsWith("mysql") || engine.startsWith("mariadb"))
                        && instance.endpoint() != null) {
                    return instance.endpoint().port();
                }
            }
        } catch (Exception e) {
            System.out.println("   ⚠️  LocalStack RDS port lookup skipped: " + e.getMessage());
        }
        return null;
    }

    /** Registers a corrected revision when {@code taskDefinitionId}'s environment bakes in the
     *  wrong port, and returns its ARN (for pinning services onto it — LocalStack's {@code
     *  UpdateService} doesn't reliably resolve a bare family name to "latest active revision" the
     *  way real AWS does, so the exact revision ARN is what actually sticks) — {@code null} if
     *  nothing needed correcting. */
    private static String correctTaskDefinition(EcsClient ecs, String taskDefinitionId, int realPort) {
        TaskDefinition current = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition(taskDefinitionId).build())
            .taskDefinition();

        List<ContainerDefinition> updatedContainers = new ArrayList<>();
        boolean changed = false;
        for (ContainerDefinition container : current.containerDefinitions()) {
            List<KeyValuePair> updatedEnv = new ArrayList<>();
            boolean containerChanged = false;
            for (KeyValuePair variable : container.environment()) {
                String value = variable.value();
                String corrected = correctedValue(variable.name(), value, realPort);
                if (corrected != null) {
                    containerChanged = true;
                    updatedEnv.add(KeyValuePair.builder().name(variable.name()).value(corrected).build());
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

    /** {@code null} means no correction needed. Handles the same two baked-in shapes {@link
     *  LocalStackTemplateAdapter} produces at adapt time — a combined {@code "host:port"} value
     *  (e.g. {@code WORDPRESS_DB_HOST=cfc-localstack:4510}) and a bare {@code *_DB_PORT} literal —
     *  but as plain strings, since by deploy time every CDK token has already resolved; no need to
     *  re-detect Fn::Join/Fn::GetAtt shapes the way the adapter itself has to. Package-visible for
     *  tests. */
    static String correctedValue(String name, String value, int realPort) {
        if (value == null) {
            return null;
        }
        String assumedSuffix = ":" + ASSUMED_PORT;
        if (value.contains(assumedSuffix)) {
            return value.replace(assumedSuffix, ":" + realPort);
        }
        boolean isPortVariable = name != null && (name.endsWith("_DB_PORT") || "DB_PORT".equals(name));
        if (isPortVariable && String.valueOf(ASSUMED_PORT).equals(value)) {
            return String.valueOf(realPort);
        }
        return null;
    }

    /** Points every ECS service in this stack at {@code revisionArn} — {@code forceNewDeployment}
     *  alone (what {@code restartEcsServices} does for the other reconcilers) would just keep
     *  recreating tasks from whatever revision the service already had pinned. Passes full ARNs
     *  for both {@code cluster}/{@code service} rather than the bare names {@code
     *  restartEcsServices} parses out — LocalStack's {@code UpdateService} silently no-ops (no
     *  exception, service unchanged) on the bare-name form here, but applies reliably given the
     *  full ARNs. */
    private static void redeployServicesOntoRevision(
            EcsClient ecs, List<StackResource> resources, String revisionArn) {
        for (StackResource resource : resources) {
            if (!"AWS::ECS::Service".equals(resource.resourceType())) {
                continue;
            }
            String serviceArn = resource.physicalResourceId();
            if (serviceArn == null || !serviceArn.contains(":service/")) {
                continue;
            }
            int clusterEnd = serviceArn.indexOf('/', serviceArn.indexOf(":service/") + ":service/".length());
            if (clusterEnd < 0) {
                continue;
            }
            String clusterArn = serviceArn.substring(0, clusterEnd).replaceFirst(":service/", ":cluster/");
            try {
                ecs.updateService(UpdateServiceRequest.builder()
                    .cluster(clusterArn)
                    .service(serviceArn)
                    .taskDefinition(revisionArn)
                    .forceNewDeployment(true)
                    .build());
            } catch (Exception e) {
                // Not every service in the stack necessarily runs this task family (e.g. a
                // sidecar) — a real mismatch just leaves that one service unchanged rather than
                // failing the whole reconcile.
                System.out.println("   ⚠️  LocalStack MySQL port service redeploy skipped for "
                    + serviceArn + ": " + e.getMessage());
            }
        }
    }
}
