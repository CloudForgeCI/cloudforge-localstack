package com.cloudforgeci.localstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;

import java.net.URI;
import java.net.URLDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reconciles LocalStack-only PostgreSQL datasource parameters after CloudFormation
 * has resolved generated credentials. Canonical AWS parameters remain unchanged.
 */
final class LocalStackPostgresDatasourceReconciler {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LocalStackPostgresDatasourceReconciler() {
    }

    static boolean requiresDatasourceParameters(String adaptedTemplateBody) {
        return adaptedTemplateBody.contains(LocalStackPostgresCompanion.hostname())
            || !datasourceParameterLogicalIds(adaptedTemplateBody).isEmpty();
    }

    static boolean reconcileAfterDeploy(
            CloudFormationClient cloudFormation,
            String stackName,
            String endpoint,
            String region,
            String adaptedTemplateBody) {
        List<String> logicalIds = datasourceParameterLogicalIds(adaptedTemplateBody);
        boolean taskDatabasePrepared = ensureRdsDatabases(endpoint, region, adaptedTemplateBody);
        if (logicalIds.isEmpty()) return taskDatabasePrepared;

        Map<String, String> physicalIds = cloudFormation.describeStackResources(
                DescribeStackResourcesRequest.builder().stackName(stackName).build())
            .stackResources().stream()
            .filter(resource -> "AWS::SSM::Parameter".equals(resource.resourceType()))
            .collect(java.util.stream.Collectors.toMap(
                StackResource::logicalResourceId,
                StackResource::physicalResourceId,
                (first, ignored) -> first));
        URI endpointUri = URI.create(endpoint);
        boolean changed = false;
        try (SsmClient ssm = SsmClient.builder()
                .endpointOverride(endpointUri)
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")))
                .build()) {
            for (String logicalId : logicalIds) {
                String parameterName = physicalIds.get(logicalId);
                if (parameterName == null || parameterName.isBlank()) {
                    continue;
                }
                String current = ssm.getParameter(GetParameterRequest.builder()
                        .name(parameterName).withDecryption(true).build())
                    .parameter().value();
                Datasource datasource = Datasource.parse(current);
                if (datasource == null) {
                    System.out.println("   ⚠️  LocalStack PostgreSQL datasource sync skipped for "
                        + parameterName + ": unsupported URL");
                    continue;
                }
                LocalStackPostgresCompanion.ensureDatabase(datasource.username(), datasource.database());
                String localValue = datasource.localValue();
                if (!localValue.equals(current)) {
                    ssm.putParameter(PutParameterRequest.builder()
                        .name(parameterName)
                        .type(ParameterType.STRING)
                        .value(localValue)
                        .overwrite(true)
                        .build());
                    changed = true;
                    System.out.println("   ✅ Reconciled PostgreSQL datasource for " + parameterName);
                }
            }
        } catch (Exception e) {
            System.out.println("   ⚠️  LocalStack PostgreSQL datasource sync skipped: " + e.getMessage());
        }
        return changed || taskDatabasePrepared;
    }

    private static boolean ensureRdsDatabases(String endpoint, String region, String template) {
        try {
            try (RdsClient rds = RdsClient.builder().endpointOverride(URI.create(endpoint))
                    .region(Region.of(region)).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test"))).build()) {
                for (var instance : rds.describeDBInstances(DescribeDbInstancesRequest.builder().build()).dbInstances()) {
                    if (instance.engine() != null && instance.engine().startsWith("postgres")
                            && instance.masterUsername() != null && instance.dbName() != null) {
                        LocalStackPostgresCompanion.ensureDatabase(instance.masterUsername(), instance.dbName());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("   ⚠️  LocalStack RDS metadata unavailable: " + e.getMessage());
        }
        try {
            return ensureTaskEnvironmentDatabases(template);
        } catch (Exception e) {
            System.out.println("   ⚠️  LocalStack task-environment database provisioning skipped: " + e.getMessage());
            return false;
        }
    }

    /** Fallback for LocalStack RDS responses that omit DBName or MasterUsername. */
    private static boolean ensureTaskEnvironmentDatabases(String template) throws IOException {
        boolean configured = false;
        try {
            JsonNode resources = MAPPER.readTree(template).path("Resources");
            for (JsonNode resource : resources) {
                if (!"AWS::ECS::TaskDefinition".equals(resource.path("Type").asText())) continue;
                String type = null;
                String username = null;
                String database = null;
                for (JsonNode container : resource.path("Properties").path("ContainerDefinitions")) {
                    for (JsonNode variable : container.path("Environment")) {
                        String name = variable.path("Name").asText();
                        String value = variable.path("Value").asText(null);
                        if ("MB_DB_TYPE".equals(name)) type = value;
                        if ("MB_DB_USER".equals(name)) username = value;
                        if ("MB_DB_DBNAME".equals(name)) database = value;
                    }
                }
                if ("postgres".equalsIgnoreCase(type) && username != null && database != null) {
                    LocalStackPostgresCompanion.ensureDatabase(username, database);
                    configured = true;
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("Cannot read adapted LocalStack template", e);
        }
        return configured;
    }

    private static List<String> datasourceParameterLogicalIds(String adaptedTemplateBody) {
        List<String> result = new ArrayList<>();
        try {
            JsonNode resources = MAPPER.readTree(adaptedTemplateBody).path("Resources");
            resources.properties().forEach(entry -> {
                JsonNode resource = entry.getValue();
                if (!"AWS::SSM::Parameter".equals(resource.path("Type").asText())) {
                    return;
                }
                String name = resource.path("Properties").path("Name").asText("");
                if (name.endsWith("/datasource-url")) {
                    result.add(entry.getKey());
                }
            });
        } catch (Exception ignored) {
            // The deployer will report malformed templates through its normal path.
        }
        return List.copyOf(result);
    }

    record Datasource(String username, String rawUserInfo, String database) {
        static Datasource parse(String value) {
            try {
                URI uri = URI.create(value);
                if (!("postgres".equalsIgnoreCase(uri.getScheme())
                        || "postgresql".equalsIgnoreCase(uri.getScheme()))
                        || uri.getRawUserInfo() == null
                        || uri.getRawPath() == null
                        || uri.getRawPath().length() <= 1) {
                    return null;
                }
                String rawUserInfo = uri.getRawUserInfo();
                int separator = rawUserInfo.indexOf(':');
                if (separator <= 0) {
                    return null;
                }
                String username = URLDecoder.decode(
                    rawUserInfo.substring(0, separator), StandardCharsets.UTF_8);
                String database = URLDecoder.decode(
                    uri.getRawPath().substring(1), StandardCharsets.UTF_8);
                return new Datasource(username, rawUserInfo, database);
            } catch (Exception ignored) {
                return null;
            }
        }

        String localValue() {
            return "postgres://" + rawUserInfo + "@" + LocalStackPostgresCompanion.hostname()
                + ":" + LocalStackPostgresCompanion.PORT + "/" + database
                + "?sslmode=disable&connect_timeout=10";
        }
    }
}
