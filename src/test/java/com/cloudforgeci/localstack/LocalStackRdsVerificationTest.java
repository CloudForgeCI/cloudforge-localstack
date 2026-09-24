package com.cloudforgeci.localstack;

import com.cloudforge.core.local.LocalStackServiceCapability;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DBSnapshot;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live LocalStack RDS smoke test for every engine {@code RdsFactory} provisions:
 * {@code postgres}, {@code mysql}, {@code mariadb}.
 *
 * <p>Deploy contexts under {@code cfc-testing/deployment-contexts/}:
 * {@code CloudForgeManager-RDS-LocalStack.json},
 * {@code CloudForgeManager-RDS-MySQL-LocalStack.json},
 * {@code CloudForgeManager-RDS-MariaDB-LocalStack.json} (option 8).
 * Override instance id with {@code CFC_LOCALSTACK_RDS_INSTANCE}.</p>
 */
@Tag("localstack")
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "LOCALSTACK_AUTH_TOKEN", matches = ".+")
class LocalStackRdsVerificationTest {

    @Test
    void probeReportsRdsOnBaseTier() {
        var snapshot = LocalStackCapabilityProbe.probeDefault();
        assertTrue(snapshot.healthy(), "LocalStack health: " + snapshot.details());
        assertTrue(
            snapshot.supports(LocalStackServiceCapability.RDS),
            "RDS capability required — tier=" + snapshot.tierProfile()
                + " caps=" + snapshot.capabilities());
        assertTrue(snapshot.supportsRdsPath(), "supportsRdsPath should be true when RDS is present");
    }

    @ParameterizedTest(name = "available smoke for engine={0}")
    @ValueSource(strings = {"postgres", "mysql", "mariadb"})
    void availableInstanceForCloudForgeEngine(String engine) {
        var caps = LocalStackCapabilityProbe.probeDefault();
        assumeTrue(caps.healthy(), "LocalStack unhealthy: " + caps.details());
        assumeTrue(caps.supports(LocalStackServiceCapability.RDS), "RDS not available on this tier");

        try (LocalStackRdsSupport rds = new LocalStackRdsSupport()) {
            DBInstance db = rds.findAvailableInstance(engine).orElse(null);
            assumeTrue(db != null,
                "No available " + engine + " RDS instance — deploy CloudForgeManager-RDS-"
                    + ("postgres".equals(engine) ? "" : engine.toUpperCase() + "-")
                    + "LocalStack via option 8, or set CFC_LOCALSTACK_RDS_INSTANCE");

            assertTrue(rds.dbInstanceAvailable(db.dbInstanceIdentifier()));
            assertTrue(
                LocalStackRdsSupport.matchesEngineFilter(
                    db.engine(), LocalStackRdsSupport.normalizeEngineFilter(engine)),
                "engine=" + db.engine() + " for requested " + engine);
        }
    }

    @Test
    void createAndDescribePostgresSnapshot() {
        var caps = LocalStackCapabilityProbe.probeDefault();
        assumeTrue(caps.healthy(), "LocalStack unhealthy: " + caps.details());
        assumeTrue(caps.supports(LocalStackServiceCapability.RDS), "RDS not available on this tier");

        try (LocalStackRdsSupport rds = new LocalStackRdsSupport()) {
            DBInstance db = rds.findAvailableInstance("postgres").orElse(null);
            assumeTrue(db != null,
                "No available Postgres RDS instance — deploy CloudForgeManager-RDS-LocalStack "
                    + "via option 8, or set CFC_LOCALSTACK_RDS_INSTANCE");

            assertTrue(LocalStackRdsSupport.supportsNativeSnapshots(db.engine()),
                "Postgres family required for snapshot smoke, engine=" + db.engine());

            String dbId = db.dbInstanceIdentifier();
            assertTrue(rds.dbInstanceAvailable(dbId), "DB not available: " + dbId);

            String snapshotId = "cfc-rds-smoke-" + UUID.randomUUID().toString().substring(0, 8);
            DBSnapshot snapshot = rds.createSnapshotAndWait(dbId, snapshotId);
            assertNotNull(snapshot);
            assertEquals(snapshotId, snapshot.dbSnapshotIdentifier());
            assertTrue(
                "available".equalsIgnoreCase(snapshot.status()),
                "Snapshot status=" + snapshot.status());
            assertEquals(dbId, snapshot.dbInstanceIdentifier());
        }
    }

    @ParameterizedTest(name = "snapshots unsupported for engine={0}")
    @ValueSource(strings = {"mysql", "mariadb"})
    void documentsSnapshotUnsupportedForMysqlFamily(String engine) {
        assertFalse(LocalStackRdsSupport.supportsNativeSnapshots(engine),
            "LocalStack does not support native snapshots for " + engine
                + "; deploy + describe/available is the acceptance check for this engine");
    }
}
