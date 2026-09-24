package com.cloudforgeci.localstack;

import com.cloudforge.core.local.LocalEmulatorDefaults;
import com.cloudforge.core.local.TemplateAdaptation;
import com.cloudforge.core.local.TemplateAdaptationResult;
import com.cloudforge.core.local.TemplateAdapter;
import com.cloudforge.core.local.TemplateAdapterSupport;
import com.cloudforge.core.manager.ManagerEnvKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces the explicitly-audited template deployed to LocalStack.
 *
 * <p>The input is always the canonical AWS template. LocalStack ALB supports forward/redirect/
 * fixed-response (not authenticate-*), so auth actions are stripped while ECS forward is kept.
 * On Base-tier expectations, EFS and AWS Backup resources are adapted away (bind mounts /
 * removed). Application Auto Scaling is left in place. Canonical AWS templates are unchanged.</p>
 */
public final class LocalStackTemplateAdapter implements TemplateAdapter {
    public static final LocalStackTemplateAdapter INSTANCE = new LocalStackTemplateAdapter();

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_VOLUME_ROOT = ".localstack-volumes";
    public static final String OUTPUT_LOCAL_URL = "LocalStackLocalUrl";
    public static final String OUTPUT_APPLICATION_URL = "LocalStackApplicationUrl";
    public static final String OUTPUT_ELB_HOSTNAME_URL = "LocalStackElbHostnameUrl";
    public static final String OUTPUT_AUTHENTICATED_URL = "LocalStackAuthenticatedUrl";
    public static final String OUTPUT_HOST_VOLUME_PREFIX = "LocalStackHostVolume";
    // CloudForge Manager's own application-oidc env vars — built server-side (Application/
    // CognitoAuthenticationFactory), unlike Jenkins/GitLab's JAVA_OPTS/GITLAB_OMNIBUS_CONFIG
    // which bake OIDC URLs into a shell command string — so they need their own name-matched
    // rewrite pass rather than falling out of the Command-node walk above.
    private static final Set<String> MANAGER_OIDC_ENDPOINT_ENV_NAMES = Set.of(
        ManagerEnvKeys.OIDC_ISSUER, ManagerEnvKeys.OIDC_AUTHORIZATION_ENDPOINT,
        ManagerEnvKeys.OIDC_TOKEN_ENDPOINT, ManagerEnvKeys.OIDC_USERINFO_ENDPOINT,
        ManagerEnvKeys.OIDC_JWKS_URI);
    private static final Pattern NAMED_LOCAL_CALLBACK_URL = Pattern.compile(
        "https?://([a-zA-Z0-9-]+\\.cloudforge\\.localhost)(?::\\d+)?(/[^'\\\"\\s]*)?");
    private static final Pattern NAMED_LOCAL_HOST_URL = Pattern.compile(
        "https?://[a-zA-Z0-9-]+\\.cloudforge\\.localhost(?::\\d+)?");
    private static final Pattern LOCALSTACK_ELB_HOST_URL = Pattern.compile(
        "https?://[a-zA-Z0-9-]+\\.elb\\.localhost\\.localstack\\.cloud(?::\\d+)?");

    private LocalStackTemplateAdapter() {
    }

    @Override
    public TemplateAdaptationResult adapt(ObjectNode canonicalTemplate, String stackName) {
        return adapt(canonicalTemplate, stackName, LocalStackCapabilityProbe.probeDefault());
    }

    /**
     * Adapts using an explicit capability snapshot (tier-aware EFS/Backup handling).
     */
    public TemplateAdaptationResult adapt(
            ObjectNode canonicalTemplate,
            String stackName,
            com.cloudforge.core.local.LocalStackCapabilitySnapshot snapshot) {
        return adaptInternal(canonicalTemplate, stackName, snapshot);
    }

    @Override
    public boolean requiresLocalAuthRuntime(TemplateAdaptationResult result) {
        return false;
    }

    @Override
    public String applicationUrl(TemplateAdaptationResult result) {
        return result.outputValue(OUTPUT_APPLICATION_URL).orElse(null);
    }

    public static TemplateAdaptationResult adapt(ObjectNode canonicalTemplate) {
        return INSTANCE.adapt(canonicalTemplate, "local");
    }

    private static TemplateAdaptationResult adaptInternal(
            ObjectNode canonicalTemplate,
            String stackName) {
        return adaptInternal(canonicalTemplate, stackName, LocalStackCapabilityProbe.probeDefault());
    }

    private static TemplateAdaptationResult adaptInternal(
            ObjectNode canonicalTemplate,
            String stackName,
            com.cloudforge.core.local.LocalStackCapabilitySnapshot snapshot) {
        boolean authenticationEnabled = canonicalTemplate.findValuesAsText("Type").stream()
            .anyMatch(type ->
                "authenticate-oidc".equals(type) || "authenticate-cognito".equals(type));
        ObjectNode local = canonicalTemplate.deepCopy();
        List<TemplateAdaptation> adaptations = new ArrayList<>();
        // Keep Application Auto Scaling (LocalStack Base supports it).
        // ALB hostname URLs break browser assets (LocalStack returns 403 with Sec-Fetch);
        // redirect ALB actions to the path-style browser entry point after port fixes.
        replaceUnsupportedEfsWithHostBindMounts(local, adaptations, stackName, snapshot);
        removeUnsupportedBackupResources(local, adaptations, snapshot);
        removeAsgTargetGroupAttachments(local, adaptations);
        inlineUnsupportedSecurityGroupIngress(local, adaptations);
        resolveCdkBootstrapParameters(local, adaptations);
        rewriteDummyAvailabilityZones(local, adaptations);
        suffixLogGroupNamesForLocalStack(local, stackName, adaptations);
        JsonNode resources = local.path("Resources");

        if (resources.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = resources.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                ObjectNode resource = asObject(entry.getValue());
                if (resource == null) {
                    continue;
                }
                String type = resource.path("Type").asText();
                ObjectNode properties = asObject(resource.get("Properties"));
                if (properties == null) {
                    continue;
                }

                if ("AWS::ElasticLoadBalancingV2::Listener".equals(type)) {
                    removeUnsupportedAuthActions(
                        properties.get("DefaultActions"),
                        "Resources." + entry.getKey() + ".Properties.DefaultActions",
                        adaptations
                    );
                } else if ("AWS::ElasticLoadBalancingV2::ListenerRule".equals(type)) {
                    removeUnsupportedAuthActions(
                        properties.get("Actions"),
                        "Resources." + entry.getKey() + ".Properties.Actions",
                        adaptations
                    );
                }
            }
        }

