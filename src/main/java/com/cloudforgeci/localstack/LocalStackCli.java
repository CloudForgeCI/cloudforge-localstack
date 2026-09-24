package com.cloudforgeci.localstack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** Non-interactive entry point for direct local LocalStack operations. */
public final class LocalStackCli {
    private LocalStackCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                "Usage: LocalStackCli <deploy|delete|verify> <stack-name> [canonical-template]");
        }

        String command = args[0];
        String stackName = args[1];
        try (LocalStackDeployer deployer = new LocalStackDeployer()) {
            switch (command) {
            case "deploy" -> {
                if (args.length != 3) {
                    throw new IllegalArgumentException("deploy requires the canonical template path");
                }
                Path canonical = Path.of(args[2]);
                String contextStackName = canonical.getFileName().toString()
                    .replace(".template.json", "");
                LocalStackDeploymentPipeline.DeployResult result =
                    LocalStackDeploymentPipeline.deploy(
                        LocalStackDeploymentPipeline.DeployRequest.of(
                            contextStackName,
                            canonical,
                            canonical.getParent()));
                System.out.println("adaptations=" + result.adaptation().adaptations().size());
                System.out.println("created=" + result.deployment().created());
                System.out.println("noOp=" + result.deployment().noOp());
                result.deployment().changes().forEach(change -> System.out.println(
                    change.action() + " " + change.resourceType() + " " + change.logicalResourceId()));
                result.deployment().outputs().forEach((key, value) ->
                    System.out.println("output." + key + "=" + value));
            }
            case "delete" -> deployer.delete(
                LocalStackDeploymentPipeline.toLocalstackStackName(stackName));
            case "verify" -> {
                String localStackName = LocalStackDeploymentPipeline.toLocalstackStackName(stackName);
                if (!deployer.stackExists(localStackName)) {
                    throw new IllegalStateException("Stack not found: " + localStackName);
                }
                var outputs = deployer.outputs(localStackName);
                outputs.forEach((key, value) -> System.out.println(key + "=" + value));
                String url = outputs.get("LocalStackLocalUrl");
                if (url != null && Boolean.parseBoolean(
                        System.getenv().getOrDefault("LOCALSTACK_HTTP_VERIFY", "true"))) {
                    System.out.println("httpStatus=" + waitForHttp(url));
                }
            }
                default -> throw new IllegalArgumentException("Unknown command: " + command);
            }
        }
    }

    private static int waitForHttp(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        Exception lastFailure = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                );
                if (response.statusCode() < 500) {
                    return response.statusCode();
                }
                lastFailure = new IllegalStateException(
                    "LocalStack endpoint returned HTTP " + response.statusCode());
            } catch (java.io.IOException e) {
                lastFailure = e;
            }
            Thread.sleep(Duration.ofSeconds(2));
        }
        throw new IllegalStateException(
            "LocalStack endpoint did not become ready within 3 minutes", lastFailure);
    }
}
