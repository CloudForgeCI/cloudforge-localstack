package com.cloudforgeci.localstack;

import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalDeploymentNaming;
import com.cloudforge.core.local.LocalDeploymentPipeline;
import com.cloudforge.core.local.LocalDeploymentPipelineResult;
import com.cloudforge.core.local.LocalDeploymentRequest;
import com.cloudforge.core.local.TemplateAdaptationResult;

import java.io.IOException;
import java.nio.file.Path;

/**
 * LocalStack entry point for adapt-and-deploy.
 *
 * <p>Delegates to {@link LocalDeploymentPipeline} with LocalStack-specific wiring.</p>
 */
public final class LocalStackDeploymentPipeline {
    private static final LocalDeploymentPipeline PIPELINE = new LocalDeploymentPipeline(
        LocalStackTemplateAdapter.INSTANCE,
        LocalStackDeployer::new,
        null);

    private LocalStackDeploymentPipeline() {
    }

    /** @deprecated use {@link com.cloudforge.core.local.LocalDeploymentArtifactPaths#forTarget} via {@link LocalDeploymentRequest}. */
    @Deprecated
    public record ArtifactPaths(Path canonicalTemplate, Path localTemplate, Path adaptationReport) {
        public static ArtifactPaths inDirectory(Path outputDirectory, String contextStackName) {
            var paths = com.cloudforge.core.local.LocalDeploymentArtifactPaths.forTarget(
                outputDirectory, contextStackName, DeploymentTarget.LOCALSTACK);
            return new ArtifactPaths(
                paths.canonicalTemplate(), paths.localTemplate(), paths.adaptationReport());
        }
    }

    /** @deprecated use {@link LocalDeploymentRequest}. */
    @Deprecated
    public record DeployRequest(
            String localstackStackName,
            Path canonicalTemplate,
            Path localTemplate,
            Path adaptationReport,
            String contextStackName) {

        public static DeployRequest of(
                String contextStackName,
                Path canonicalTemplate,
                Path outputDirectory) {
            LocalDeploymentRequest request = LocalDeploymentRequest.forTarget(
                DeploymentTarget.LOCALSTACK,
                contextStackName,
                canonicalTemplate,
                outputDirectory);
            return new DeployRequest(
                request.localStackName(),
                request.canonicalTemplate(),
                request.localTemplate(),
                request.adaptationReport(),
                request.contextStackName());
        }

        LocalDeploymentRequest toCoreRequest() {
            return new LocalDeploymentRequest(
                DeploymentTarget.LOCALSTACK,
                localstackStackName,
                canonicalTemplate,
                localTemplate,
                adaptationReport,
                contextStackName);
        }
    }

    /** @deprecated use {@link LocalDeploymentPipelineResult}. */
    @Deprecated
    public record DeployResult(
            TemplateAdaptationResult adaptation,
            com.cloudforge.core.local.LocalDeployResult deployment) {
    }

    public static String toLocalstackStackName(String stackName) {
        return LocalDeploymentNaming.localStackName(stackName, DeploymentTarget.LOCALSTACK);
    }

    public static TemplateAdaptationResult adapt(DeployRequest request) throws IOException {
        return PIPELINE.adapt(request.toCoreRequest());
    }

    public static TemplateAdaptationResult adapt(LocalDeploymentRequest request) throws IOException {
        return PIPELINE.adapt(request);
    }

    public static DeployResult deploy(DeployRequest request) throws IOException {
        return fromCore(PIPELINE.deploy(request.toCoreRequest()));
    }

    public static LocalDeploymentPipelineResult deploy(LocalDeploymentRequest request)
            throws IOException {
        return PIPELINE.deploy(request);
    }

    public static LocalDeploymentRequest request(
            String contextStackName,
            Path canonicalTemplate,
            Path outputDirectory) {
        return LocalDeploymentRequest.forTarget(
            DeploymentTarget.LOCALSTACK,
            contextStackName,
            canonicalTemplate,
            outputDirectory);
    }

    private static DeployResult fromCore(LocalDeploymentPipelineResult result) {
        return new DeployResult(result.adaptation(), result.deployment());
    }
}