        rewriteAlbRedirectsForLocalStackGateway(local, adaptations);
        redirectAlbToLocalhostPort(local, adaptations);
        rewriteApplicationOidcForLocalStack(local, adaptations);
        removeUnsupportedLogRetentionCustomResources(local, adaptations);
        removeUnsupportedS3AutoDeleteObjectsCustomResources(local, adaptations);
        removeUnsupportedCustomAwsResources(local, adaptations);
        removeRoute53QueryLogging(local, adaptations);
        String edgeHostname = removeRoute53RecordSets(local, adaptations);
        LocalStackCognitoSecretReconciler.removeCdkCognitoCustomResources(local, adaptations);
        addLocalUrlOutput(local, adaptations, authenticationEnabled);
        configureManagerForLocalStack(local, adaptations);
        rewriteDatabaseTaskEndpointsForLocalStack(local, adaptations);
        resolveAlbLocalName(local, adaptations)
            .ifPresent(name -> injectLocalStackPathPrefixForEcsTasks(local, name, edgeHostname, adaptations));
        return new TemplateAdaptationResult(local, List.copyOf(adaptations));
    }

    /**
     * Routes task database connections to listeners reachable from the emulator
     * Docker network. LocalStack models RDS endpoints in CloudFormation, but its
     * native MySQL listener is exposed inside the LocalStack container instead.
     */
    private static void rewriteDatabaseTaskEndpointsForLocalStack(
            ObjectNode template, List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) return;
        Map<String, LocalDatabaseEndpoint> databases = new LinkedHashMap<>();
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null || !"AWS::RDS::DBInstance".equals(resource.path("Type").asText())) {
                return;
            }
            String engine = resource.path("Properties").path("Engine").asText("");
            if (engine.startsWith("postgres")) {
                databases.put(entry.getKey(), new LocalDatabaseEndpoint(
                    LocalStackPostgresCompanion.hostname(), LocalStackPostgresCompanion.PORT));
            } else if (engine.startsWith("mysql") || engine.startsWith("mariadb")) {
                // 4510, not 4512 — an RDS_MYSQL_DOCKER=1 instance's Endpoint.Port matches the
                // first port in LocalStack's default EXTERNAL_SERVICE_PORTS_START..END range
                // (4510-4559), where its dynamic-port-per-resource allocator starts. This is a
                // guess: the adapter runs before the RDS instance and its allocated port exist.
                // LocalStackMysqlPortReconciler corrects it after deploy when another MySQL/MariaDB
                // instance in the same LocalStack session already holds this port.
                databases.put(entry.getKey(), new LocalDatabaseEndpoint("cfc-localstack", 4510));
            }
        });
        if (databases.isEmpty()) return;
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null || !"AWS::ECS::TaskDefinition".equals(resource.path("Type").asText())) return;
            JsonNode containers = resource.path("Properties").path("ContainerDefinitions");
            if (!(containers instanceof ArrayNode array)) return;
            for (int i = 0; i < array.size(); i++) {
                ObjectNode container = asObject(array.get(i));
                if (container == null || !(container.get("Environment") instanceof ArrayNode environment)) continue;
                for (int j = 0; j < environment.size(); j++) {
                    ObjectNode variable = asObject(environment.get(j));
                    JsonNode value = variable == null ? null : variable.get("Value");
                    if (value == null) continue;
                    String bareReplacement = resolveDatabaseGetAtt(value, databases);
                    if (bareReplacement != null) {
                        variable.put("Value", bareReplacement);
                        adaptations.add(new TemplateAdaptation(
                            "Resources." + entry.getKey() + ".Properties.ContainerDefinitions[" + i
                                + "].Environment[" + j + "].Value",
                            "LocalStack routes direct RDS task traffic to a reachable emulator listener",
                            value.deepCopy()));
                        continue;
                    }
                    // A GetAtt doesn't have to be the WHOLE value — an app spec building a combined
                    // "host:port" env var (e.g. WORDPRESS_DB_HOST, from Java string concatenation on
                    // CDK tokens) synthesizes as an Fn::Join wrapping a nested Fn::GetAtt alongside a
                    // literal like ":3306", not a bare Fn::GetAtt. Without this, WORDPRESS_DB_HOST
                    // would keep LocalStack's RDS-emulation hostname — unreachable from inside the
                    // ECS task's own Docker network — even when the plain DB_HOST env var next to it
                    // is rewritten, and WordPress reports "Database Error".
                    //
                    // The literal that follows the rewritten host (":3306" here) needs handling too,
                    // not just the GetAtt itself — it's the RDS port the app spec assumed at synth
                    // time (`host + ":" + port` with a plain int, never a token, so it was never a
                    // GetAtt for this method to see in the first place), not LocalStack's actual
                    // emulator listener port. Rewriting only the host would connect to the right
                    // host on the wrong port — same "Database Error" symptom, harder to spot
                    // since DB_HOST/DB_PORT (separate, bare-GetAtt env vars) look correctly rewritten.
                    if (value.get("Fn::Join") instanceof ArrayNode join && join.size() == 2
                            && join.get(1) instanceof ArrayNode parts) {
                        boolean rewrote = false;
                        for (int k = 0; k < parts.size(); k++) {
                            LocalDatabaseEndpoint endpoint = matchAddressGetAtt(parts.get(k), databases);
                            if (endpoint == null) {
                                continue;
                            }
                            parts.set(k, com.fasterxml.jackson.databind.node.TextNode.valueOf(endpoint.host()));
                            rewrote = true;
                            if (k + 1 < parts.size() && parts.get(k + 1).isTextual()
                                    && parts.get(k + 1).asText().matches(":\\d+")) {
                                parts.set(k + 1, com.fasterxml.jackson.databind.node.TextNode.valueOf(
                                    ":" + endpoint.port()));
                            }
                        }
                        if (rewrote) {
                            adaptations.add(new TemplateAdaptation(
                                "Resources." + entry.getKey() + ".Properties.ContainerDefinitions[" + i
                                    + "].Environment[" + j + "].Value",
                                "LocalStack routes direct RDS task traffic to a reachable emulator "
                                    + "listener (Fn::GetAtt embedded in an Fn::Join, e.g. a combined "
                                    + "\"host:port\" env var)",
                                value.deepCopy()));
                        }
                        continue;
                    }
                    // Some app specs (e.g. PhpBBApplicationSpec's PHPBB_DB_PORT, from
                    // DatabaseSpec.DatabaseConnection.port() — a plain int, never a CDK token
                    // unlike endpoint()) never produce a GetAtt at all: the port is a bare
                    // standard-port literal ("3306") from the moment the template is
                    // synthesized. Same underlying bug as the Fn::Join case above (assumed
                    // engine-standard port instead of LocalStack's actual emulator listener
                    // port) but with no GetAtt shape to key off — match by env var name instead,
                    // and only when there's exactly one candidate database, to avoid guessing
                    // which of several DB_PORT-shaped vars a value belongs to.
                    String name = variable.path("Name").asText("");
                    if (databases.size() == 1 && value.isTextual()
                            && (name.endsWith("_DB_PORT") || "DB_PORT".equals(name))) {
                        String literalPort = value.asText();
                        boolean isStandardPort = "3306".equals(literalPort) || "3307".equals(literalPort)
                            || "5432".equals(literalPort);
                        if (isStandardPort) {
                            LocalDatabaseEndpoint endpoint = databases.values().iterator().next();
                            String replacement = String.valueOf(endpoint.port());
                            if (!literalPort.equals(replacement)) {
                                variable.put("Value", replacement);
                                adaptations.add(new TemplateAdaptation(
                                    "Resources." + entry.getKey() + ".Properties.ContainerDefinitions[" + i
                                        + "].Environment[" + j + "].Value",
                                    "LocalStack routes direct RDS task traffic to a reachable emulator "
                                        + "listener (bare engine-standard-port literal, not a CDK token)",
                                    value.deepCopy()));
                            }
                        }
                    }
                }
            }
        });
    }

    /** {@code {"Fn::GetAtt": [dbLogicalId, "Endpoint.Address"|"Endpoint.Port"]}} → the
     *  emulator-reachable literal, or {@code null} if {@code value} isn't a GetAtt referencing a
     *  known database endpoint (including when it's {@code null} outright — callers pass array
     *  elements here too, which may not be objects at all). */
    private static String resolveDatabaseGetAtt(JsonNode value, Map<String, LocalDatabaseEndpoint> databases) {
        JsonNode getAtt = value == null ? null : value.path("Fn::GetAtt");
        if (!(getAtt instanceof ArrayNode parts) || parts.size() < 2
                || !databases.containsKey(parts.get(0).asText())) {
            return null;
        }
        LocalDatabaseEndpoint endpoint = databases.get(parts.get(0).asText());
        String attribute = parts.get(1).asText();
        if ("Endpoint.Address".equals(attribute)) return endpoint.host();
        if ("Endpoint.Port".equals(attribute)) return String.valueOf(endpoint.port());
        return null;
    }

    /** Same {@code Fn::GetAtt} shape as {@link #resolveDatabaseGetAtt}, but only matches {@code
     *  Endpoint.Address} and returns the {@link LocalDatabaseEndpoint} itself (not just the host
     *  string) — the Fn::Join case needs the endpoint's port too, to also fix up a literal
     *  ":<port>" element immediately following the rewritten host. */
    private static LocalDatabaseEndpoint matchAddressGetAtt(JsonNode value, Map<String, LocalDatabaseEndpoint> databases) {
        JsonNode getAtt = value == null ? null : value.path("Fn::GetAtt");
        if (!(getAtt instanceof ArrayNode parts) || parts.size() < 2
                || !"Endpoint.Address".equals(parts.get(1).asText())
                || !databases.containsKey(parts.get(0).asText())) {
            return null;
        }
        return databases.get(parts.get(0).asText());
    }

    private record LocalDatabaseEndpoint(String host, int port) { }

    /**
     * Manager on LocalStack must use {@code authMode=none} and reach the emulator API
     * from inside ECS ({@code host.docker.internal:4566} on Docker Desktop, overridable).
     */
    private static void configureManagerForLocalStack(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        String endpoint = resolveManagerLocalStackEndpoint();
        Set<String> managerTaskDefinitions = new LinkedHashSet<>();
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null
                    || !"AWS::ECS::TaskDefinition".equals(resource.path("Type").asText())) {
                return;
            }
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null || !(properties.get("ContainerDefinitions") instanceof ArrayNode containers)) {
                return;
            }
            for (int i = 0; i < containers.size(); i++) {
                ObjectNode container = asObject(containers.get(i));
                if (container == null || !isManagerContainer(container)) {
                    continue;
                }
                managerTaskDefinitions.add(entry.getKey());
                ArrayNode environment = ensureEnvironmentArray(container);
                for (int j = 0; j < environment.size(); j++) {
                    ObjectNode env = asObject(environment.get(j));
                    if (env != null && "CFC_MANAGER_AUTH_MODE".equals(env.path("Name").asText())
                            && "alb-oidc".equalsIgnoreCase(env.path("Value").asText(""))) {
                        adaptations.add(new TemplateAdaptation(
                            "Resources." + entry.getKey() + ".Properties.ContainerDefinitions["
                                + i + "].Environment[" + j + "].Value",
                            "LocalStack strips ALB Cognito; Manager uses local setup (authMode=none)",
                            env.get("Value").deepCopy()
                        ));
                        env.put("Value", "none");
                    }
                }
                upsertEnvironment(environment, "CFC_MANAGER_TARGET", "localstack");
                // LocalStack models RDS topology but does not expose a database listener to ECS tasks.
                // Keep Manager on its supported local H2 store while still deploying the RDS
                // primary and replica resources for topology validation.
                upsertEnvironment(environment, "CFC_MANAGER_DB_MODE", "embedded-h2");
                upsertEnvironment(environment, "LOCALSTACK_ENDPOINT", endpoint);
                upsertEnvironment(environment, "AWS_ENDPOINT_URL", endpoint);
                upsertEnvironment(environment, "LOCALSTACK_VOLUME_ROOT", managerVolumeRoot());
                // cloudforge-synth-service sidecar (see ManagerEnvKeys.SYNTH_SERVICE_URL's own
                // javadoc for why this is needed only on LocalStack/MiniStack): the sidecar
                // publishes its port to the host (see ApplicationSpec.SidecarContainer's own
                // wiring in ContainerFactory), so host.docker.internal:<port> reaches it from
                // Manager's own, separate container namespace under this target's ECS emulation.
                upsertEnvironment(environment, com.cloudforge.core.manager.ManagerEnvKeys.SYNTH_SERVICE_URL,
                    "http://host.docker.internal:"
                        + com.cloudforge.core.manager.ManagerEnvKeys.SYNTH_SERVICE_DEFAULT_PORT);
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties.ContainerDefinitions["
                        + i + "].Environment",
                    "Wire Manager to LocalStack CFN endpoint " + endpoint,
                    com.fasterxml.jackson.databind.node.NullNode.instance
                ));
                grantDockerSocketAccess(properties, container, entry.getKey(), i, adaptations);
            }
        });
        configureManagerReplacementStrategy(resources, managerTaskDefinitions, adaptations);
    }

    /**
     * Manager's own {@code deploy:create}/{@code deploy:catalog} handling (via {@code
     * LocalStackDeployer}) needs to shell out to {@code docker} directly for target-specific
     * emulation it owns (e.g. {@link LocalStackPostgresCompanion}'s companion Postgres container).
     * That only works when Manager's own container has the Docker CLI on {@code PATH} — see the
     * runtime image's {@code Dockerfile} — AND access to the host's Docker daemon, which this bind
     * mount grants. Deliberately Manager-only and localstack-only: no other application container
     * gets this (host-root-equivalent) access, and the {@code aws} target's task definition is
     * never touched by this adapter at all, so real Fargate — which has no host Docker socket to
     * mount in the first place — never sees it either.
     */
    private static void grantDockerSocketAccess(
            ObjectNode properties,
            ObjectNode container,
            String logicalId,
            int containerIndex,
            List<TemplateAdaptation> adaptations) {
        String volumeName = "cfc-docker-socket";
        ArrayNode volumes = properties.get("Volumes") instanceof ArrayNode existing
            ? existing : properties.putArray("Volumes");
        boolean volumeExists = false;
        for (JsonNode volume : volumes) {
            if (volumeName.equals(volume.path("Name").asText())) {
                volumeExists = true;
                break;
            }
        }
        if (!volumeExists) {
            ObjectNode volume = volumes.addObject();
            volume.put("Name", volumeName);
            volume.putObject("Host").put("SourcePath",
                com.cloudforge.core.local.LocalEmulatorPaths.dockerSocketHostPath());
            adaptations.add(new TemplateAdaptation(
                "Resources." + logicalId + ".Properties.Volumes",
                "Manager's own LocalStack deploy handling needs Docker CLI access to manage "
                    + "target-owned emulator containers (e.g. the Postgres companion for "
                    + "RDS-backed apps) — see DockerEmulatorSupport",
                com.fasterxml.jackson.databind.node.NullNode.instance
            ));
        }
        JsonNode mountPointsNode = container.get("MountPoints");
        ArrayNode mountPoints = mountPointsNode instanceof ArrayNode existing
            ? existing : container.putArray("MountPoints");
        boolean mountExists = false;
        for (JsonNode mountPoint : mountPoints) {
            if (volumeName.equals(mountPoint.path("SourceVolume").asText())) {
                mountExists = true;
                break;
            }
        }
        if (!mountExists) {
            ObjectNode mountPoint = mountPoints.addObject();
            mountPoint.put("SourceVolume", volumeName);
            mountPoint.put("ContainerPath", "/var/run/docker.sock");
            mountPoint.put("ReadOnly", false);
            adaptations.add(new TemplateAdaptation(
                "Resources." + logicalId + ".Properties.ContainerDefinitions["
                    + containerIndex + "].MountPoints",
                "Mount the host Docker socket into Manager's own container for local target "
                    + "emulator management",
                com.fasterxml.jackson.databind.node.NullNode.instance
            ));
        }
    }

    /**
     * H2 is a single-writer store, so LocalStack must stop a Manager task before
     * starting its replacement. AWS keeps the canonical rolling-deployment policy.
     */
    private static void configureManagerReplacementStrategy(
            ObjectNode resources,
            Set<String> managerTaskDefinitions,
            List<TemplateAdaptation> adaptations) {
        if (managerTaskDefinitions.isEmpty()) {
            return;
        }
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null || !"AWS::ECS::Service".equals(resource.path("Type").asText())) {
                return;
            }
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null || !referencesAny(properties.get("TaskDefinition"), managerTaskDefinitions)) {
                return;
            }
            ObjectNode deployment = properties.withObject("DeploymentConfiguration");
            deployment.put("MaximumPercent", 100);
            deployment.put("MinimumHealthyPercent", 0);
            adaptations.add(new TemplateAdaptation(
                "Resources." + entry.getKey() + ".Properties.DeploymentConfiguration",
                "LocalStack Manager uses single-task replacement because embedded H2 has one writer",
                com.fasterxml.jackson.databind.node.NullNode.instance));
        });
    }

    private static boolean referencesAny(JsonNode value, Set<String> logicalIds) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return logicalIds.contains(value.asText());
        }
        return logicalIds.contains(value.path("Ref").asText());
    }

    static String resolveManagerLocalStackEndpoint() {
        return resolveContainerLocalStackEndpoint();
    }

    /**
     * ECS tasks reach LocalStack via the Docker host gateway, not {@code localhost}.
     */
    static String resolveContainerLocalStackEndpoint() {
        String override = System.getenv("CFC_LOCALSTACK_CONTAINER_ENDPOINT");
        if (override == null || override.isBlank()) {
            override = System.getenv("CFC_LOCALSTACK_MANAGER_ENDPOINT");
        }
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        // ECS tasks LocalStack starts on Docker Desktop reach the host gateway.
        return "http://host.docker.internal:4566";
    }

    /**
     * Issuer claim LocalStack Cognito embeds in ID tokens ({@code localhost.localstack.cloud}).
     */
    static String resolveCognitoIssuerBase() {
        String override = System.getenv("CFC_LOCALSTACK_COGNITO_ISSUER_BASE");
        if (override != null && !override.isBlank()) {
            return override.trim().replaceAll("/$", "");
        }
        return "http://localhost.localstack.cloud:4566";
    }

    /**
     * LocalStack serves TLS on the gateway port ({@code 4566} by default), not {@code 443}.
     * Canonical templates redirect HTTP→HTTPS on port 443; rewrite to the gateway port so
     * browsers following {@link #OUTPUT_APPLICATION_URL} reach a listening endpoint.
     */
    static int resolveGatewayPort() {
        for (String key : List.of("CFC_LOCALSTACK_GATEWAY_PORT", "LOCALSTACK_GATEWAY_PORT")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the next candidate key, then the endpoint-derived port below
                }
            }
        }
        String endpoint = System.getenv("LOCALSTACK_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = System.getenv("AWS_ENDPOINT_URL");
        }
        if (endpoint != null && !endpoint.isBlank()) {
            try {
                var uri = java.net.URI.create(endpoint.trim());
                if (uri.getPort() > 0) {
                    return uri.getPort();
                }
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return 4566;
    }

    private static void rewriteAlbRedirectsForLocalStackGateway(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        int gatewayPort = resolveGatewayPort();
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null) {
                return;
            }
            String type = resource.path("Type").asText();
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null) {
                return;
            }
            if ("AWS::ElasticLoadBalancingV2::Listener".equals(type)) {
                rewriteRedirectActions(
                    properties.get("DefaultActions"),
                    "Resources." + entry.getKey() + ".Properties.DefaultActions",
                    gatewayPort,
                    adaptations);
            } else if ("AWS::ElasticLoadBalancingV2::ListenerRule".equals(type)) {
                rewriteRedirectActions(
                    properties.get("Actions"),
                    "Resources." + entry.getKey() + ".Properties.Actions",
                    gatewayPort,
                    adaptations);
            }
        });
    }

    private static void rewriteRedirectActions(
            JsonNode actionsNode,
            String path,
            int gatewayPort,
            List<TemplateAdaptation> adaptations) {
        if (!(actionsNode instanceof ArrayNode actions)) {
            return;
        }
        for (int index = 0; index < actions.size(); index++) {
            ObjectNode action = asObject(actions.get(index));
            if (action == null || !"redirect".equals(action.path("Type").asText())) {
                continue;
            }
            ObjectNode config = asObject(action.get("RedirectConfig"));
            if (config == null) {
                continue;
            }
            String port = config.path("Port").asText("");
            if (!"443".equals(port) && config.path("Port").asInt(0) != 443) {
                continue;
            }
            adaptations.add(new TemplateAdaptation(
                path + "[" + index + "].RedirectConfig.Port",
                "LocalStack gateway serves HTTPS on port " + gatewayPort + ", not 443",
                config.get("Port").deepCopy()
            ));
            config.put("Port", Integer.toString(gatewayPort));
            if (!config.has("Protocol") || config.path("Protocol").asText("").isBlank()) {
                config.put("Protocol", "HTTPS");
            }
        }
    }

    /**
     * Host ports the local emulator stack itself always occupies — {@code cfc-emulator-edge}
     * (nginx) on 80, LocalStack's own gateway on 4566, StackPort on 8888. An application whose
     * container listens on one of these (WordPress's default image listens on 80, for instance)
     * can never get that host port: something else already holds it before the app's task
     * starts. Pinning {@code HostPort} to it anyway makes Docker/LocalStack fall back to a random
     * host port while every URL this adapter generates points at the reserved one, which serves
     * something else (e.g. {@code http://localhost:80/} is {@code cfc-emulator-edge}'s own root,
     * "route not found").
     */
    private static final Set<Integer> RESERVED_LOCAL_HOST_PORTS = Set.of(
        LocalEmulatorDefaults.EMULATOR_EDGE_HOST_PORT,
        LocalEmulatorDefaults.GATEWAY_PORT,
        LocalEmulatorDefaults.STACKPORT_HOST_PORT);

    /**
     * LocalStack ELB hostnames break browser assets (403 with Sec-Fetch). Pin ECS
     * {@code HostPort} to {@code ContainerPort} and redirect ALB forward/TLS actions
     * to {@code http://localhost:{port}} like MiniStack — except when that port is one of the
     * emulator stack's own reserved ports (see {@link #RESERVED_LOCAL_HOST_PORTS}), where pinning
     * would fail; leave the ALB forward action alone in that case so the (still working,
     * if asset-loading-limited) ELB-hostname URL remains the reachable one instead of a broken
     * localhost:port promise.
     */
    private static void redirectAlbToLocalhostPort(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        pinEcsHostPorts(resources, adaptations);
        int applicationPort = findEcsApplicationPort(resources);
        if (applicationPort == 0 || RESERVED_LOCAL_HOST_PORTS.contains(applicationPort)) {
            return;
        }
        String localPort = Integer.toString(applicationPort);
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null) {
                return;
            }
            String type = resource.path("Type").asText();
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null) {
                return;
            }
            String actionsPath = null;
            JsonNode actionsNode = null;
            if ("AWS::ElasticLoadBalancingV2::Listener".equals(type)) {
                actionsPath = "Resources." + entry.getKey() + ".Properties.DefaultActions";
                actionsNode = properties.get("DefaultActions");
            } else if ("AWS::ElasticLoadBalancingV2::ListenerRule".equals(type)) {
                actionsPath = "Resources." + entry.getKey() + ".Properties.Actions";
                actionsNode = properties.get("Actions");
            }
            if (actionsNode instanceof ArrayNode actions) {
                replaceAlbActionsWithLocalhostRedirect(actions, actionsPath, localPort, adaptations);
            }
        });
    }

    private static void pinEcsHostPorts(ObjectNode resources, List<TemplateAdaptation> adaptations) {
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null || !"AWS::ECS::TaskDefinition".equals(resource.path("Type").asText())) {
                return;
            }
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null || !(properties.get("ContainerDefinitions") instanceof ArrayNode containers)) {
                return;
            }
            for (int i = 0; i < containers.size(); i++) {
                ObjectNode container = asObject(containers.get(i));
                if (container == null || !(container.get("PortMappings") instanceof ArrayNode mappings)) {
                    continue;
                }
                for (int j = 0; j < mappings.size(); j++) {
                    ObjectNode mapping = asObject(mappings.get(j));
                    if (mapping == null) {
                        continue;
                    }
                    int containerPort = mapping.path("ContainerPort").asInt(0);
                    if (containerPort <= 0 || mapping.has("HostPort")
                            || RESERVED_LOCAL_HOST_PORTS.contains(containerPort)) {
                        continue;
                    }
                    mapping.put("HostPort", containerPort);
                    adaptations.add(new TemplateAdaptation(
                        "Resources." + entry.getKey() + ".Properties.ContainerDefinitions["
                            + i + "].PortMappings[" + j + "].HostPort",
                        "Pin LocalStack ECS host port to container port for localhost ALB redirect",
                        com.fasterxml.jackson.databind.node.NullNode.instance));
                }
            }
        });
    }

    private static int findEcsApplicationPort(ObjectNode resources) {
        for (JsonNode resource : resources) {
            if ("AWS::ECS::Service".equals(resource.path("Type").asText())) {
                int port = resource.path("Properties")
                    .path("LoadBalancers").path(0).path("ContainerPort").asInt();
                if (port > 0) {
                    return port;
                }
            }
        }
        for (JsonNode resource : resources) {
            if (!"AWS::ECS::TaskDefinition".equals(resource.path("Type").asText())) {
                continue;
            }
            JsonNode containers = resource.path("Properties").path("ContainerDefinitions");
            if (!(containers instanceof ArrayNode array)) {
                continue;
            }
            for (JsonNode container : array) {
                JsonNode mappings = container.path("PortMappings");
                if (!(mappings instanceof ArrayNode ports)) {
                    continue;
                }
                for (JsonNode mapping : ports) {
                    int port = mapping.path("ContainerPort").asInt(0);
                    if (port > 0) {
                        return port;
                    }
                }
            }
        }
        return 0;
    }

    private static void replaceAlbActionsWithLocalhostRedirect(
            ArrayNode actions,
            String path,
            String localPort,
            List<TemplateAdaptation> adaptations) {
        for (int index = 0; index < actions.size(); index++) {
            ObjectNode action = asObject(actions.get(index));
            if (action == null) {
                continue;
            }
            String type = action.path("Type").asText();
            boolean forward = "forward".equals(type);
            boolean redirect = "redirect".equals(type);
            if (!forward && !redirect) {
                continue;
            }
            ObjectNode replacement = MAPPER.createObjectNode();
            replacement.put("Type", "redirect");
            if (action.has("Order")) {
                replacement.set("Order", action.get("Order"));
            }
            ObjectNode config = replacement.putObject("RedirectConfig");
            config.put("Protocol", "HTTP");
            config.put("Host", "localhost");
            config.put("Port", localPort);
            config.put("Path", "/");
            config.put("StatusCode", "HTTP_302");
            adaptations.add(new TemplateAdaptation(
                path + "[" + index + "]",
                forward
                    ? "LocalStack ELB hostname breaks browser assets; redirect to localhost ECS port"
                    : "LocalStack ALB TLS redirect targets localhost ECS port",
                action.deepCopy()));
            actions.set(index, replacement);
        }
    }

    private static boolean isManagerContainer(ObjectNode container) {
        if (container.has("Environment") && container.get("Environment").isArray()) {
            for (JsonNode env : container.get("Environment")) {
                if ("CFC_MANAGER_PORT".equals(env.path("Name").asText())
                        || "CFC_MANAGER_AUTH_MODE".equals(env.path("Name").asText())) {
                    return true;
                }
            }
        }
        String image = container.path("Image").asText("").toLowerCase(Locale.ROOT);
        return image.contains("cloudforge-manager");
    }

    /**
     * Removes target group attachments from Auto Scaling groups: LocalStack's Auto Scaling provider
     * does not resolve ELBv2 target groups in {@code CreateAutoScalingGroup}. The health check falls
     * back to EC2, since no target group remains to check against.
     */
    private static void removeAsgTargetGroupAttachments(
            ObjectNode template, List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        resources.properties().forEach(entry -> {
            if (!"AWS::AutoScaling::AutoScalingGroup".equals(entry.getValue().path("Type").asText())) {
                return;
            }
            ObjectNode properties = asObject(entry.getValue().get("Properties"));
            if (properties == null || !properties.has("TargetGroupARNs")) {
                return;
            }
            adaptations.add(new TemplateAdaptation(
                "Resources." + entry.getKey() + ".Properties.TargetGroupARNs",
                "LocalStack Auto Scaling cannot resolve ELBv2 target groups on CreateAutoScalingGroup",
                properties.get("TargetGroupARNs").deepCopy()
            ));
            properties.remove("TargetGroupARNs");
            if ("ELB".equals(properties.path("HealthCheckType").asText())) {
                properties.put("HealthCheckType", "EC2");
            }
        });
    }

    private static void removeUnsupportedBackupResources(
            ObjectNode template,
            List<TemplateAdaptation> adaptations,
            com.cloudforge.core.local.LocalStackCapabilitySnapshot snapshot) {
        if (snapshot.keepBackupResources()) {
            return;
        }
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }

        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            String type = entry.getValue().path("Type").asText();
            if (type != null && type.startsWith("AWS::Backup::")) {
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey(),
                    "LocalStack Base does not include AWS Backup; Ultimate may keep these resources",
                    entry.getValue().deepCopy()
                ));
                remove.add(entry.getKey());
            }
        });
        remove.forEach(resources::remove);
    }

    /**
     * CDK {@code Custom::LogRetention} providers often hang in LocalStack CloudFormation
     * (provider Lambda may create, but the custom resource never leaves CREATE_IN_PROGRESS).
     * Log groups still exist; retention is a non-goal for the emulator.
     */
    private static void removeUnsupportedLogRetentionCustomResources(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            String type = entry.getValue().path("Type").asText();
            if ("Custom::LogRetention".equals(type)) {
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey(),
                    "LocalStack CloudFormation hangs on CDK Custom::LogRetention; retention skipped",
                    entry.getValue().deepCopy()
                ));
                remove.add(entry.getKey());
            }
        });
        if (remove.isEmpty()) {
            return;
        }
        remove.forEach(resources::remove);
        cleanupDependsOnReferences(resources, remove);
    }

    /**
     * CDK {@code Custom::S3AutoDeleteObjects} (from {@code Bucket.autoDeleteObjects(true)}) hangs
     * the same way {@code Custom::LogRetention} does -- the provider Lambda never leaves
     * CREATE_IN_PROGRESS on LocalStack. Bucket contents just won't be auto-purged on stack
     * deletion locally, which doesn't matter for an emulator.
     */
    private static void removeUnsupportedS3AutoDeleteObjectsCustomResources(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            String type = entry.getValue().path("Type").asText();
            if ("Custom::S3AutoDeleteObjects".equals(type)) {
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey(),
                    "LocalStack CloudFormation hangs on CDK Custom::S3AutoDeleteObjects; bucket auto-purge skipped",
                    entry.getValue().deepCopy()
                ));
                remove.add(entry.getKey());
            }
        });
        if (remove.isEmpty()) {
            return;
        }
        remove.forEach(resources::remove);
        cleanupDependsOnReferences(resources, remove);
    }

    /**
     * CDK {@code AwsCustomResource} calls perform deployment-time side effects such as SSM
     * metadata writes and starting an AWS Config recorder. They are not required to run an
     * application locally, and LocalStack's CloudFormation provider can leave them permanently
     * in {@code CREATE_IN_PROGRESS}. Cognito client-secret sync is performed after the stack
     * completes by {@link LocalStackCognitoSecretReconciler}.
     */
    private static void removeUnsupportedCustomAwsResources(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null || !"Custom::AWS".equals(resource.path("Type").asText())) {
                return;
            }
            adaptations.add(new TemplateAdaptation(
                "Resources." + entry.getKey(),
                "LocalStack CloudFormation hangs on CDK Custom::AWS side effects; local-only post-deploy reconciliation is used where needed",
                resource.deepCopy()
            ));
            remove.add(entry.getKey());
        });
        if (remove.isEmpty()) {
            return;
        }
        remove.forEach(resources::remove);
        cleanupDependsOnReferences(resources, remove);
    }

    /**
     * LocalStack keeps Route 53 query-log groups after a CloudFormation rollback.
     * Query logging is observability-only for a local emulator, so omit the complete
     * sidecar rather than leaving deterministic log-group names that block redeploys.
     */
    private static void removeRoute53QueryLogging(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null) {
                return;
            }
            if ("AWS::Route53::HostedZone".equals(resource.path("Type").asText())) {
                ObjectNode properties = asObject(resource.get("Properties"));
                if (properties != null && properties.remove("QueryLoggingConfig") != null) {
                    adaptations.add(new TemplateAdaptation(
                        "Resources." + entry.getKey() + ".Properties.QueryLoggingConfig",
                        "LocalStack omits Route 53 query logging to keep redeploys idempotent",
                        com.fasterxml.jackson.databind.node.NullNode.instance));
                }
            }
            if (entry.getKey().contains("Route53QueryLogs")) {
                remove.add(entry.getKey());
            }
        });
        if (!remove.isEmpty()) {
            remove.forEach(resources::remove);
            cleanupDependsOnReferences(resources, remove);
        }
    }

    /**
     * The emulator edge resolves {@code *.cloudforge.localhost} directly to the
     * local application task. Route 53 aliases are therefore neither used nor
     * valid in LocalStack: CloudForge's placeholder hosted-zone ID is rejected
     * by the LocalStack Route 53 provider during a stack update.
     */
    /**
     * Strips {@code AWS::Route53::RecordSet} resources (LocalStack has no real DNS to alias
     * against — the emulator edge owns {@code *.cloudforge.localhost} routing instead), but
     * first captures the subdomain the deployment process itself already computed
     * ({@code ApplicationServiceTopologyConfiguration}/{@code JenkinsServiceTopologyConfiguration}
     * etc. set {@code recordName} from {@code deploymentContext.subdomain}) so the edge can route
     * on that same identity rather than falling back to one fixed hostname per application type.
     *
     * @return the derived {@code <subdomain>.cloudforge.localhost} edge hostname, or {@code null}
     *         when no RecordSet was found (no subdomain configured) or its {@code Name} wasn't a
     *         plain string (e.g. still an unresolved CDK token) — the caller falls back to the
     *         per-application-type hostname in that case.
     */
    private static String removeRoute53RecordSets(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return null;
        }
        List<String> remove = new ArrayList<>();
        String[] capturedRecordName = new String[1];
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null || !"AWS::Route53::RecordSet".equals(resource.path("Type").asText())) {
                return;
            }
            remove.add(entry.getKey());
            if (capturedRecordName[0] == null) {
                JsonNode nameNode = resource.path("Properties").path("Name");
                if (nameNode.isTextual() && !nameNode.asText().isBlank()) {
                    capturedRecordName[0] = nameNode.asText();
                }
            }
            adaptations.add(new TemplateAdaptation(
                "Resources." + entry.getKey(),
                "LocalStack emulator edge owns cloudforge.localhost routing; Route 53 aliases are omitted",
                resource.deepCopy()));
        });
        if (!remove.isEmpty()) {
            remove.forEach(resources::remove);
            cleanupDependsOnReferences(resources, remove);
        }
        return deriveEdgeHostname(capturedRecordName[0]);
    }

    /**
     * {@code jenkins1.example.com.} (the resolved Route53 record name, subdomain + registered
     * domain, trailing dot per DNS convention) → {@code jenkins1.cloudforge.localhost} (the edge
     * hostname). Only the first label is kept — the registered domain is real-AWS-specific and
     * meaningless under the emulator's own {@code cloudforge.localhost} suffix.
     */
    static String deriveEdgeHostname(String route53RecordName) {
        if (route53RecordName == null) {
            return null;
        }
        String trimmed = route53RecordName.trim();
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return null;
        }
        int dot = trimmed.indexOf('.');
        String label = (dot < 0 ? trimmed : trimmed.substring(0, dot)).toLowerCase(Locale.ROOT).trim();
        return label.isBlank() ? null : label + ".cloudforge.localhost";
    }

    private static void cleanupDependsOnReferences(ObjectNode resources, List<String> removed) {
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
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

    /**
     * CDK injects {@code BootstrapVersion} as {@code AWS::SSM::Parameter::Value<String>}.
     * LocalStack CloudFormation often fails to resolve that dynamic type from Default alone;
     * rewrite to a plain String default so create/update works without a prior CDK bootstrap.
     */
    private static void resolveCdkBootstrapParameters(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode parameters = asObject(template.get("Parameters"));
        if (parameters == null) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = parameters.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            ObjectNode parameter = asObject(entry.getValue());
            if (parameter == null) {
                continue;
            }
            String type = parameter.path("Type").asText();
            if (type == null || !type.startsWith("AWS::SSM::Parameter::Value")) {
                continue;
            }
            adaptations.add(new TemplateAdaptation(
                "Parameters." + entry.getKey(),
                "LocalStack: resolve CDK SSM bootstrap parameter to a plain String default",
                parameter.deepCopy()
            ));
            String defaultValue = parameter.path("Default").asText(null);
            // Prefer a numeric bootstrap version when Default is an SSM path
            String resolved = (defaultValue != null && defaultValue.startsWith("/"))
                ? "21"
                : (defaultValue == null || defaultValue.isBlank() ? "21" : defaultValue);
            parameter.put("Type", "String");
            parameter.put("Default", resolved);
        }
    }

    /**
     * Offline CDK synth often stamps subnet AZs as {@code dummy1a}/{@code dummy1b}.
     * MiniStack accepts those; LocalStack EC2 validates against real AZ names.
     */
    private static void rewriteDummyAvailabilityZones(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        String region = System.getenv().getOrDefault("AWS_DEFAULT_REGION", "us-east-1");
        rewriteDummyAvailabilityZones(template, adaptations, region);
    }

    static void rewriteDummyAvailabilityZones(
            ObjectNode template,
            List<TemplateAdaptation> adaptations,
            String region) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null || region == null || region.isBlank()) {
            return;
        }
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null || !"AWS::EC2::Subnet".equals(resource.path("Type").asText())) {
                return;
            }
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null || !properties.has("AvailabilityZone")) {
                return;
            }
            JsonNode azNode = properties.get("AvailabilityZone");
            if (azNode == null || !azNode.isTextual()) {
                return;
            }
            String az = azNode.asText();
            if (az == null || !az.startsWith("dummy")) {
                return;
            }
            // dummy1a -> us-east-1a, dummy1b -> us-east-1b
            String suffix = az.substring("dummy".length()); // e.g. 1a
            String letter = suffix.isEmpty() ? "a" : suffix.substring(suffix.length() - 1);
            String mapped = region + letter;
            adaptations.add(new TemplateAdaptation(
                "Resources." + entry.getKey() + ".Properties.AvailabilityZone",
                "LocalStack rejects CDK offline dummy AZs; map to region AZ " + mapped,
                azNode.deepCopy()
            ));
            properties.put("AvailabilityZone", mapped);
        });
    }

    private static void inlineUnsupportedSecurityGroupIngress(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }

        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null
                    || !"AWS::EC2::SecurityGroupIngress".equals(
                        resource.path("Type").asText())) {
                return;
            }

            ObjectNode ingress = asObject(resource.path("Properties").deepCopy());
            JsonNode groupId = ingress == null ? null : ingress.remove("GroupId");
            String targetLogicalId = referencedLogicalId(groupId);
            ObjectNode target =
                targetLogicalId == null ? null : asObject(resources.get(targetLogicalId));
            ObjectNode targetProperties =
                target == null ? null : asObject(target.get("Properties"));
            if (ingress == null || targetProperties == null
                    || !"AWS::EC2::SecurityGroup".equals(target.path("Type").asText())) {
                throw new IllegalArgumentException(
                    "Cannot inline LocalStack security-group ingress " + entry.getKey());
            }

            targetProperties.withArray("SecurityGroupIngress").add(ingress);
            adaptations.add(new TemplateAdaptation(
                "Resources." + entry.getKey(),
                "LocalStack CloudFormation requires ingress inline on AWS::EC2::SecurityGroup",
                resource.deepCopy()
            ));
            remove.add(entry.getKey());
        });
        remove.forEach(resources::remove);
    }

    private static String referencedLogicalId(JsonNode reference) {
        if (reference == null) {
            return null;
        }
        if (reference.has("Ref")) {
            return reference.path("Ref").asText();
        }
        JsonNode getAtt = reference.get("Fn::GetAtt");
        return getAtt != null && getAtt.isArray() && !getAtt.isEmpty()
            ? getAtt.get(0).asText()
            : null;
    }

    private static void replaceUnsupportedEfsWithHostBindMounts(
            ObjectNode template,
            List<TemplateAdaptation> adaptations,
            String stackName,
            com.cloudforge.core.local.LocalStackCapabilitySnapshot snapshot) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }

        Set<String> removedLogicalIds = new LinkedHashSet<>();
        if (!snapshot.keepEfsResources()) {
            Iterator<Map.Entry<String, JsonNode>> fields = resources.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getValue().path("Type").asText().startsWith("AWS::EFS::")) {
                    removedLogicalIds.add(entry.getKey());
                    adaptations.add(new TemplateAdaptation(
                        "Resources." + entry.getKey(),
                        "LocalStack CloudFormation does not support AWS::EFS resources",
                        entry.getValue().deepCopy()
                    ));
                    fields.remove();
                }
            }
        }

        ObjectNode outputsNode = asObject(template.get("Outputs"));
        if (outputsNode == null) {
            outputsNode = template.putObject("Outputs");
        }
        final ObjectNode outputs = outputsNode;

        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null
                    || !"AWS::ECS::TaskDefinition".equals(resource.path("Type").asText())) {
                return;
            }
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null) {
                return;
            }

            JsonNode volumesNode = properties.get("Volumes");
            if (!(volumesNode instanceof ArrayNode volumes)) {
                return;
            }

            for (int index = 0; index < volumes.size(); index++) {
                JsonNode volume = volumes.get(index);
                if (!volume.has("EFSVolumeConfiguration")) {
                    continue;
                }

                String volumeName = volume.path("Name").asText();
                if (volumeName.isBlank()) {
                    throw new IllegalArgumentException(
                        "EFS-backed ECS volume must have a Name for LocalStack host bind mount");
                }

                Path hostPath = resolveHostVolumePath(stackName, volumeName);
                ObjectNode replacement = MAPPER.createObjectNode();
                replacement.put("Name", volumeName);
                String reason;
                boolean hostMountUsable;
                try {
                    Files.createDirectories(hostPath);
                    hostMountUsable = true;
                } catch (IOException e) {
                    // This directory only means anything on the real host filesystem that
                    // LocalStack's own Docker executor can see — a caller running containerized
                    // itself (Manager's own Spring Boot process, doing deploy:create against
                    // LocalStack) has no such access, so creation predictably fails (and even
                    // where the mkdir itself *would* succeed inside that caller's own container,
                    // the resulting SourcePath still wouldn't resolve to anything on the host —
                    // it'd just be a path inside that caller's own throwaway filesystem).
                    // Hard-aborting the whole deploy over this would be needlessly strict;
                    // degrading to an ephemeral task-scoped Docker volume instead lets the
                    // deploy — and the container's own expectation of *something* mounted at this
                    // path — still succeed. The tradeoff (this app's data won't survive a task
                    // replacement when deployed this way) is surfaced in the adaptation `reason`
                    // below.
                    hostMountUsable = false;
                }
                if (hostMountUsable) {
                    replacement.putObject("Host").put("SourcePath", hostPath.toString());
                    reason = snapshot.keepEfsResources()
                        ? "LocalStack ECS Docker tasks require host bind mounts even when native EFS is available"
                        : "Replaced unsupported LocalStack EFS with host bind mount at " + hostPath;
                } else {
                    replacement.putObject("DockerVolumeConfiguration")
                        .put("Scope", "task")
                        .put("Autoprovision", false);
                    reason = "Could not create host bind mount directory " + hostPath
                        + " (no real host filesystem access from this deploy caller) — using an "
                        + "ephemeral per-task Docker volume instead. Data in this volume will NOT "
                        + "survive the task being replaced; deploy via the CLI (which runs "
                        + "directly on the host) instead if this application needs its data to persist.";
                }
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties.Volumes[" + index + "]",
                    reason,
                    volume.deepCopy()
                ));
                volumes.set(index, replacement);

                if (hostMountUsable) {
                    String outputKey = hostVolumeOutputKey(volumeName);
                    if (!outputs.has(outputKey)) {
                        outputs.putObject(outputKey)
                            .put("Description",
                                "Host bind mount for ECS volume '" + volumeName + "'")
                            .put("Value", hostPath.toString());
                    }
                }
            }
        });

        if (removedLogicalIds.isEmpty()) {
            return;
        }

        String adaptedTemplate = template.toString();
        removedLogicalIds.stream()
            .filter(adaptedTemplate::contains)
            .findFirst()
            .ifPresent(logicalId -> {
                throw new IllegalArgumentException(
                    "Unsupported EFS resource " + logicalId
                        + " is still referenced after LocalStack adaptation");
            });
    }

    static Path resolveHostVolumePath(String stackName, String volumeName) {
        Path root = defaultVolumeRoot();
        String safeStackName = stackName == null || stackName.isBlank() ? "local" : stackName;
        String safeVolumeName = volumeName == null || volumeName.isBlank() ? "data" : volumeName;
        return root.resolve(safeStackName).resolve(safeVolumeName).toAbsolutePath().normalize();
    }

    static Path defaultVolumeRoot() {
        String configured = System.getenv("LOCALSTACK_VOLUME_ROOT");
        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_VOLUME_ROOT;
        }
        Path root = Paths.get(configured);
        if (!root.isAbsolute()) {
            root = Paths.get(System.getProperty("user.dir")).resolve(root);
        }
        return root.toAbsolutePath().normalize();
    }

    /**
     * {@code defaultVolumeRoot()} falls back to {@code user.dir} whenever {@code
     * LOCALSTACK_VOLUME_ROOT} isn't set — meaningful only when the JVM adapting the template is
     * running directly on the host (the interactive deployer, a CLI, a test). {@code
     * deploy:create}/{@code deploy:catalog} run this exact same adapt pipeline from *inside
     * Manager's own already-running container*, though, where {@code user.dir} is {@code /app}
     * (the Dockerfile's WORKDIR), a path meaningless to the Docker host. Every EFS-backed volume
     * for every app deployed through Manager's UI (Jenkins, GitLab, Manager itself) would then
     * bind-mount to a bogus {@code /app/.localstack-volumes/...} path that Docker auto-creates
     * empty, resetting the app's persistent data on every redeploy while the deploy still
     * "succeeds".
     *
     * <p>This method resolves the value once, from a process where {@code user.dir} is trustworthy
     * (this method's own {@code defaultVolumeRoot()} fallback still does exactly that), and bake
     * it into Manager's own launched container as {@code LOCALSTACK_VOLUME_ROOT}. From then on,
     * every synth+adapt Manager's own process performs — including adapting its own next
     * self-deploy's template, and every other app's — reads that already-correct value straight
     * from its environment via {@link #defaultVolumeRoot()} instead of re-deriving a meaningless
     * one from its own containerized {@code user.dir}. Self-perpetuating: once correctly seeded
     * (by the very first, host-run deploy of Manager), every later generation propagates the same
     * value forward unchanged, since {@code defaultVolumeRoot()} always prefers whatever's already
     * in the environment over computing something fresh.
     */
    private static String managerVolumeRoot() {
        return defaultVolumeRoot().toString();
    }

    private static String hostVolumeOutputKey(String volumeName) {
        return "LocalStackHostVolume"
            + volumeName.substring(0, 1).toUpperCase()
            + volumeName.substring(1);
    }

    private static String stackNameFromTemplatePath(Path canonicalTemplate) {
        String fileName = canonicalTemplate.getFileName().toString();
        if (fileName.endsWith(".template.json")) {
            return fileName.substring(0, fileName.length() - ".template.json".length());
        }
        return fileName;
    }

    public static TemplateAdaptationResult adaptFile(Path canonicalTemplate, Path localTemplate, Path report)
            throws IOException {
        return adaptFile(canonicalTemplate, localTemplate, report,
            stackNameFromTemplatePath(canonicalTemplate));
    }

    public static TemplateAdaptationResult adaptFile(
            Path canonicalTemplate,
            Path localTemplate,
            Path report,
            String stackName) throws IOException {
        return TemplateAdapterSupport.adaptFile(
            INSTANCE, canonicalTemplate, localTemplate, report, stackName);
    }

    private static void removeUnsupportedAuthActions(
            JsonNode actionsNode,
            String path,
            List<TemplateAdaptation> adaptations) {
        if (!(actionsNode instanceof ArrayNode actions)) {
            return;
        }

        for (int index = actions.size() - 1; index >= 0; index--) {
            JsonNode action = actions.get(index);
            String type = action.path("Type").asText();
            if ("authenticate-oidc".equals(type) || "authenticate-cognito".equals(type)) {
                adaptations.add(new TemplateAdaptation(
                    path + "[" + index + "]",
                    "LocalStack ALB supports forward/redirect/fixed-response actions only",
                    action.deepCopy()
                ));
                actions.remove(index);
            }
        }

        if (actions.isEmpty()) {
            throw new IllegalArgumentException(
                "Refusing to create a listener with no action after adapting " + path);
        }
    }

    private static void addLocalUrlOutput(
            ObjectNode template,
            List<TemplateAdaptation> adaptations,
            boolean authenticationEnabled) {
        resolveAlbLocalName(template, adaptations).ifPresent(localName -> {
        int gatewayPort = resolveGatewayPort();
        String gateway = Integer.toString(gatewayPort);
        ObjectNode outputs = asObject(template.get("Outputs"));
        if (outputs == null) {
            outputs = template.putObject("Outputs");
        }
        if (!outputs.has(OUTPUT_LOCAL_URL)) {
            ObjectNode output = outputs.putObject(OUTPUT_LOCAL_URL);
            output.put("Description", "Reachable LocalStack ALB URL (path-style)");
            output.put("Value", pathStyleBrowserUrl(gatewayPort, localName));
        }

        if (!outputs.has(OUTPUT_APPLICATION_URL)) {
            int appPort = findEcsApplicationPort(asObject(template.get("Resources")));
            String browserUrl = appPort > 0 && !RESERVED_LOCAL_HOST_PORTS.contains(appPort)
                ? "http://localhost:" + appPort + "/"
                : pathStyleBrowserUrl(gatewayPort, localName);
            outputs.putObject(OUTPUT_APPLICATION_URL)
                .put("Description", "Browser-safe LocalStack URL (ALB redirects to localhost ECS port)")
                .put("Value", browserUrl);
        }

        if (!outputs.has(OUTPUT_ELB_HOSTNAME_URL)) {
            outputs.putObject(OUTPUT_ELB_HOSTNAME_URL)
                .put("Description", "LocalStack ELB hostname (curl/LB probe; browsers redirect to path-style)")
                .put("Value",
                    "https://" + localName + ".elb.localhost.localstack.cloud:" + gateway + "/");
        }

        // Authenticated URL omitted: LocalStack ALB does not run authenticate-* actions;
        // use application-oidc on the app when needed (auth actions already stripped).
        if (authenticationEnabled) {
            adaptations.add(new TemplateAdaptation(
                "Outputs." + OUTPUT_AUTHENTICATED_URL,
                "LocalStack strips ALB authenticate-* actions; use application-oidc or authMode none",
                com.fasterxml.jackson.databind.node.NullNode.instance
            ));
        }
        });
    }

    /**
     * Plain HTTP, not HTTPS — LocalStack's edge (2026.7.2) completes a TLS handshake with
     * a valid cert on this port, but then 400s every {@code /_aws/elb/<name>/}
     * path-style request over HTTPS regardless of hostname (bare {@code localhost} or {@code
     * localhost.localstack.cloud}), HTTP version, or trailing slash — a platform-level routing gap
     * for this specific proxy path, not a client/cert issue (same 400 for the subdomain-style
     * {@code <name>.elb.localhost.localstack.cloud} ELB hostname too). Plain HTTP on the same port
     * reaches the app correctly. {@link #addLocalUrlOutput}'s {@code OUTPUT_APPLICATION_URL} branch already prefers a direct
     * {@code http://localhost:<port>/} whenever the app's own port isn't one of {@link
     * #RESERVED_LOCAL_HOST_PORTS}, so this path-style fallback (and this method) mainly gets
     * exercised by apps whose default port collides with a reservation, such as WordPress (port 80,
     * reserved by the emulator edge).
     */
    private static String pathStyleBrowserUrl(int gatewayPort, String albLocalName) {
        return "http://localhost:" + gatewayPort + "/_aws/elb/" + albLocalName + "/";
    }

    private static void suffixLogGroupNamesForLocalStack(
            ObjectNode template,
            String stackName,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        String suffix = stackName == null || stackName.isBlank() ? "localstack" : stackName;
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null || !"AWS::Logs::LogGroup".equals(resource.path("Type").asText())) {
                return;
            }
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null || !properties.has("LogGroupName")) {
                return;
            }
            String current = properties.path("LogGroupName").asText();
            if (current.contains("-localstack")) {
                return;
            }
            String updated = current.replace("Jenkins-Stack", suffix)
                .replace("CloudForgeManager-Dev", suffix);
            if (updated.equals(current)) {
                updated = current + "-" + suffix;
            }
            properties.put("LogGroupName", updated);
            adaptations.add(new TemplateAdaptation(
                "Resources." + entry.getKey() + ".Properties.LogGroupName",
                "Avoid orphaned CloudWatch log group collisions across LocalStack redeploys",
                com.fasterxml.jackson.databind.node.NullNode.instance));
        });
    }

    /**
     * Path-style LocalStack ELB URLs ({@code /_aws/elb/{name}/...}) require apps to
     * know their public prefix or static assets resolve from {@code /static} (404).
     */
    private static void injectLocalStackPathPrefixForEcsTasks(
            ObjectNode template,
            String albLocalName,
            String edgeHostname,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        String prefix = "/_aws/elb/" + albLocalName;
        int gatewayPort = resolveGatewayPort();
        // Plain HTTP — see pathStyleBrowserUrl's javadoc for why: this exact path-style route
        // 400s over HTTPS regardless of hostname/HTTP-version, a LocalStack edge-routing gap
        // rather than anything client-side. Same URL shape apps embed in their
        // own config (WP_HOME/WP_SITEURL, JENKINS_URL, CFC_MANAGER_PUBLIC_URL), so it needs to
        // stay consistent with what OUTPUT_APPLICATION_URL actually hands back.
        String publicUrl = "http://localhost:" + gatewayPort + prefix + "/";

        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null
                    || !"AWS::ECS::TaskDefinition".equals(resource.path("Type").asText())) {
                return;
            }
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null || !(properties.get("ContainerDefinitions") instanceof ArrayNode containers)) {
                return;
            }
            for (int i = 0; i < containers.size(); i++) {
                ObjectNode container = asObject(containers.get(i));
                if (container == null) {
                    continue;
                }
                String image = container.path("Image").asText("").toLowerCase(Locale.ROOT);
                boolean jenkins = image.contains("jenkins");
                boolean manager = isManagerContainer(container);
                // wordpress:php8.2-apache and woocommerce (same image, see WordPressApplicationSpec/
                // WooCommerceApplicationSpec) — matched by image name like Jenkins, not container
                // identity, since neither has a dedicated marker the way Manager's container does.
                boolean wordpress = image.contains("wordpress");
                if (!jenkins && !manager && !wordpress) {
                    continue;
                }
                if (jenkins && hasApplicationOidcConfig(container)) {
                    continue;
                }
                ArrayNode environment = ensureEnvironmentArray(container);
                upsertEnvironment(environment, "CFC_LOCALSTACK_ALB_PREFIX", prefix);
                upsertEnvironment(environment, "CFC_LOCALSTACK_PUBLIC_URL", publicUrl);
                if (edgeHostname != null && !edgeHostname.isBlank()) {
                    // Read by DefaultEmulatorEdgeRuntime (cloudforge-core) via `docker inspect`
                    // alongside CFC_LOCALSTACK_ALB_PREFIX, so two instances of the same app
                    // (e.g. jenkins1/jenkins2, each deployed with a distinct `subdomain`) get
                    // their own nginx vhost instead of colliding on one shared per-application
                    // hostname.
                    upsertEnvironment(environment, "CFC_LOCALSTACK_EDGE_HOSTNAME", edgeHostname);
                }
                if (jenkins) {
                    mergeJenkinsOpts(environment, "--prefix=" + prefix);
                    upsertEnvironment(environment, "JENKINS_URL", publicUrl);
                    mergeJavaOptsRootUrl(environment, publicUrl);
                }
                if (manager) {
                    upsertEnvironment(environment, "CFC_MANAGER_PUBLIC_URL", publicUrl);
                }
                if (wordpress) {
                    mergeWordPressSiteUrl(environment, publicUrl, prefix);
                }
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties.ContainerDefinitions[" + i
                        + "].Environment",
                    jenkins
                        ? "LocalStack path-style ELB requires Jenkins --prefix for static assets"
                        : wordpress
                            ? "LocalStack path-style ELB requires WordPress WP_HOME/WP_SITEURL "
                                + "so it knows it's mounted under a subpath, not site root"
                            : "LocalStack path-style ELB public URL for Manager",
                    com.fasterxml.jackson.databind.node.NullNode.instance
                ));
            }
        });
    }

    /**
     * Rewrites Cognito callback URLs and Jenkins JCasC OIDC config for LocalStack.
     *
     * <p>When the canonical deployment has a {@code *.cloudforge.localhost} callback, preserve
     * that named browser host and use the HTTP emulator edge. This keeps the callback Jenkins
     * sends to Cognito identical to the one Cognito permits. Templates without a named local
     * host retain the direct {@code localhost:{port}} fallback.</p>
     */
    private static void rewriteApplicationOidcForLocalStack(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        int appPort = findEcsApplicationPort(resources);
        if (appPort <= 0) {
            appPort = 8080;
        }
        String appBase = resolveLocalApplicationBase(template, appPort);
        String browserGateway = localStackGatewayBase();
        String containerGateway = resolveContainerLocalStackEndpoint();
        String issuerGateway = resolveCognitoIssuerBase();
        boolean adapted = false;

        Iterator<Map.Entry<String, JsonNode>> fields = resources.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null) {
                continue;
            }
            String type = resource.path("Type").asText();
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null) {
                continue;
            }

            if ("AWS::Cognito::UserPool".equals(type)) {
                // The LocalStack hosted Cognito page cannot complete an MFA challenge
                // without a separately enrolled device. Keep production MFA in canonical
                // AWS templates while making application-oidc usable in the local emulator.
                properties.put("MfaConfiguration", "OFF");
                properties.remove("EnabledMfas");
                properties.remove("SoftwareTokenMfaConfiguration");
                adapted = true;
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties.MfaConfiguration",
                    "LocalStack application-oidc disables unsupported hosted-login MFA",
                    com.fasterxml.jackson.databind.node.NullNode.instance));
            } else if ("AWS::Cognito::UserPoolClient".equals(type)) {
                String callbackPath = callbackPath(properties.path("CallbackURLs"));
                ArrayNode callbacks = properties.putArray("CallbackURLs");
                callbacks.add(appBase + callbackPath);
                ArrayNode logouts = properties.putArray("LogoutURLs");
                logouts.add(appBase + "/");
                adapted = true;
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties.CallbackURLs",
                    "LocalStack application-oidc callback targets the browser-safe local application URL",
                    com.fasterxml.jackson.databind.node.NullNode.instance));
            } else if ("AWS::ECS::TaskDefinition".equals(type)
                    && properties.get("ContainerDefinitions") instanceof ArrayNode containers) {
                for (int i = 0; i < containers.size(); i++) {
                    ObjectNode container = asObject(containers.get(i));
                    if (container == null || !hasApplicationOidcConfig(container)) {
                        continue;
                    }
                    rewriteJenkinsOidcContainer(container, appBase, browserGateway, containerGateway, issuerGateway);
                    inlineOidcSecretForLocalStack(container);
                    adapted = true;
                    adaptations.add(new TemplateAdaptation(
                        "Resources." + entry.getKey() + ".Properties.ContainerDefinitions[" + i + "]",
                        "LocalStack application-oidc Jenkins URLs and Cognito endpoints",
                        com.fasterxml.jackson.databind.node.NullNode.instance));
                }
            }
        }

        if (adapted) {
            ObjectNode outputs = asObject(template.get("Outputs"));
            if (outputs != null && outputs.has(OUTPUT_APPLICATION_URL)) {
                ObjectNode output = asObject(outputs.get(OUTPUT_APPLICATION_URL));
                if (output != null) {
                    output.put("Value", appBase + "/");
                    output.put("Description",
                        "Browser-safe LocalStack URL for application-oidc Jenkins");
                }
            }
        }
    }

    private static void rewriteJenkinsOidcContainer(
            ObjectNode container,
            String appBase,
            String browserGateway,
            String containerGateway,
            String issuerGateway) {
        if (container.get("Command") instanceof ArrayNode command) {
            for (int i = 0; i < command.size(); i++) {
                JsonNode commandPart = command.get(i);
                if (commandPart.isTextual()) {
                    command.set(i, rewriteLocalStackOidcText(
                        commandPart.asText(), appBase, browserGateway, containerGateway, issuerGateway));
                } else {
                    rewriteOidcCommandNode(commandPart, appBase, browserGateway, containerGateway, issuerGateway);
                }
            }
        }
        if (container.get("Environment") instanceof ArrayNode environment) {
            upsertEnvironment(environment, "AWS_ENDPOINT_URL", containerGateway);
            upsertEnvironment(environment, "LOCALSTACK_ENDPOINT", containerGateway);
            for (int i = 0; i < environment.size(); i++) {
                ObjectNode env = asObject(environment.get(i));
                if (env == null) {
                    continue;
                }
                String name = env.path("Name").asText();
                if ("JENKINS_URL".equals(name)) {
                    env.put("Value", appBase);
                } else if (ManagerEnvKeys.OIDC_REDIRECT_URL.equals(name)) {
                    // The app's own redirect_uri (sent as an OAuth query param, and what Cognito
                    // sends the browser back to after login) is built from the ALB's real DNS
                    // name via a deploy-time Fn::Join/Fn::GetAtt chain — correct for a real AWS
                    // deployment, but on LocalStack that host either isn't reachable at all or
                    // has nothing listening on the implicit HTTPS port, so the browser sees
                    // "can't connect" right after a successful login. Full-value override rather
                    // than a pattern rewrite (the ALB-DNSName join tree has no fixed literal
                    // prefix to match against, unlike the Cognito-domain-based endpoints above) —
                    // matches exactly what the Cognito App Client's own CallbackURLs already get
                    // rewritten to below (`appBase + callbackPath`), since a redirect_uri that
                    // doesn't match a registered callback is itself invalid regardless of the
                    // unreachable-host problem.
                    env.put("Value", appBase + com.cloudforge.core.oidc.CloudForgeManagerOidcIntegration.CALLBACK_PATH);
                } else if ("JAVA_OPTS".equals(name)) {
                    env.put("Value", rewriteLocalStackOidcText(
                        env.path("Value").asText(""), appBase, browserGateway, containerGateway, issuerGateway));
                } else if ("GITLAB_OMNIBUS_CONFIG".equals(name)) {
                    env.put("Value", rewriteLocalStackOidcText(
                        env.path("Value").asText(""), appBase, browserGateway, containerGateway, issuerGateway));
                } else if (MANAGER_OIDC_ENDPOINT_ENV_NAMES.contains(name)) {
                    // CloudForge Manager's own application-oidc env vars (built server-side by
                    // ApplicationOidcFactory/CognitoAuthenticationFactory, not baked into a
                    // Command string the way Jenkins/GitLab's are) need the same rewrite, or
                    // Manager's Cognito Hosted UI redirect points at the amazoncognito.com domain,
                    // unreachable on LocalStack, instead of the emulator's `/_aws/cognito-idp/...`
                    // endpoints used for every other application-oidc app.
                    //
                    // Unlike JAVA_OPTS/GITLAB_OMNIBUS_CONFIG, these values are not always plain
                    // strings: the issuer/JWKS URIs embed the Cognito User Pool's ID, which isn't
                    // known until deploy time, so CDK builds them as an `Fn::Join` combining a
                    // literal prefix with a `Ref`/`Fn::GetAtt` token. Coercing a non-textual node
                    // with `.asText()` discards that intrinsic and replaces it with an
                    // empty string instead of rewriting it — handle those two by walking the join
                    // parts directly rather than reusing rewriteOidcCommandNode/
                    // rewriteLocalStackOidcText: the JWKS URI's correct target gateway (container-
                    // reachable, since the container itself fetches its own signing keys) depends
                    // on recognizing the "...well-known/jwks.json" suffix, which only exists in a
                    // *different* join part once split, not in the same string as the prefix.
                    JsonNode value = env.get("Value");
                    if (value == null) {
                        continue;
                    }
                    if (value.isTextual()) {
                        env.put("Value", rewriteLocalStackOidcText(
                            value.asText(""), appBase, browserGateway, containerGateway, issuerGateway));
                    } else if (ManagerEnvKeys.OIDC_ISSUER.equals(name)) {
                        rewriteRealCognitoIdpPrefix(value, issuerGateway);
                    } else if (ManagerEnvKeys.OIDC_JWKS_URI.equals(name)) {
                        rewriteRealCognitoIdpPrefix(value, containerGateway);
                    } else {
                        rewriteOidcCommandNode(value, appBase, browserGateway, containerGateway, issuerGateway);
                    }
                }
            }
        }
    }

    /**
     * LocalStack ECS cannot resolve Secrets Manager ARNs supplied by CloudFormation for
     * the generated Cognito client secret. Keep the local task runnable with a placeholder;
     * the post-deploy reconciler remains responsible for syncing the real secret.
     */
    private static void inlineOidcSecretForLocalStack(ObjectNode container) {
        if (!(container.get("Secrets") instanceof ArrayNode secrets)) {
            return;
        }
        List<String> oidcSecretNames = new ArrayList<>();
        for (JsonNode secret : secrets) {
            String name = secret.path("Name").asText();
            if (name.endsWith("OIDC_CLIENT_SECRET")) {
                oidcSecretNames.add(name);
            }
        }
        if (oidcSecretNames.isEmpty()) {
            return;
        }
        ArrayNode retainedSecrets = MAPPER.createArrayNode();
        for (JsonNode secret : secrets) {
            if (!oidcSecretNames.contains(secret.path("Name").asText())) {
                retainedSecrets.add(secret);
            }
        }
        if (retainedSecrets.isEmpty()) {
            container.remove("Secrets");
        } else {
            container.set("Secrets", retainedSecrets);
        }
        ArrayNode environment = container.get("Environment") instanceof ArrayNode current
            ? current : container.putArray("Environment");
        oidcSecretNames.forEach(name -> upsertEnvironment(environment, name, "pending-localstack-sync"));
    }

    private static void rewriteOidcCommandNode(
            JsonNode node,
            String appBase,
            String browserGateway,
            String containerGateway,
            String issuerGateway) {
        if (node == null || !node.isObject() || !node.has("Fn::Join")) {
            return;
        }
        JsonNode joinNode = node.get("Fn::Join");
        if (!(joinNode instanceof ArrayNode join) || join.size() < 2) {
            return;
        }
        JsonNode partsNode = join.get(1);
        if (!(partsNode instanceof ArrayNode parts)) {
            return;
        }
        for (int i = 0; i < parts.size(); i++) {
            JsonNode part = parts.get(i);
            if (part.isTextual()) {
                parts.set(i, rewriteLocalStackOidcText(
                    part.asText(), appBase, browserGateway, containerGateway, issuerGateway));
            }
        }
    }

    /** Replaces the literal {@code https://cognito-idp.us-east-1.amazonaws.com/} prefix CDK
     *  bakes into an {@code Fn::Join}-shaped OIDC issuer/JWKS URI with the given gateway, leaving
     *  the join's other parts (the {@code Ref}/{@code Fn::GetAtt} User Pool ID token) untouched.
     *  A direct prefix swap rather than {@link #rewriteLocalStackOidcText}'s broader
     *  pattern-matching, because that helper's issuer-vs-JWKS distinction depends on seeing the
     *  {@code .well-known/jwks.json} suffix in the *same* string as the prefix — true for
     *  Jenkins's single-string CASC config, not for a value split across join parts. */
    private static void rewriteRealCognitoIdpPrefix(JsonNode node, String targetGateway) {
        if (node == null || !node.isObject() || !node.has("Fn::Join")) {
            return;
        }
        JsonNode joinNode = node.get("Fn::Join");
        if (!(joinNode instanceof ArrayNode join) || join.size() < 2) {
            return;
        }
        JsonNode partsNode = join.get(1);
        if (!(partsNode instanceof ArrayNode parts)) {
            return;
        }
        String realPrefix = "https://cognito-idp.us-east-1.amazonaws.com/";
        for (int i = 0; i < parts.size(); i++) {
            JsonNode part = parts.get(i);
            if (part.isTextual() && part.asText().startsWith(realPrefix)) {
                parts.set(i, targetGateway + "/" + part.asText().substring(realPrefix.length()));
            }
        }
    }

    private static String rewriteLocalStackOidcText(
            String value,
            String appBase,
            String browserGateway,
            String containerGateway,
            String issuerGateway) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String rewritten = value
            .replace(appBase.replaceFirst("^http://", "https://"), appBase)
            .replace("'X-Forwarded-Proto' => 'https'", "'X-Forwarded-Proto' => 'http'")
            .replace("'X-Forwarded-Ssl' => 'on'", "'X-Forwarded-Ssl' => 'off'")
            .replace("nginx['listen_https'] = false;", "nginx['listen_https'] = false; nginx['hsts_max_age'] = 0;")
            .replace("https://jenkins.local.test", appBase)
            .replace("http://jenkins.local.test", appBase)
            .replaceAll(NAMED_LOCAL_HOST_URL.pattern(), Matcher.quoteReplacement(appBase))
            .replaceAll(LOCALSTACK_ELB_HOST_URL.pattern(), Matcher.quoteReplacement(appBase))
            .replaceAll(
                "https://[^\"\\s]+\\.auth\\.[^\"\\s]+\\.amazoncognito\\.com/oauth2/authorize",
                browserGateway + "/_aws/cognito-idp/oauth2/authorize")
            .replaceAll(
                "https://[^\"\\s]+\\.auth\\.[^\"\\s]+\\.amazoncognito\\.com/oauth2/token",
                containerGateway + "/_aws/cognito-idp/oauth2/token")
            .replaceAll(
                "https://[^\"\\s]+\\.auth\\.[^\"\\s]+\\.amazoncognito\\.com/oauth2/userInfo",
                containerGateway + "/_aws/cognito-idp/oauth2/userInfo")
            .replaceAll(
                "https://[^\"\\s]+\\.auth\\.[^\"\\s]+\\.amazoncognito\\.com/logout",
                browserGateway + "/_aws/cognito-idp/logout")
            .replace("https://cognito-idp.us-east-1.amazonaws.com/", issuerGateway + "/")
            .replace("https://cognito-idp.localhost.localstack.cloud/", issuerGateway + "/");
        rewritten = rewriteLocalStackGatewayForContainer(
            rewritten, browserGateway, containerGateway, issuerGateway);
        return rewritten;
    }

    private static String resolveLocalApplicationBase(ObjectNode template, int appPort) {
        String namedHost = findNamedLocalCallbackHost(template);
        if (namedHost != null) {
            // The emulator edge is HTTP unless a local TLS listener has explicitly been added.
            return "http://" + namedHost;
        }
        // CloudForge Manager's own Cognito CallbackURLs are built from the ALB's real DNS name
        // (an Fn::Join/Fn::GetAtt token, not a literal placeholder host like Jenkins/GitLab's
        // "jenkins.local.test"), so findNamedLocalCallbackHost above never matches for it. The
        // emulator edge still assigns Manager a stable vhost (HOST_MANAGER) regardless — this
        // template just never says so anywhere. Raw localhost:<port> would use the container's
        // internal port, which the browser can't reach directly and which doesn't survive
        // LocalStack respawning the container onto a new host port; HOST_MANAGER does.
        if (referencesManagerOidcRedirect(template)) {
            return "http://" + LocalEmulatorDefaults.HOST_MANAGER;
        }
        return "http://localhost:" + appPort;
    }

    private static boolean referencesManagerOidcRedirect(ObjectNode template) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return false;
        }
        for (JsonNode resource : resources) {
            if (!"AWS::ECS::TaskDefinition".equals(resource.path("Type").asText())) {
                continue;
            }
            if (!(resource.path("Properties").get("ContainerDefinitions") instanceof ArrayNode containers)) {
                continue;
            }
            for (JsonNode container : containers) {
                if (!(container.get("Environment") instanceof ArrayNode environment)) {
                    continue;
                }
                for (JsonNode env : environment) {
                    if (ManagerEnvKeys.OIDC_REDIRECT_URL.equals(env.path("Name").asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String findNamedLocalCallbackHost(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            Matcher matcher = NAMED_LOCAL_CALLBACK_URL.matcher(node.asText());
            return matcher.find() ? matcher.group(1) : null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String host = findNamedLocalCallbackHost(child);
                if (host != null) {
                    return host;
                }
            }
        } else if (node.isObject()) {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                String host = findNamedLocalCallbackHost(children.next());
                if (host != null) {
                    return host;
                }
            }
        }
        return null;
    }

    private static String callbackPath(JsonNode callbacks) {
        boolean sawIntrinsic = false;
        if (callbacks.isArray()) {
            for (JsonNode callback : callbacks) {
                if (callback.isTextual()) {
                    Matcher matcher = NAMED_LOCAL_CALLBACK_URL.matcher(callback.asText());
                    if (matcher.matches()) {
                        String path = matcher.group(2);
                        return path == null || path.isBlank() ? "/securityRealm/finishLogin" : path;
                    }
                } else {
                    // Jenkins/GitLab's canonical CallbackURLs use a fixed literal placeholder
                    // domain ("jenkins.local.test") the regex above matches directly. CloudForge
                    // Manager has no such placeholder — without a custom domain configured, its
                    // canonical CallbackURLs are built from the ALB's real DNS name via a
                    // deploy-time Fn::Join/Fn::GetAtt token, which arrives here as a JSON object,
                    // not a string. Falling through to Jenkins's callback path for that case would
                    // register the wrong path for Manager's Cognito client.
                    sawIntrinsic = true;
                }
            }
        }
        return sawIntrinsic
            ? com.cloudforge.core.oidc.CloudForgeManagerOidcIntegration.CALLBACK_PATH
            : "/securityRealm/finishLogin";
    }

    /** Browser URLs and JWT issuer stay on LocalStack public host; token/JWKS calls use the container gateway. */
    private static String rewriteLocalStackGatewayForContainer(
            String value, String browserGateway, String containerGateway, String issuerGateway) {
        if (browserGateway.equals(containerGateway) && browserGateway.equals(issuerGateway)) {
            return value;
        }
        String rewritten = value
            .replace(containerGateway + "/_aws/cognito-idp/oauth2/authorize", browserGateway
                + "/_aws/cognito-idp/oauth2/authorize")
            .replace(issuerGateway + "/_aws/cognito-idp/oauth2/authorize", browserGateway
                + "/_aws/cognito-idp/oauth2/authorize")
            .replace(containerGateway + "/_aws/cognito-idp/logout", browserGateway
                + "/_aws/cognito-idp/logout")
            .replace(issuerGateway + "/_aws/cognito-idp/logout", browserGateway
                + "/_aws/cognito-idp/logout")
            .replace(browserGateway + "/_aws/cognito-idp/oauth2/token", containerGateway
                + "/_aws/cognito-idp/oauth2/token")
            .replace(browserGateway + "/_aws/cognito-idp/oauth2/userInfo", containerGateway
                + "/_aws/cognito-idp/oauth2/userInfo")
            .replaceAll(
                browserGateway + "/(us-east-1_[^\"\\s/]+)/\\.well-known/jwks\\.json",
                containerGateway + "/$1/.well-known/jwks.json")
            .replaceAll(
                containerGateway + "/(us-east-1_[^\"\\s/]+)/\\.well-known/jwks\\.json",
                containerGateway + "/$1/.well-known/jwks.json");
        rewritten = rewritten.replaceAll(
            issuerGateway + "/(us-east-1_[^\"\\s/]+)/\\.well-known/jwks\\.json",
            containerGateway + "/$1/.well-known/jwks.json");
        rewritten = rewritten
            .replace("issuer: \"" + browserGateway + "/", "issuer: \"" + issuerGateway + "/")
            .replace("issuer: \"" + containerGateway + "/", "issuer: \"" + issuerGateway + "/");
        return rewritten;
    }

    private static String localStackGatewayBase() {
        String override = System.getenv("CFC_LOCALSTACK_BROWSER_ENDPOINT");
        if (override != null && !override.isBlank()) {
            return override.trim().replaceAll("/$", "");
        }
        // Route the browser through the emulator edge. It is a loopback-safe hostname
        // installed by the LocalStack lifecycle and avoids browser/proxy handling of the
        // public localstack.cloud suffix. Container token exchanges still use
        // host.docker.internal through resolveContainerLocalStackEndpoint().
        return "http://localstack.cloudforge.localhost";
    }

    private static boolean hasApplicationOidcConfig(ObjectNode container) {
        if (container.get("Environment") instanceof ArrayNode environment) {
            for (JsonNode env : environment) {
                if ("CASC_JENKINS_CONFIG".equals(env.path("Name").asText())) {
                    return true;
                }
            }
        }
        if (container.get("Secrets") instanceof ArrayNode secrets) {
            for (JsonNode secret : secrets) {
                if (secret.path("Name").asText().endsWith("OIDC_CLIENT_SECRET")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static java.util.Optional<String> resolveAlbLocalName(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return java.util.Optional.empty();
        }

        String loadBalancerLogicalId = null;
        Iterator<Map.Entry<String, JsonNode>> fields = resources.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if ("AWS::ElasticLoadBalancingV2::LoadBalancer"
                    .equals(entry.getValue().path("Type").asText())) {
                loadBalancerLogicalId = entry.getKey();
                break;
            }
        }
        if (loadBalancerLogicalId == null) {
            return java.util.Optional.empty();
        }

        ObjectNode loadBalancer = asObject(resources.get(loadBalancerLogicalId));
        ObjectNode loadBalancerProperties =
            loadBalancer == null ? null : asObject(loadBalancer.get("Properties"));
        if (loadBalancerProperties == null) {
            return java.util.Optional.empty();
        }
        String localName = loadBalancerProperties.path("Name").asText();
        if (localName.isBlank()) {
            localName = "cfc-" + Integer.toUnsignedString(
                loadBalancerLogicalId.hashCode(), 36);
            loadBalancerProperties.put("Name", localName);
            adaptations.add(new TemplateAdaptation(
                "Resources." + loadBalancerLogicalId + ".Properties.Name",
                "Added deterministic LocalStack ALB data-plane name",
                com.fasterxml.jackson.databind.node.NullNode.instance
            ));
        }
        return java.util.Optional.of(localName);
    }

    private static ArrayNode ensureEnvironmentArray(ObjectNode container) {
        JsonNode existing = container.get("Environment");
        if (existing instanceof ArrayNode array) {
            return array;
        }
        return container.putArray("Environment");
    }

    private static void upsertEnvironment(ArrayNode environment, String name, String value) {
        for (int i = 0; i < environment.size(); i++) {
            ObjectNode entry = asObject(environment.get(i));
            if (entry != null && name.equals(entry.path("Name").asText())) {
                entry.put("Value", value);
                return;
            }
        }
        ObjectNode entry = environment.addObject();
        entry.put("Name", name);
        entry.put("Value", value);
    }

    private static void mergeJenkinsOpts(ArrayNode environment, String extra) {
        for (int i = 0; i < environment.size(); i++) {
            ObjectNode entry = asObject(environment.get(i));
            if (entry == null || !"JENKINS_OPTS".equals(entry.path("Name").asText())) {
                continue;
            }
            String current = entry.path("Value").asText("");
            if (!current.contains(extra)) {
                entry.put("Value", (current + " " + extra).trim());
            }
            return;
        }
        ObjectNode entry = environment.addObject();
        entry.put("Name", "JENKINS_OPTS");
        entry.put("Value", extra);
    }

    private static void mergeJavaOptsRootUrl(ArrayNode environment, String publicUrl) {
        for (int i = 0; i < environment.size(); i++) {
            ObjectNode entry = asObject(environment.get(i));
            if (entry == null || !"JAVA_OPTS".equals(entry.path("Name").asText())) {
                continue;
            }
            String current = entry.path("Value").asText("");
            String property = "-Djenkins.model.Jenkins.rootUrl=" + publicUrl;
            if (current.contains("-Djenkins.model.Jenkins.rootUrl=")) {
                entry.put("Value", current.replaceAll(
                    "-Djenkins\\.model\\.Jenkins\\.rootUrl=\\S+", property));
            } else {
                entry.put("Value", (current + " " + property).trim());
            }
            return;
        }
    }

    /**
     * WORDPRESS_CONFIG_EXTRA carries {@code WP_HOME}/{@code WP_SITEURL} as embedded PHP {@code
     * define()} calls ({@code WordPressApplicationSpec.containerEnvironmentVariables}) — but only
     * when a real {@code fqdn} is configured. A LocalStack deploy typically has none (authMode
     * none, no domain), so there's usually nothing there to replace: without WP_HOME/WP_SITEURL
     * set at all, WordPress falls back to auto-detecting its own URL from the incoming request and
     * has no way to know it's being reached through a path prefix — so its own generated links/
     * redirects (e.g. the first-run "not installed yet" redirect to {@code wp-admin/install.php})
     * come back rooted at {@code /}, missing the prefix, and 404. Prepends fresh define()s
     * pointing at the path-prefixed public URL, replacing any real fqdn's WP_HOME/WP_SITEURL that
     * might already be present (LocalStack's own reachable URL always wins there — a real fqdn
     * wouldn't resolve locally anyway).
     *
     * <p>WP_HOME/WP_SITEURL alone aren't the whole fix, though: LocalStack's ALB emulation strips
     * {@code /_aws/elb/<name>} before forwarding to the container, so PHP's own {@code
     * $_SERVER['REQUEST_URI']} never sees it either. Anything WordPress builds FROM the raw
     * request rather than from {@code home_url()}/
     * {@code site_url()} — most importantly {@code wp-login.php}'s post-login {@code redirect_to}
     * target, generated from {@code REQUEST_URI} — comes out unprefixed. A browser following that
     * link lands on a bare path LocalStack's edge doesn't recognize as this app's ALB route, and
     * (having no other API shape to match it against) reads it path-style as an S3 bucket/key —
     * {@code /wp-admin/edit.php} becomes "bucket wp-admin, key edit.php" — and 400s with
     * NoSuchBucket. Prepending the prefix to {@code $_SERVER['REQUEST_URI']} itself, once, as early
     * as possible (WORDPRESS_CONFIG_EXTRA lands in wp-config.php, before wp-settings.php bootstraps
     * anything that reads it) fixes every one of these at the source instead of chasing each
     * REQUEST_URI-reading call site individually.
     */
    private static void mergeWordPressSiteUrl(ArrayNode environment, String publicUrl, String prefix) {
        String url = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        String escapedPrefix = prefix.replace("\\", "\\\\").replace("'", "\\'");
        String requestUriFix = String.format(
            "if (isset($_SERVER['REQUEST_URI']) && strpos($_SERVER['REQUEST_URI'], '%s') !== 0) { "
                + "$_SERVER['REQUEST_URI'] = '%s' . $_SERVER['REQUEST_URI']; } ",
            escapedPrefix, escapedPrefix);
        String directives = requestUriFix + String.format(
            "define('WP_HOME', '%s'); define('WP_SITEURL', '%s'); ", url, url);
        for (int i = 0; i < environment.size(); i++) {
            ObjectNode entry = asObject(environment.get(i));
            if (entry == null || !"WORDPRESS_CONFIG_EXTRA".equals(entry.path("Name").asText())) {
                continue;
            }
            String rest = entry.path("Value").asText("")
                .replaceAll("if \\(isset\\(\\$_SERVER\\['REQUEST_URI'\\]\\).*?REQUEST_URI'\\]; \\}\\s*", "")
                .replaceAll("define\\('WP_HOME',\\s*'[^']*'\\);\\s*", "")
                .replaceAll("define\\('WP_SITEURL',\\s*'[^']*'\\);\\s*", "");
            entry.put("Value", (directives + rest).trim());
            return;
        }
        ObjectNode entry = environment.addObject();
        entry.put("Name", "WORDPRESS_CONFIG_EXTRA");
        entry.put("Value", directives.trim());
    }

    private static ObjectNode asObject(JsonNode node) {
        return node instanceof ObjectNode objectNode ? objectNode : null;
    }
}
