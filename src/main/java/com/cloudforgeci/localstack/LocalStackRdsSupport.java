package com.cloudforgeci.localstack;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbSnapshotRequest;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DBSnapshot;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbSnapshotsRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RDS helpers for LocalStack integration-test verification.
 *
 * <p>Engines match {@code RdsFactory}: {@code postgres}/{@code postgresql}, {@code mysql},
 * {@code mariadb}. LocalStack can provision all three; native snapshots are Postgres-only
 * per LocalStack RDS docs.</p>
 */
public final class LocalStackRdsSupport implements AutoCloseable {

    /** Engines {@code RdsFactory} can provision (CloudForge deployment support). */
    public static final Set<String> CLOUDFORGE_ENGINES = Set.of(
        "postgres", "postgresql", "mysql", "mariadb");

    private final RdsClient rds;
    private final Duration pollInterval = Duration.ofSeconds(2);
    private final Duration snapshotTimeout = Duration.ofMinutes(10);

    public LocalStackRdsSupport() {
        this(LocalStackDeployer.resolveEndpoint(),
            System.getenv().getOrDefault("AWS_DEFAULT_REGION", "us-east-1"));
    }

    public LocalStackRdsSupport(String endpoint, String region) {
        this.rds = RdsClient.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();
    }

    /** All DB instances visible to the LocalStack RDS API. */
    public List<DBInstance> listDbInstances() {
        return rds.describeDBInstances(DescribeDbInstancesRequest.builder().build()).dbInstances();
    }

    /**
     * Prefer {@code CFC_LOCALSTACK_RDS_INSTANCE}, else first available instance whose engine
     * matches one of {@code engines} (normalized). Empty {@code engines} → any CloudForge engine.
     */
    public Optional<String> resolveInstanceId(String... engines) {
        String override = System.getenv("CFC_LOCALSTACK_RDS_INSTANCE");
        if (override != null && !override.isBlank()) {
            return Optional.of(override.trim());
        }
        Set<String> wanted = normalizeEngineFilter(engines);
        return listDbInstances().stream()
            .filter(db -> "available".equalsIgnoreCase(db.dbInstanceStatus()))
            .filter(db -> matchesEngineFilter(db.engine(), wanted))
            .map(DBInstance::dbInstanceIdentifier)
            .findFirst();
    }

    /** @deprecated use {@link #resolveInstanceId(String...)} with {@code "postgres"} */
    @Deprecated
    public Optional<String> resolvePostgresInstanceId() {
        return resolveInstanceId("postgres");
    }

    public Optional<DBInstance> findAvailableInstance(String... engines) {
        String id = resolveInstanceId(engines).orElse(null);
        if (id == null) {
            return Optional.empty();
        }
        return listDbInstances().stream()
            .filter(db -> id.equals(db.dbInstanceIdentifier()))
            .findFirst();
    }

    public boolean dbInstanceAvailable(String dbInstanceIdentifier) {
        return rds.describeDBInstances(DescribeDbInstancesRequest.builder()
                .dbInstanceIdentifier(dbInstanceIdentifier)
                .build())
            .dbInstances().stream()
            .anyMatch(db -> "available".equalsIgnoreCase(db.dbInstanceStatus()));
    }

    /**
     * LocalStack documents native create/restore snapshots for PostgreSQL only.
     * MySQL and MariaDB instances are provisionable but snapshot APIs are not supported.
     */
    public static boolean supportsNativeSnapshots(String engine) {
        String n = normalizeEngine(engine);
        return "postgres".equals(n) || "aurora-postgresql".equals(n);
    }

    public static boolean isCloudForgeEngine(String engine) {
        String n = normalizeEngine(engine);
        return CLOUDFORGE_ENGINES.contains(n) || "aurora-postgresql".equals(n) || "aurora-mysql".equals(n);
    }

    /**
     * Normalize engine id: trim/lower-case; map {@code postgresql} → {@code postgres}.
     */
    public static String normalizeEngine(String engine) {
        if (engine == null || engine.isBlank()) {
            return "";
        }
        String e = engine.trim().toLowerCase(Locale.ROOT);
        if ("postgresql".equals(e)) {
            return "postgres";
        }
        return e;
    }

    public DBSnapshot createSnapshotAndWait(String dbInstanceIdentifier, String snapshotId) {
        rds.createDBSnapshot(CreateDbSnapshotRequest.builder()
            .dbInstanceIdentifier(dbInstanceIdentifier)
            .dbSnapshotIdentifier(snapshotId)
            .build());
        Instant deadline = Instant.now().plus(snapshotTimeout);
        while (Instant.now().isBefore(deadline)) {
            List<DBSnapshot> snapshots = rds.describeDBSnapshots(DescribeDbSnapshotsRequest.builder()
                    .dbSnapshotIdentifier(snapshotId)
                    .build())
                .dbSnapshots();
            if (!snapshots.isEmpty()) {
                DBSnapshot snapshot = snapshots.get(0);
                String status = snapshot.status();
                if ("available".equalsIgnoreCase(status)) {
                    return snapshot;
                }
                if (status != null && status.toLowerCase(Locale.ROOT).contains("fail")) {
                    throw new IllegalStateException("Snapshot failed: " + snapshotId + " status=" + status);
                }
            }
            sleep(pollInterval);
        }
        throw new IllegalStateException("Timed out waiting for snapshot " + snapshotId);
    }

    @Override
    public void close() {
        rds.close();
    }

    static Set<String> normalizeEngineFilter(String... engines) {
        if (engines == null || engines.length == 0) {
            return CLOUDFORGE_ENGINES.stream()
                .map(LocalStackRdsSupport::normalizeEngine)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return Arrays.stream(engines)
            .map(LocalStackRdsSupport::normalizeEngine)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static boolean matchesEngineFilter(String engine, Set<String> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return isCloudForgeEngine(engine) || engine == null || engine.isBlank();
        }
        String n = normalizeEngine(engine);
        if (n.isEmpty()) {
            // LocalStack sometimes omits engine; treat as match when filtering CloudForge set
            return true;
        }
        if (wanted.contains(n)) {
            return true;
        }
        // Accept aurora-* when caller asked for base family
        if (wanted.contains("postgres") && n.contains("postgres")) {
            return true;
        }
        if (wanted.contains("mysql") && (n.equals("mysql") || n.equals("aurora-mysql"))) {
            return true;
        }
        return wanted.contains("mariadb") && n.contains("mariadb");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for RDS snapshot", e);
        }
    }
}
