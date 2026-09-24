package com.cloudforgeci.localstack;

import com.cloudforge.core.local.DockerEmulatorSupport;
import com.cloudforge.core.local.LocalEmulatorDefaults;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * PostgreSQL listener used by LocalStack ECS workloads when LocalStack models an
 * RDS instance but does not provide a task-reachable database endpoint.
 *
 * <p>The companion is intentionally target-owned: it is never part of a canonical
 * AWS template and is reachable only on the CloudForge emulator Docker network.</p>
 */
final class LocalStackPostgresCompanion {
    static final String CONTAINER_NAME = "cfc-localstack-postgres";
    static final String HOSTNAME = CONTAINER_NAME;
    static final int PORT = 5432;
    private static final String IMAGE = "postgres:16";
    private static final Duration START_TIMEOUT = Duration.ofMinutes(2);

    private LocalStackPostgresCompanion() {
    }

    static void ensureRunning() throws IOException {
        DockerEmulatorSupport.ensureNetwork(LocalEmulatorDefaults.DOCKER_NETWORK);
        if (DockerEmulatorSupport.containerExists(CONTAINER_NAME)) {
            if (!DockerEmulatorSupport.isContainerRunning(CONTAINER_NAME)) {
                DockerEmulatorSupport.startExistingContainer(CONTAINER_NAME);
            }
        } else {
            DockerEmulatorSupport.pullImage(IMAGE);
            DockerEmulatorSupport.runDetached(List.of(
                "run", "--detach",
                "--name", CONTAINER_NAME,
                "--network", LocalEmulatorDefaults.DOCKER_NETWORK,
                "--hostname", HOSTNAME,
                "--env", "POSTGRES_USER=postgres",
                "--env", "POSTGRES_DB=postgres",
                // Roles and databases are created per deployed application. Trust is
                // safe here because this listener has no published host port and is
                // limited to the disposable emulator network.
                "--env", "POSTGRES_HOST_AUTH_METHOD=trust",
                IMAGE));
        }
        waitUntilReady();
    }

    static void remove() throws IOException {
        DockerEmulatorSupport.removeContainer(CONTAINER_NAME);
    }

    static void ensureDatabase(String username, String database) throws IOException {
        requireSafeIdentifier("username", username);
        requireSafeIdentifier("database", database);
        ensureRunning();
        String administrativeUser = resolveAdministrativeUser(username);
        String quotedUser = quoteIdentifier(username);
        DockerEmulatorSupport.run(List.of(
            "docker", "exec", CONTAINER_NAME,
            "psql", "--username", administrativeUser, "--dbname", "postgres",
            "--set", "ON_ERROR_STOP=1",
            "--command",
            "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
                + username + "') THEN CREATE ROLE " + quotedUser + " LOGIN; END IF; END $$;"),
            Duration.ofMinutes(1));

        List<String> exists = DockerEmulatorSupport.capture(List.of(
            "docker", "exec", CONTAINER_NAME,
            "psql", "--username", administrativeUser, "--dbname", "postgres",
            "--tuples-only", "--no-align",
            "--command", "SELECT 1 FROM pg_database WHERE datname = '" + database + "';"));
        if (exists.stream().noneMatch("1"::equals)) {
            DockerEmulatorSupport.run(List.of(
                "docker", "exec", CONTAINER_NAME,
                "createdb", "--username", administrativeUser, "--owner", username, database),
                Duration.ofMinutes(1));
        }
    }

    static String hostname() {
        return HOSTNAME;
    }

    private static void waitUntilReady() throws IOException {
        Instant deadline = Instant.now().plus(START_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                DockerEmulatorSupport.run(List.of(
                    "docker", "exec", CONTAINER_NAME,
                    "pg_isready", "--username", "postgres", "--dbname", "postgres"),
                    Duration.ofSeconds(5));
                return;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for LocalStack PostgreSQL companion", e);
                }
            }
        }
        throw new IOException("Timed out waiting for LocalStack PostgreSQL companion");
    }

    /**
     * Prefers the managed companion's {@code postgres} account, but falls back to the
     * application user, which older manually created LocalStack PostgreSQL setups used as the
     * initial superuser, so those can be migrated in place.
     */
    private static String resolveAdministrativeUser(String applicationUser) throws IOException {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add("postgres");
        candidates.add(applicationUser);
        String configured = configuredBootstrapUser();
        if (configured != null) candidates.add(configured);
        for (String candidate : candidates) {
            try {
                DockerEmulatorSupport.run(List.of(
                    "docker", "exec", CONTAINER_NAME,
                    "psql", "--username", candidate, "--dbname", "postgres",
                    "--command", "SELECT 1;"), Duration.ofSeconds(10));
                return candidate;
            } catch (IOException ignored) {
                // Try the existing application's original bootstrap role next.
            }
        }
        throw new IOException("LocalStack PostgreSQL companion has neither the managed postgres "
            + "role nor the application bootstrap role " + applicationUser);
    }

    private static String configuredBootstrapUser() throws IOException {
        for (String line : DockerEmulatorSupport.capture(List.of(
                "docker", "inspect", "--format", "{{range .Config.Env}}{{println .}}{{end}}", CONTAINER_NAME))) {
            if (line.startsWith("POSTGRES_USER=")) {
                String value = line.substring("POSTGRES_USER=".length()).trim();
                if (value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) return value;
            }
        }
        return null;
    }

    private static void requireSafeIdentifier(String label, String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("LocalStack PostgreSQL " + label
                + " must be a PostgreSQL identifier: " + value);
        }
    }

    private static String quoteIdentifier(String value) {
        return '"' + value + '"';
    }
}
