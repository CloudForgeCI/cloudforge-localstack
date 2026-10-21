package com.cloudforgeci.localstack;

import com.cloudforge.core.local.LocalStackServiceCapability;
import com.cloudforge.core.local.LocalStackTierProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStackCapabilityProbeTest {

    @Test
    void infersUltimateWhenEfsAndBackupAvailable() {
        var caps = EnumSet.of(
            LocalStackServiceCapability.ECS,
            LocalStackServiceCapability.EFS,
            LocalStackServiceCapability.BACKUP);
        assertEquals(LocalStackTierProfile.ULTIMATE, LocalStackCapabilityProbe.inferTier(caps));
    }

    @Test
    void parsesHealthServicesIntoCapabilities() throws Exception {
        ObjectNode root = new ObjectMapper().createObjectNode();
        ObjectNode services = root.putObject("services");
        services.put("ecs", "running");
        services.put("rds", "available");
        services.put("efs", "available");
        services.put("backup", "available");

        var caps = LocalStackCapabilityProbe.capabilitiesFromHealth(root, new LinkedHashMap<>());
        assertTrue(caps.contains(LocalStackServiceCapability.ECS));
        assertTrue(caps.contains(LocalStackServiceCapability.RDS));
        assertTrue(caps.contains(LocalStackServiceCapability.EFS));
        assertTrue(caps.contains(LocalStackServiceCapability.BACKUP));
    }

    /** LocalStack's own {@code /_localstack/health} reports ELBv2/ALB support under the key
     *  {@code elasticloadbalancing} (its real AWS service name), not {@code elbv2} — mapping only
     *  the latter meant every deploy needing an ALB was blocked as "missing capability" even
     *  though LocalStack reported it as available. */
    @Test
    void mapsElasticLoadBalancingHealthKeyToElbv2Capability() {
        ObjectNode root = new ObjectMapper().createObjectNode();
        ObjectNode services = root.putObject("services");
        services.put("ecs", "available");
        services.put("elasticloadbalancing", "available");

        var caps = LocalStackCapabilityProbe.capabilitiesFromHealth(root, new LinkedHashMap<>());
        assertTrue(caps.contains(LocalStackServiceCapability.ELBV2));
    }
}
