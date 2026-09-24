package com.cloudforgeci.localstack;

import com.cloudforge.core.config.DeploymentConfig;
import com.cloudforge.core.enums.RuntimeType;
import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalHostPortConflictChecker;
import com.cloudforge.core.local.LocalHostPortOccupant;
import com.cloudforge.core.local.LocalStackCapabilitySnapshot;
import com.cloudforge.core.local.LocalStackServiceCapability;
import com.cloudforge.core.local.LocalStackTierProfile;
import com.cloudforge.core.local.PreflightMode;
import com.cloudforge.core.local.PreflightResult;
import com.cloudforge.core.local.PreflightViolation;
import com.cloudforge.core.local.TemplateResourceScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStackDeployPreflightTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static LocalStackCapabilitySnapshot snapshot(Set<LocalStackServiceCapability> capabilities) {
        return new LocalStackCapabilitySnapshot(
            true,
            URI.create("http://localhost:4566"),
            LocalStackTierProfile.BASE,
            "base",
            "test",
            capabilities,
            Map.of());
    }

    @Test
    void blocksWhenLocalStackUnhealthy() throws Exception {
        LocalStackCapabilitySnapshot unhealthy = LocalStackCapabilitySnapshot.unavailable(
            URI.create("http://localhost:4566"),
            "connection refused");

        PreflightResult result = LocalStackDeployPreflight.validateForDeployment(
            new DeploymentConfig(), null, null, unhealthy);

        assertFalse(result.allowed(PreflightMode.ENFORCE));
        assertEquals("LOCALSTACK_UNHEALTHY", result.blockingViolations().getFirst().ruleId());
    }

    @Test
    void requiresEcsAndElbv2ForFargatePath() throws Exception {
        LocalStackCapabilitySnapshot missingElb = snapshot(EnumSet.of(LocalStackServiceCapability.ECS));

        PreflightResult result = LocalStackDeployPreflight.validateForDeployment(
            new DeploymentConfig(), null, null, missingElb);

        assertFalse(result.allowed(PreflightMode.ENFORCE));
        assertTrue(result.blockingViolations().stream()
            .anyMatch(v -> v.ruleId().equals("MISSING_CAPABILITY")
                && v.message().contains("ELBV2")));
    }

    @Test
    void requiresRdsWhenTemplateIncludesRds() throws Exception {
        ObjectNode template = MAPPER.createObjectNode();
        template.putObject("Resources")
            .putObject("Db")
            .put("Type", "AWS::RDS::DBInstance");
        Path canonical = tempDir.resolve("rds-stack.json");
        Files.writeString(canonical, MAPPER.writeValueAsString(template));

        LocalStackCapabilitySnapshot fargateOnly = snapshot(EnumSet.of(
            LocalStackServiceCapability.ECS,
            LocalStackServiceCapability.ELBV2));

        PreflightResult result = LocalStackDeployPreflight.validateForDeployment(
            new DeploymentConfig(), null, canonical, fargateOnly);

        assertFalse(result.allowed(PreflightMode.ENFORCE));
        assertTrue(result.blockingViolations().stream()
            .anyMatch(v -> v.message().contains("RDS")));
    }

    @Test
    void requiresEc2CapabilitiesForEc2Runtime() throws Exception {
        DeploymentConfig config = new DeploymentConfig();
        config.runtime = RuntimeType.EC2;

        LocalStackCapabilitySnapshot fargateOnly = snapshot(EnumSet.of(
            LocalStackServiceCapability.ECS,
            LocalStackServiceCapability.ELBV2,
            LocalStackServiceCapability.RDS));

        PreflightResult result = LocalStackDeployPreflight.validateForDeployment(
            config, null, null, fargateOnly);

        assertFalse(result.allowed(PreflightMode.ENFORCE));
        assertTrue(result.blockingViolations().stream()
            .anyMatch(v -> v.message().contains("EC2") || v.message().contains("AUTOSCALING")));
    }

    @Test
    void warnsWhenEfsPresentOnBaseTier() throws Exception {
        ObjectNode template = MAPPER.createObjectNode();
        template.putObject("Resources")
            .putObject("Fs")
            .put("Type", "AWS::EFS::FileSystem");
        Path canonical = tempDir.resolve("efs-stack.json");
        Files.writeString(canonical, MAPPER.writeValueAsString(template));

        LocalStackCapabilitySnapshot base = snapshot(EnumSet.of(
            LocalStackServiceCapability.ECS,
            LocalStackServiceCapability.ELBV2));

        PreflightResult result = LocalStackDeployPreflight.validateForDeployment(
            new DeploymentConfig(), null, canonical, base);

        assertTrue(result.allowed(PreflightMode.ENFORCE));
        assertEquals(1, result.warnings().size());
        assertEquals("EFS_ADAPTED", result.warnings().getFirst().ruleId());
    }

    /**
     * Asserts {@link LocalHostPortConflictChecker#validateAgainstOccupants}'s actual, documented
     * behavior for {@code DeploymentTarget.LOCALSTACK}: that method returns immediately for
     * LocalStack (see its own comment: LocalStack ECS publishes each task on an allocated host
     * port, so a shared container port is never an exclusive host-port reservation the way
     * MiniStack's is). The MiniStack-blocks case is covered separately by {@code
     * LocalHostPortConflictCheckerTest.blocksWhenDifferentStackUsesPort} in cloudforge-core; this
     * test asserts the LocalStack exemption specifically (matching cloudforge-core's own
     * {@code localstackAllowsDuplicateContainerPortsBecauseTasksUseAllocatedHostPorts}).
     */
    @Test
    void allowsHostPortReuseOnLocalStackBecauseTasksUseAllocatedHostPorts() throws Exception {
        ObjectNode template = MAPPER.createObjectNode();
        ObjectNode service = template.putObject("Resources").putObject("Service");
        service.put("Type", "AWS::ECS::Service");
        service.putObject("Properties")
            .putArray("LoadBalancers")
            .addObject()
            .put("ContainerPort", 3000);
        Path canonical = tempDir.resolve("metabase.json");
        Files.writeString(canonical, MAPPER.writeValueAsString(template));

        List<PreflightViolation> violations = new ArrayList<>();
        LocalHostPortConflictChecker.validateAgainstOccupants(
            DeploymentTarget.LOCALSTACK,
            "Metabase-Stack-localstack",
            null,
            TemplateResourceScanner.readTemplate(canonical),
            List.of(new LocalHostPortOccupant(
                "Grafana-Stack-localstack",
                3000,
                "stack output LocalStackApplicationUrl")),
            violations);
        PreflightResult result = PreflightResult.of(DeploymentTarget.LOCALSTACK, violations);

        assertTrue(result.allowed(PreflightMode.ENFORCE));
        assertTrue(violations.isEmpty());
    }
}
