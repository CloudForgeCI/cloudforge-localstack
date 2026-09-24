package com.cloudforgeci.localstack;

import com.cloudforge.core.local.LocalStackCapabilitySnapshot;
import com.cloudforge.core.local.LocalStackServiceCapability;
import com.cloudforge.core.local.LocalStackTierProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Probes LocalStack gateway health and derives a {@link LocalStackCapabilitySnapshot}
 * for tier-aware template adaptation and deploy preflight.
 */
public final class LocalStackCapabilityProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private LocalStackCapabilityProbe() {
    }

    public static LocalStackCapabilitySnapshot probeDefault() {
        return probe(LocalStackDeployer.resolveEndpoint());
    }

    public static LocalStackCapabilitySnapshot probe(String endpoint) {
        return probe(URI.create(normalizeEndpoint(endpoint)));
    }

    public static LocalStackCapabilitySnapshot probe(URI endpoint) {
        LocalStackTierProfile override = LocalStackTierProfile.fromEnvOverride();
        Set<LocalStackServiceCapability> envCaps = capabilitiesFromEnv();
        if (override != null && !envCaps.isEmpty()) {
            return new LocalStackCapabilitySnapshot(
                true,
                endpoint,
                override,
                "env",
                "env",
                envCaps,
                Map.of("source", "LOCALSTACK_TIER_PROFILE+LOCALSTACK_CAPABILITIES"));
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
            URI healthUri = endpoint.resolve("/_localstack/health");
            HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(healthUri).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return LocalStackCapabilitySnapshot.unavailable(
                    endpoint, "health HTTP " + response.statusCode());
            }

            JsonNode root = MAPPER.readTree(response.body());
            Map<String, Object> details = new LinkedHashMap<>();
            Set<LocalStackServiceCapability> capabilities = envCaps.isEmpty()
                ? capabilitiesFromHealth(root, details)
                : envCaps;
            LocalStackTierProfile tier = override != null
                ? override
                : inferTier(capabilities);
            String edition = textOrDefault(root.path("edition"), inferEdition(capabilities));
            String version = textOrDefault(root.path("version"), "unknown");
            details.put("healthStatus", response.statusCode());
            return new LocalStackCapabilitySnapshot(
                true,
                endpoint,
                tier,
                edition,
                version,
                capabilities,
                details);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return LocalStackCapabilitySnapshot.unavailable(
                endpoint, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    static Set<LocalStackServiceCapability> capabilitiesFromHealth(
            JsonNode root,
            Map<String, Object> details) {
        EnumSet<LocalStackServiceCapability> caps = EnumSet.noneOf(LocalStackServiceCapability.class);
        JsonNode services = root.path("services");
        if (!services.isObject()) {
            // Healthy gateway with no breakdown — assume Base Fargate path for CloudForge dev
            caps.addAll(EnumSet.of(
                LocalStackServiceCapability.ECS,
                LocalStackServiceCapability.ELBV2,
                LocalStackServiceCapability.EC2,
                LocalStackServiceCapability.AUTOSCALING,
                LocalStackServiceCapability.RDS,
                LocalStackServiceCapability.COGNITO));
            details.put("capabilitiesInferred", "default-base");
            return caps;
        }
        services.properties().forEach(entry -> {
            if (!isServiceAvailable(entry.getValue())) {
                return;
            }
            mapServiceName(entry.getKey()).ifPresent(caps::add);
        });
        details.put("services", services.toString());
        return caps;
    }

    private static java.util.Optional<LocalStackServiceCapability> mapServiceName(String service) {
        if (service == null) {
            return java.util.Optional.empty();
        }
        return switch (service.toLowerCase(Locale.ROOT)) {
            case "ecs" -> java.util.Optional.of(LocalStackServiceCapability.ECS);
            case "elbv2", "elb", "elasticloadbalancingv2" ->
                java.util.Optional.of(LocalStackServiceCapability.ELBV2);
            case "ec2" -> java.util.Optional.of(LocalStackServiceCapability.EC2);
            case "autoscaling", "application-autoscaling" ->
                java.util.Optional.of(LocalStackServiceCapability.AUTOSCALING);
            case "rds" -> java.util.Optional.of(LocalStackServiceCapability.RDS);
            case "efs" -> java.util.Optional.of(LocalStackServiceCapability.EFS);
            case "backup" -> java.util.Optional.of(LocalStackServiceCapability.BACKUP);
            case "cognito-idp", "cognito" ->
                java.util.Optional.of(LocalStackServiceCapability.COGNITO);
            default -> java.util.Optional.empty();
        };
    }

    private static boolean isServiceAvailable(JsonNode status) {
        if (status == null || status.isNull()) {
            return false;
        }
        String text = status.isTextual() ? status.asText() : status.toString();
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("available")
            || normalized.contains("running")
            || normalized.equals("ready");
    }

    static LocalStackTierProfile inferTier(Set<LocalStackServiceCapability> capabilities) {
        if (capabilities.contains(LocalStackServiceCapability.EFS)
                && capabilities.contains(LocalStackServiceCapability.BACKUP)) {
            return LocalStackTierProfile.ULTIMATE;
        }
        return LocalStackTierProfile.BASE;
    }

    private static String inferEdition(Set<LocalStackServiceCapability> capabilities) {
        return inferTier(capabilities) == LocalStackTierProfile.ULTIMATE ? "ultimate" : "base";
    }

    static Set<LocalStackServiceCapability> capabilitiesFromEnv() {
        String raw = System.getenv("LOCALSTACK_CAPABILITIES");
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        EnumSet<LocalStackServiceCapability> caps =
            EnumSet.noneOf(LocalStackServiceCapability.class);
        for (String part : raw.split("[,\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            caps.add(LocalStackServiceCapability.fromKey(part));
        }
        return caps;
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() && !node.asText().isBlank()
            ? node.asText()
            : defaultValue;
    }

    private static String normalizeEndpoint(String endpoint) {
        String trimmed = endpoint.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
