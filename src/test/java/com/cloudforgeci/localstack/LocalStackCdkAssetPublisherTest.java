package com.cloudforgeci.localstack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code resolveTokens} and the manifest-parsing/no-op paths — deterministic, no live S3 endpoint
 * needed. The actual upload path (this class's original, already-real-LocalStack-exercised
 * purpose via {@code LocalStackDeployer}) isn't re-proven here; see this class's own javadoc.
 */
class LocalStackCdkAssetPublisherTest {

    private static S3Client unreachableClient() {
        return S3Client.builder()
            .endpointOverride(URI.create("http://127.0.0.1:1"))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();
    }

    /** A minimal {@link S3Client} standing in for "this bucket doesn't exist" — every operation
     *  method on the SDK's client interfaces is a default method throwing {@code
     *  UnsupportedOperationException} unless overridden, so only {@code headBucket} needs one
     *  here; nothing else in the path under test calls anything else on this fake. */
    private static S3Client bucketDoesNotExistClient() {
        return new S3Client() {
            @Override
            public String serviceName() {
                return "s3";
            }

            @Override
            public void close() {
            }

            @Override
            public HeadBucketResponse headBucket(HeadBucketRequest request) {
                throw NoSuchBucketException.builder().message("The specified bucket does not exist").build();
            }
        };
    }

    @Test
    void resolveTokensSubstitutesAccountIdAndPartition() {
        assertEquals(
            "cdk-hnb659fds-assets-000000000000-us-east-1",
            LocalStackCdkAssetPublisher.resolveTokens(
                "cdk-hnb659fds-assets-${AWS::AccountId}-us-east-1", "000000000000"));
        assertEquals(
            "arn:aws:iam::123456789012:role/cdk-hnb659fds-file-publishing-role-123456789012-us-east-1",
            LocalStackCdkAssetPublisher.resolveTokens(
                "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/cdk-hnb659fds-file-publishing-role-${AWS::AccountId}-us-east-1",
                "123456789012"));
    }

    @Test
    void resolveTokensLeavesPlainValuesUnchanged() {
        assertEquals(
            "cdk-hnb659fds-assets-000000000000-us-east-1",
            LocalStackCdkAssetPublisher.resolveTokens(
                "cdk-hnb659fds-assets-000000000000-us-east-1", "999999999999"));
    }

    @Test
    void resolveTokensHandlesNullValueAndNullAccountId() {
        assertEquals("", LocalStackCdkAssetPublisher.resolveTokens(null, "000000000000"));
        assertEquals(
            "cdk-hnb659fds-assets-${AWS::AccountId}-us-east-1",
            LocalStackCdkAssetPublisher.resolveTokens(
                "cdk-hnb659fds-assets-${AWS::AccountId}-us-east-1", null));
    }

    @Test
    void publishIsANoOpWhenNoManifestExistsNextToTheTemplate(@TempDir Path cdkOut) {
        assertDoesNotThrow(() ->
            LocalStackCdkAssetPublisher.publish(
                cdkOut, "NoAssetsStack", unreachableClient(), "000000000000", true));
    }

    @Test
    void publishThrowsWhenAManifestReferencesAnAssetFileThatDoesNotExistOnDisk(@TempDir Path cdkOut)
            throws IOException {
        String manifest = """
            {
              "files": {
                "abc123": {
                  "source": { "path": "asset.abc123.zip", "packaging": "zip" },
                  "destinations": {
                    "current_account-current_region": {
                      "bucketName": "cdk-hnb659fds-assets-${AWS::AccountId}-us-east-1",
                      "objectKey": "abc123.zip"
                    }
                  }
                }
              }
            }
            """;
        Files.writeString(cdkOut.resolve("Stack.assets.json"), manifest);

        assertThrows(IOException.class, () ->
            LocalStackCdkAssetPublisher.publish(cdkOut, "Stack", unreachableClient(), "000000000000", true));
    }

    /** An AWS deploy against an account that was never {@code cdk bootstrap}ped must fail with
     *  an actionable message, never self-create CDK's
     *  own bootstrap-owned asset bucket — doing so leaves a bare, untracked bucket with that exact
     *  deterministic name sitting in the account, which then permanently blocks a later {@code
     *  cdk bootstrap} from creating its own properly-configured copy of it. */
    @Test
    void publishRefusesToSelfCreateTheAssetBucketWhenCreateBucketIfMissingIsFalse(@TempDir Path cdkOut)
            throws IOException {
        String manifest = """
            {
              "files": {
                "abc123": {
                  "source": { "path": "asset.abc123.zip", "packaging": "zip" },
                  "destinations": {
                    "current_account-current_region": {
                      "bucketName": "cdk-hnb659fds-assets-123456789012-us-east-1",
                      "objectKey": "abc123.zip"
                    }
                  }
                }
              }
            }
            """;
        Files.writeString(cdkOut.resolve("Stack.assets.json"), manifest);
        Files.writeString(cdkOut.resolve("asset.abc123.zip"), "not a real zip, just needs to exist");

        IOException thrown = assertThrows(IOException.class, () -> LocalStackCdkAssetPublisher.publish(
            cdkOut, "Stack", bucketDoesNotExistClient(), "123456789012", false));

        assertTrue(thrown.getMessage().contains("cdk bootstrap"), thrown.getMessage());
    }
}
