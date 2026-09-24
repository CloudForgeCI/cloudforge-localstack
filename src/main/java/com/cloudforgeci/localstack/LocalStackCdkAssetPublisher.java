package com.cloudforgeci.localstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Publishes CDK synth assets from {@code cdk.out/*.assets.json} to S3 so {@code Custom::AWS}
 * Lambdas and other asset-backed resources can deploy — used by both {@code LocalStackDeployer}
 * (this module) and {@code AwsDirectDeployer} (cloudforge-api, which already depends on this
 * module for orchestrating the local-emulator deploy pipelines it dispatches to; despite the
 * class's LocalStack-flavored name, its logic is a plain S3 upload against whatever {@code
 * S3Client} it's given and is not LocalStack-specific).
 *
 * <p><b>Resolves CDK's {@code ${AWS::AccountId}}/{@code ${AWS::Partition}} pseudo-parameter
 * tokens</b> in {@code bucketName}/{@code objectKey} before using them as literal S3 API values —
 * an account-agnostic CDK synthesis (no concrete AWS account known at synth time, e.g. {@code
 * CloudForgeSynthesizer}'s deploy:create path) leaves these as unresolved literal token strings
 * in the asset manifest, which the S3 SDK then rejects outright
 * ({@code "Bucket name should not contain '$'"}) since a direct S3 API call has no CloudFormation
 * pseudo-parameter evaluator to fall back on — CloudFormation resolves them fine <em>inside</em>
 * the template body itself, but never for values an S3 client uses directly. {@code ${AWS::Region}}
 * is deliberately not substituted the same way: manifest destinations carry their own concrete
 * {@code region} field.</p>
 */
public final class LocalStackCdkAssetPublisher {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LocalStackCdkAssetPublisher() {
    }

    /**
     * @param accountId substituted for any {@code ${AWS::AccountId}} token in a destination's
     *     {@code bucketName}/{@code objectKey} — the real account id for a real-AWS caller (e.g.
     *     via {@code sts:GetCallerIdentity}), or LocalStack's well-known fixed test account
     *     ({@code 000000000000}) for a local-emulator caller.
     * @param createBucketIfMissing whether to self-create an asset destination bucket that
     *     doesn't exist yet, rather than require it already exist. {@code true} for a local
     *     emulator (LocalStack/MiniStack have no separate {@code cdk bootstrap} concept — this
     *     class creating the bucket on first use IS the bootstrap, for that target). {@code
     *     false} for real AWS: the destination bucket named here is CDK's own bootstrap staging
     *     bucket ({@code cdk-hnb659fds-assets-<account>-<region>}), which {@code cdk bootstrap}
     *     is supposed to create and own as a tracked CloudFormation resource (versioning,
     *     encryption, lifecycle rules, an SSL-only bucket policy). Self-creating a bare, untracked
     *     bucket with that exact name papers over an unbootstrapped account instead of surfacing
     *     it, and then permanently blocks a later {@code cdk bootstrap} from creating its
     *     own properly-configured copy of that bucket (CloudFormation's early-validation resource-
     *     existence check refuses to "adopt" a bucket the stack doesn't already own) — a
     *     connected account's first cross-account deploy attempt would otherwise pre-create
     *     this bucket before the account had been bootstrapped.
     * @throws IOException if the destination bucket doesn't exist and {@code createBucketIfMissing}
     *     is {@code false} — the actionable signal that the target account needs {@code cdk
     *     bootstrap} run against it before this deploy can proceed
     */
    public static void publish(
            Path cdkOutDirectory, String contextStackName, S3Client s3, String accountId,
            boolean createBucketIfMissing)
            throws IOException {
        if (cdkOutDirectory == null || contextStackName == null || contextStackName.isBlank()) {
            return;
        }
        Path manifest = cdkOutDirectory.resolve(contextStackName + ".assets.json");
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        JsonNode files = MAPPER.readTree(manifest.toFile()).path("files");
        if (!files.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> entries = files.properties().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            publishAsset(cdkOutDirectory, entry.getValue(), s3, accountId, createBucketIfMissing);
        }
    }

    private static void publishAsset(
            Path cdkOutDirectory, JsonNode asset, S3Client s3, String accountId,
            boolean createBucketIfMissing)
            throws IOException {
        JsonNode source = asset.path("source");
        String packaging = source.path("packaging").asText("file");
        String relativePath = source.path("path").asText("");
        if (relativePath.isBlank()) {
            return;
        }
        Path sourcePath = cdkOutDirectory.resolve(relativePath);
        if (!Files.exists(sourcePath)) {
            throw new IOException("CDK asset not found: " + sourcePath);
        }

        JsonNode destinations = asset.path("destinations");
        if (!destinations.isObject() || destinations.isEmpty()) {
            return;
        }
        JsonNode destination = destinations.elements().next();
        String bucket = resolveTokens(destination.path("bucketName").asText(), accountId);
        String objectKey = resolveTokens(destination.path("objectKey").asText(), accountId);
        if (bucket.isBlank() || objectKey.isBlank()) {
            return;
        }

        ensureBucket(s3, bucket, createBucketIfMissing);
        byte[] payload = "zip".equals(packaging)
            ? zipDirectory(sourcePath)
            : Files.readAllBytes(sourcePath);
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build(),
            RequestBody.fromBytes(payload));
    }

    /**
     * Substitutes {@code ${AWS::AccountId}} (the only pseudo-parameter these manifests contain in
     * {@code bucketName}/{@code objectKey} — see class javadoc)
     * with a real value, leaving everything else untouched. Also handles the bare
     * {@code ${AWS::Partition}} token some manifests carry in the (currently unused)
     * {@code assumeRoleArn} field, hardcoded to the standard {@code aws} partition — this
     * codebase has no GovCloud/China deploy path.
     */
    static String resolveTokens(String value, String accountId) {
        if (value == null) {
            return "";
        }
        String resolved = value;
        if (accountId != null && !accountId.isBlank()) {
            resolved = resolved.replace("${AWS::AccountId}", accountId);
        }
        return resolved.replace("${AWS::Partition}", "aws");
    }

    private static void ensureBucket(S3Client s3, String bucket, boolean createIfMissing)
            throws IOException {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            if (!createIfMissing) {
                throw new IOException(
                    "Asset bucket \"" + bucket + "\" doesn't exist yet. This AWS account hasn't "
                        + "been set up for AWS CDK deployments — someone with admin access to it "
                        + "needs to run \"cdk bootstrap\" once, directly, with their own AWS "
                        + "credentials. This is a one-time AWS CDK setup step, not a CloudForge "
                        + "permission or configuration issue.", e);
            }
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ignored) {
                // concurrent create
            }
        }
    }

    private static byte[] zipDirectory(Path directory) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes);
             var paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String entryName = directory.relativize(path).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(Files.readAllBytes(path));
                    zip.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
        return bytes.toByteArray();
    }
}
