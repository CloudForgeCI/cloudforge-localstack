package com.cloudforgeci.localstack;

import com.cloudforge.core.config.DeploymentConfig;
import com.cloudforge.core.enums.RuntimeType;
import com.cloudforge.core.interfaces.ApplicationSpec;
import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalDeploymentNaming;
import com.cloudforge.core.local.LocalHostPortConflictChecker;
import com.cloudforge.core.local.LocalStackCapabilitySnapshot;
import com.cloudforge.core.local.LocalStackServiceCapability;
import com.cloudforge.core.local.MiniStackCfnResourceCatalog;
import com.cloudforge.core.local.PreflightResult;
import com.cloudforge.core.local.PreflightSeverity;
import com.cloudforge.core.local.PreflightViolation;
import com.cloudforge.core.local.TemplateResourceScanner;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tier- and capability-aware LocalStack deploy preflight.
 */
public final class LocalStackDeployPreflight {

    private LocalStackDeployPreflight() {
    }

    public static PreflightResult validateForDeployment(
            DeploymentConfig config,
            ApplicationSpec spec,
            Path canonicalTemplate,
            LocalStackCapabilitySnapshot snapshot) throws IOException {
        return validateForDeployment(config, spec, canonicalTemplate, snapshot, null);
    }

    public static PreflightResult validateForDeployment(
            DeploymentConfig config,
            ApplicationSpec spec,
            Path canonicalTemplate,
            LocalStackCapabilitySnapshot snapshot,
            String stackName) throws IOException {
        List<PreflightViolation> violations = new ArrayList<>();
        if (snapshot == null || !snapshot.healthy()) {
            String detail = snapshot == null
                ? "probe returned null"
                : String.valueOf(snapshot.details().getOrDefault("error", "unhealthy"));
            violations.add(new PreflightViolation(
                PreflightSeverity.BLOCK,
                "LOCALSTACK_UNHEALTHY",
                "LocalStack is not healthy: " + detail,
                "Start LocalStack from the Interactive Deployer platform menu (--platform)."));
            return PreflightResult.of(DeploymentTarget.LOCALSTACK, violations);
        }

        Set<LocalStackServiceCapability> required = requiredCapabilities(config, spec, canonicalTemplate);
        for (LocalStackServiceCapability capability : required) {
            if (!snapshot.supports(capability)) {
                violations.add(new PreflightViolation(
                    PreflightSeverity.BLOCK,
                    "MISSING_CAPABILITY",
                    "LocalStack missing required capability " + capability
                        + " (tier=" + snapshot.tierProfile()
                        + ", edition=" + snapshot.edition()
                        + ", available=" + snapshot.capabilities() + ")",
                    capabilitySuggestion(capability)));
            }
        }

        JsonNode template = null;
        if (canonicalTemplate != null) {
            template = TemplateResourceScanner.readTemplate(canonicalTemplate);
            validateTemplateTier(template, snapshot, violations);
        }

        String localStackName = stackName == null || stackName.isBlank()
            ? null
            : LocalDeploymentNaming.localStackName(stackName, DeploymentTarget.LOCALSTACK);
        LocalHostPortConflictChecker.validate(
            DeploymentTarget.LOCALSTACK,
            snapshot.endpoint(),
            localStackName,
            spec,
            template,
            violations);

        return PreflightResult.of(DeploymentTarget.LOCALSTACK, violations);
    }

    static Set<LocalStackServiceCapability> requiredCapabilities(
            DeploymentConfig config,
            ApplicationSpec spec,
            Path canonicalTemplate) throws IOException {
        EnumSet<LocalStackServiceCapability> required = EnumSet.of(
            LocalStackServiceCapability.ECS,
            LocalStackServiceCapability.ELBV2);

        boolean needsRds = config != null && (
            Boolean.TRUE.equals(config.provisionDatabase)
                || (spec != null && spec.requiresDatabase()));
        if (needsRds || (canonicalTemplate != null && templateRequiresRds(canonicalTemplate))) {
            required.add(LocalStackServiceCapability.RDS);
        }

        if (config != null && config.runtime == RuntimeType.EC2) {
            required.add(LocalStackServiceCapability.EC2);
            required.add(LocalStackServiceCapability.AUTOSCALING);
        }

        return required;
    }

