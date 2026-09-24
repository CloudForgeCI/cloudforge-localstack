package com.cloudforgeci.localstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.DescribeUserPoolClientRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.OAuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UpdateUserPoolClientRequest;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * LocalStack cannot reliably run CDK {@code Custom::AWS} Lambdas. The adapter strips
 * those deployment-time writers; this reconciler applies Cognito redirect URLs and copies the
 * generated client secret after deploy.
 */
final class LocalStackCognitoSecretReconciler {

    private LocalStackCognitoSecretReconciler() {
    }

    static boolean reconcileAfterDeploy(
            CloudFormationClient cloudFormation,
            String stackName,
            String endpoint,
            String region,
            String adaptedTemplateBody) {
        String userPoolId = null;
        String clientId = null;
        String secretId = null;
        for (StackResource resource : cloudFormation.describeStackResources(
                DescribeStackResourcesRequest.builder().stackName(stackName).build())
            .stackResources()) {
            if ("AWS::Cognito::UserPool".equals(resource.resourceType())) {
                userPoolId = resource.physicalResourceId();
            } else if ("AWS::Cognito::UserPoolClient".equals(resource.resourceType())) {
                clientId = resource.physicalResourceId();
            } else if ("AWS::SecretsManager::Secret".equals(resource.resourceType())
                    && resource.logicalResourceId().contains("CognitoClientSecret")) {
                secretId = resource.physicalResourceId();
            }
        }
        if (userPoolId == null) {
            userPoolId = existingUserPoolId(adaptedTemplateBody);
        }
        if (userPoolId == null || clientId == null) {
            return false;
        }

        URI endpointUri = URI.create(endpoint);
        try (CognitoIdentityProviderClient cognito = CognitoIdentityProviderClient.builder()
                .endpointOverride(endpointUri)
                .region(software.amazon.awssdk.regions.Region.of(region))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
                .build();
             SecretsManagerClient secrets = SecretsManagerClient.builder()
                .endpointOverride(endpointUri)
                .region(software.amazon.awssdk.regions.Region.of(region))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
                .build()) {

            var client = cognito.describeUserPoolClient(
                    DescribeUserPoolClientRequest.builder()
                        .userPoolId(userPoolId)
                        .clientId(clientId)
                        .build())
                .userPoolClient();
            boolean redirectsSynced = reconcileRedirectUrls(
                cognito, adaptedTemplateBody, userPoolId, clientId, client);

            if (secretId == null) {
                return redirectsSynced;
            }
            String clientSecret = client.clientSecret();
            if (clientSecret == null || clientSecret.isBlank()) {
                System.out.println("   ⚠️  Cognito client secret not available; OIDC login may require redeploy");
                return redirectsSynced;
            }
            secrets.putSecretValue(PutSecretValueRequest.builder()
                .secretId(secretId)
                .secretString(clientSecret)
                .build());
            System.out.println("   ✅ Synced Cognito client secret to Secrets Manager for application-oidc");
            return true;
        } catch (Exception e) {
            System.out.println("   ⚠️  Cognito secret sync skipped: " + e.getMessage());
            return false;
        }
    }

    private static boolean reconcileRedirectUrls(
            CognitoIdentityProviderClient cognito,
            String adaptedTemplateBody,
            String userPoolId,
            String clientId,
            software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolClientType client) {
        CognitoRedirectUrls desired = redirectUrlsFromAdaptedTemplate(adaptedTemplateBody);
        boolean oauthEnabled = Boolean.TRUE.equals(client.allowedOAuthFlowsUserPoolClient())
            && client.allowedOAuthFlows().contains(OAuthFlowType.CODE)
            && client.allowedOAuthScopes().containsAll(List.of("openid", "profile", "email"))
            && client.supportedIdentityProviders().contains("COGNITO");
        if (desired == null || (desired.callbackUrls().equals(client.callbackURLs())
                && desired.logoutUrls().equals(client.logoutURLs())
                && oauthEnabled)) {
            return false;
        }
        cognito.updateUserPoolClient(UpdateUserPoolClientRequest.builder()
            .userPoolId(userPoolId)
            .clientId(clientId)
            .callbackURLs(desired.callbackUrls())
            .logoutURLs(desired.logoutUrls())
            .allowedOAuthFlowsUserPoolClient(true)
            .allowedOAuthFlows(OAuthFlowType.CODE)
            .allowedOAuthScopes("openid", "profile", "email")
            .supportedIdentityProviders("COGNITO")
            .build());
        System.out.println("   ✅ Reconciled Cognito callback URLs and OAuth settings for LocalStack application-oidc");
        return true;
    }

    private static CognitoRedirectUrls redirectUrlsFromAdaptedTemplate(String adaptedTemplateBody) {
        try {
            JsonNode resources = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(adaptedTemplateBody).path("Resources");
            for (JsonNode resource : resources) {
                if (!"AWS::Cognito::UserPoolClient".equals(resource.path("Type").asText())) {
                    continue;
                }
                JsonNode properties = resource.path("Properties");
                List<String> callbacks = textValues(properties.path("CallbackURLs"));
                List<String> logouts = textValues(properties.path("LogoutURLs"));
                if (!callbacks.isEmpty() && !logouts.isEmpty()) {
                    return new CognitoRedirectUrls(callbacks, logouts);
                }
            }
        } catch (Exception e) {
            System.out.println("   ⚠️  Cognito redirect URL sync skipped: " + e.getMessage());
        }
        return null;
    }

