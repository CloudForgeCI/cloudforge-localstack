package com.cloudforgeci.localstack;

import com.cloudforge.core.local.AbstractLocalEmulatorRuntime;
import com.cloudforge.core.local.LocalEmulatorPaths;
import com.cloudforge.core.local.LocalEmulatorSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts and stops the LocalStack Docker container ({@code cfc-localstack}).
 */
public final class LocalStackEmulatorRuntime extends AbstractLocalEmulatorRuntime {

    private static final String ECS_TASK_CONTAINER_PREFIX = "ls-ecs-";

    public static final LocalStackEmulatorRuntime INSTANCE = new LocalStackEmulatorRuntime();

    private LocalStackEmulatorRuntime() {
        super(LocalEmulatorSpec.localstack());
    }

    @Override
    public void start() throws IOException {
        // LocalStack's ECS task containers outlive a stopped control plane. They cannot
        // reconnect to the new emulator and may retain the friendly Manager port.
        if (!isRunning()) {
            removeStaleEcsTasks();
        }
        super.start();
    }

    @Override
    protected List<String> dockerCreateArgs() throws IOException {
        String authToken = requiredAuthToken();
        Path volumeDir = LocalEmulatorPaths.localStackVolumeDir();
        Files.createDirectories(volumeDir);

        List<String> args = new ArrayList<>(baseDockerCreateArgs());
        // RDS_MYSQL_DOCKER=1: use real MySQL containers for engine=mysql (else MariaDB package).
        args.addAll(List.of(
            "-e", "LOCALSTACK_AUTH_TOKEN=" + authToken,
            "-e", "GATEWAY_LISTEN=0.0.0.0:4566",
            "-e", "DEBUG=0",
            // Cognito's hosted login page is served by the emulator. Allow the named
            // local application hosts that the edge routes to it for application OIDC, plus
            // localhost:8080, the origin Jenkins is served from. Requests from any other origin
            // are answered with 403.
            "-e", "EXTRA_CORS_ALLOWED_ORIGINS=http://localstack.cloudforge.localhost,"
                + "http://manager.cloudforge.localhost,http://jenkins.cloudforge.localhost,"
                + "http://gitlab.cloudforge.localhost,http://localhost:8080",
            "-e", "DOCKER_HOST=unix:///var/run/docker.sock",
            "-e", "RDS_MYSQL_DOCKER=1",
            "-v", volumeDir.toAbsolutePath() + ":/var/lib/localstack",
            spec().image()));
        return args;
    }

    @Override
    public void stop() throws IOException {
        // Remove (not only stop) so restart re-applies dockerCreateArgs env
        // (e.g. RDS_MYSQL_DOCKER=1) instead of starting a stale container.
        removeStaleEcsTasks();
        LocalStackPostgresCompanion.remove();
        com.cloudforge.core.local.DockerEmulatorSupport.removeContainer(containerName());
        System.out.println(spec().displayName() + " stopped");
    }

    private static void removeStaleEcsTasks() throws IOException {
        List<String> removed = com.cloudforge.core.local.DockerEmulatorSupport
            .removeContainersWithPrefix(ECS_TASK_CONTAINER_PREFIX);
        if (!removed.isEmpty()) {
            System.out.println("Removed stale LocalStack ECS task container(s): "
                + String.join(", ", removed));
        }
    }

    @Override
    protected void onStarted() {
        System.out.println("Volume: " + LocalEmulatorPaths.localStackVolumeDir().toAbsolutePath());
    }

    private static String requiredAuthToken() throws IOException {
        String token = System.getenv("LOCALSTACK_AUTH_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IOException(
                "LOCALSTACK_AUTH_TOKEN is required to start LocalStack "
                    + "(trial or paid token from localstack.cloud)");
        }
        return token;
    }
}