    private static boolean templateRequiresRds(Path canonicalTemplate) throws IOException {
        if (canonicalTemplate == null) {
            return false;
        }
        JsonNode template = TemplateResourceScanner.readTemplate(canonicalTemplate);
        return MiniStackCfnResourceCatalog.templateRequiresRds(template);
    }

    private static void validateTemplateTier(
            JsonNode template,
            LocalStackCapabilitySnapshot snapshot,
            List<PreflightViolation> violations) {
        Set<String> types = MiniStackCfnResourceCatalog.distinctTypes(template);
        if (types.stream().anyMatch(t -> t.startsWith("AWS::EFS::")) && !snapshot.keepEfsResources()) {
            violations.add(new PreflightViolation(
                PreflightSeverity.WARN,
                "EFS_ADAPTED",
                "Template includes EFS resources; LocalStack "
                    + snapshot.tierProfile()
                    + " will replace them with host bind mounts.",
                "Set LOCALSTACK_TIER_PROFILE=ultimate and enable EFS on Ultimate to keep native EFS."));
        }
        if (types.stream().anyMatch(t -> t.startsWith("AWS::Backup::")) && !snapshot.keepBackupResources()) {
            violations.add(new PreflightViolation(
                PreflightSeverity.WARN,
                "BACKUP_STRIPPED",
                "Template includes AWS Backup resources; LocalStack "
                    + snapshot.tierProfile()
                    + " will strip them during adaptation.",
                "Use LOCALSTACK_TIER_PROFILE=ultimate with backup capability, or disable automated backups."));
        }
    }

    private static String capabilitySuggestion(LocalStackServiceCapability capability) {
        return switch (capability) {
            case RDS -> "Enable RDS on your LocalStack tier, or set LOCALSTACK_CAPABILITIES=rds when health JSON is sparse.";
            case EC2, AUTOSCALING -> "EC2 runtime requires LocalStack EC2/Auto Scaling support (Base tier when probed).";
            case EFS -> "Ultimate tier required for native EFS; Base tier uses bind mounts.";
            case BACKUP -> "Ultimate tier required for AWS Backup resources.";
            default -> "Check LocalStack edition/token and /_localstack/health services map.";
        };
    }

    /** @deprecated prefer {@link #validateForDeployment} */
    @Deprecated
    public static LocalStackCapabilitySnapshot requireDefault(
            LocalStackServiceCapability... required) throws IOException {
        return require(LocalStackCapabilityProbe.probeDefault(), required);
    }

    public static LocalStackCapabilitySnapshot require(
            LocalStackCapabilitySnapshot snapshot,
            LocalStackServiceCapability... required) throws IOException {
        if (!snapshot.healthy()) {
            throw new IOException("LocalStack is not healthy at " + snapshot.endpoint()
                + ": " + snapshot.details().getOrDefault("error", "unknown"));
        }
        for (LocalStackServiceCapability capability : required) {
            if (!snapshot.supports(capability)) {
                throw new IOException(
                    "LocalStack missing required capability " + capability
                        + " (tier=" + snapshot.tierProfile()
                        + ", available=" + snapshot.capabilities() + ")");
            }
        }
        return snapshot;
    }

    /** @deprecated wraps the deprecated {@link #requireDefault}; prefer {@link #validateForDeployment} */
    @Deprecated
    public static LocalStackCapabilitySnapshot requireFargatePath() throws IOException {
        return requireDefault(
            LocalStackServiceCapability.ECS,
            LocalStackServiceCapability.ELBV2);
    }

    public static String missingCapabilities(
            LocalStackCapabilitySnapshot snapshot,
            LocalStackServiceCapability... required) {
        return java.util.Arrays.stream(required)
            .filter(cap -> !snapshot.supports(cap))
            .map(Enum::name)
            .collect(Collectors.joining(", "));
    }
}
