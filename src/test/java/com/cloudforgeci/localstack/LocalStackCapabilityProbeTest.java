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
}
