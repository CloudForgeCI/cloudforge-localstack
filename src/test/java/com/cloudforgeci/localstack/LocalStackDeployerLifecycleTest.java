package com.cloudforgeci.localstack;

import com.cloudforge.core.local.LocalSameApplicationStackReplacer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStackDeployerLifecycleTest {

    @Test
    void internalServerErrorIsTreatedAsALocalStackEmulationFailure() {
        CloudFormationException e = (CloudFormationException) CloudFormationException.builder()
            .statusCode(500)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalError").build())
            .build();
        assertTrue(LocalStackDeployer.isLocalStackInternalError(e));
    }

    @Test
    void errorCodeAloneIsEnoughEvenWithoutAMatchingStatusCode() {
        // Some LocalStack responses carry the InternalError code with a status the SDK doesn't
        // map to 500 -- either signal alone should be treated as LocalStack's own emulation
        // breaking, not a real deploy failure.
        CloudFormationException e = (CloudFormationException) CloudFormationException.builder()
            .statusCode(200)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalError").build())
            .build();
        assertTrue(LocalStackDeployer.isLocalStackInternalError(e));
    }

    @Test
    void aRealValidationFailureIsNotTreatedAsALocalStackEmulationFailure() {
        // A genuine business-logic failure (bad template, invalid parameter, etc.) must still
        // propagate and fail the deploy -- only LocalStack breaking internally should fall back.
        CloudFormationException e = (CloudFormationException) CloudFormationException.builder()
            .statusCode(400)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("ValidationError").build())
            .build();
        assertFalse(LocalStackDeployer.isLocalStackInternalError(e));
    }

    @Test
    void replaceSameApplicationStacksSkipsBlankApplicationId() {
        try (LocalStackDeployer deployer = new LocalStackDeployer("http://127.0.0.1:1", "us-east-1")) {
            LocalSameApplicationStackReplacer.Result result =
                deployer.replaceSameApplicationStacks(" ", "keep-localstack", Path.of("deployment-contexts"));
            assertTrue(result.deletedStacks().isEmpty());
            assertTrue(result.warning().isEmpty());
        }
    }

    @Test
    void verifyDeploymentFailsWhenStackMissing() {
        try (LocalStackDeployer deployer = new LocalStackDeployer("http://127.0.0.1:1", "us-east-1")) {
            org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> deployer.verifyDeployment("missing-localstack"));
        }
    }
}
