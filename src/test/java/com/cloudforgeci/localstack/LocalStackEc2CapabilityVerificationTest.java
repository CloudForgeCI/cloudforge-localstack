package com.cloudforgeci.localstack;

import com.cloudforge.core.local.LocalStackServiceCapability;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live LocalStack EC2/ASG capability smoke test.
 */
@Tag("localstack")
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "LOCALSTACK_AUTH_TOKEN", matches = ".+")
class LocalStackEc2CapabilityVerificationTest {

    @Test
    void probeReportsEc2AutoscalingOnBaseTier() {
        var snapshot = LocalStackCapabilityProbe.probeDefault();
        assertTrue(snapshot.healthy(), "LocalStack health: " + snapshot.details());
        assertTrue(snapshot.supports(LocalStackServiceCapability.EC2),
            "EC2 capability missing: " + snapshot.capabilities());
        assertTrue(snapshot.supports(LocalStackServiceCapability.AUTOSCALING),
            "Auto Scaling capability missing: " + snapshot.capabilities());
    }
}
