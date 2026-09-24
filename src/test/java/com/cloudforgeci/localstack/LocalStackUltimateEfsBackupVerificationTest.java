package com.cloudforgeci.localstack;

import com.cloudforge.core.local.LocalStackServiceCapability;
import com.cloudforge.core.local.LocalStackTierProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live LocalStack Ultimate-tier EFS/Backup capability smoke test. Requires token + running emulator.
 */
@Tag("localstack")
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "LOCALSTACK_AUTH_TOKEN", matches = ".+")
class LocalStackUltimateEfsBackupVerificationTest {

    @Test
    void probeReportsEfsAndBackupOnUltimateTier() {
        var snapshot = LocalStackCapabilityProbe.probeDefault();
        assertTrue(snapshot.healthy(), "LocalStack health: " + snapshot.details());

        if (snapshot.tierProfile() == LocalStackTierProfile.ULTIMATE) {
            assertTrue(
                snapshot.supports(LocalStackServiceCapability.EFS),
                "EFS capability required on Ultimate — caps=" + snapshot.capabilities());
            assertTrue(
                snapshot.supports(LocalStackServiceCapability.BACKUP),
                "Backup capability required on Ultimate — caps=" + snapshot.capabilities());
            assertTrue(snapshot.keepEfsResources(), "Adapter should keep EFS on Ultimate");
            assertTrue(snapshot.keepBackupResources(), "Adapter should keep Backup on Ultimate");
        }
    }
}