    private static String existingUserPoolId(String adaptedTemplateBody) {
        try {
            JsonNode resources = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(adaptedTemplateBody).path("Resources");
            for (JsonNode resource : resources) {
                if (!"AWS::Cognito::UserPoolClient".equals(resource.path("Type").asText())) {
                    continue;
                }
                JsonNode userPoolId = resource.path("Properties").path("UserPoolId");
                if (userPoolId.isTextual() && !userPoolId.asText().isBlank()) {
                    return userPoolId.asText();
                }
            }
        } catch (Exception e) {
            System.out.println("   ⚠️  Existing Cognito user pool lookup skipped: " + e.getMessage());
        }
        return null;
    }

    private static List<String> textValues(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) {
            values.forEach(value -> {
                if (value.isTextual()) {
                    result.add(value.asText());
                }
            });
        }
        return result;
    }

    private record CognitoRedirectUrls(List<String> callbackUrls, List<String> logoutUrls) {
    }

    static void removeCdkCognitoCustomResources(
            ObjectNode template,
            List<com.cloudforge.core.local.TemplateAdaptation> adaptations) {
        if (!templateHasApplicationOidc(template)) {
            return;
        }
        ObjectNode resources = template.get("Resources") instanceof ObjectNode objectNode
            ? objectNode : null;
        if (resources == null) {
            return;
        }
        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            String id = entry.getKey();
            String type = entry.getValue().path("Type").asText();
            if ("Custom::AWS".equals(type)) {
                remove.add(id);
            }
            if ("AWS::Lambda::Function".equals(type)
                    && id.startsWith("AWS679f53fac002430cb0da5b7982bd22872")) {
                remove.add(id);
            }
            if ("AWS::IAM::Role".equals(type)
                    && id.contains("AWS679f53fac002430cb0da5b7982bd2287ServiceRole")) {
                remove.add(id);
            }
            if ("AWS::IAM::Policy".equals(type) && id.contains("CustomResourcePolicy")) {
                remove.add(id);
            }
        });
        if (remove.isEmpty()) {
            return;
        }
        remove.forEach(resources::remove);
        cleanupDependsOn(resources, remove);
        resources.properties().forEach(entry -> {
            ObjectNode resource = entry.getValue() instanceof ObjectNode objectNode ? objectNode : null;
            if (resource == null) {
                return;
            }
            if ("AWS::SecretsManager::Secret".equals(resource.path("Type").asText())
                    && entry.getKey().contains("CognitoClientSecret")) {
                ObjectNode properties = resource.get("Properties") instanceof ObjectNode props
                    ? props : resource.putObject("Properties");
                properties.remove("GenerateSecretString");
                properties.put("SecretString", "pending-localstack-sync");
            }
        });
        adaptations.add(new com.cloudforge.core.local.TemplateAdaptation(
            "Resources",
            "LocalStack omits CDK Custom::AWS writers; Cognito secret sync runs after deployment",
            com.fasterxml.jackson.databind.node.NullNode.instance));
    }

    static boolean templateHasApplicationOidcPublic(ObjectNode template) {
        return templateHasApplicationOidc(template);
    }

    private static boolean templateHasApplicationOidc(ObjectNode template) {
        ObjectNode resources = template.get("Resources") instanceof ObjectNode objectNode
            ? objectNode : null;
        if (resources == null) {
            return false;
        }
        for (JsonNode resource : resources) {
            if ("AWS::Cognito::UserPoolClient".equals(resource.path("Type").asText())) {
                return true;
            }
            if (!"AWS::ECS::TaskDefinition".equals(resource.path("Type").asText())) {
                continue;
            }
            JsonNode containers = resource.path("Properties").path("ContainerDefinitions");
            if (!(containers instanceof ArrayNode array)) {
                continue;
            }
            for (JsonNode container : array) {
                JsonNode environment = container.path("Environment");
                if (!(environment instanceof ArrayNode envArray)) {
                    continue;
                }
                for (JsonNode env : envArray) {
                    if ("CASC_JENKINS_CONFIG".equals(env.path("Name").asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void cleanupDependsOn(ObjectNode resources, List<String> removed) {
        resources.properties().forEach(entry -> {
            ObjectNode resource = entry.getValue() instanceof ObjectNode objectNode ? objectNode : null;
            if (resource == null || !resource.has("DependsOn")) {
                return;
            }
            JsonNode dependsOnNode = resource.get("DependsOn");
            List<String> kept = new ArrayList<>();
            if (dependsOnNode.isArray()) {
                dependsOnNode.forEach(node -> {
                    String name = node.asText();
                    if (!removed.contains(name)) {
                        kept.add(name);
                    }
                });
            } else if (dependsOnNode.isTextual()) {
                String name = dependsOnNode.asText();
                if (!removed.contains(name)) {
                    kept.add(name);
                }
            }
            resource.remove("DependsOn");
            if (kept.size() == 1) {
                resource.put("DependsOn", kept.getFirst());
            } else if (kept.size() > 1) {
                ArrayNode filtered = resource.putArray("DependsOn");
                kept.forEach(filtered::add);
            }
        });
    }

}
