package io.gen2spring.mcp.adapter.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.dockerjava.api.model.ExposedPort;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorageFailure;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Testcontainers
@Timeout(30)
class S3ObjectStorageTest {
    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key-123456789";
    private static final String BUCKET = "private-hosted-objects";

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(
            "tobi312/minio:alpine-RELEASE.2025-07-23T15-54-02Z"
                    + "@sha256:d081402f706701b8f6ab6678d481036a673777d58ae7107652632b245b94a9dc"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private S3Client client;

    @BeforeEach
    void preparePrivateBucket() {
        client = client();
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (RuntimeException ignored) {
            // The static test container persists the bucket across test methods and restarts.
        }
        var objects = client.listObjectsV2(request -> request.bucket(BUCKET)).contents();
        if (!objects.isEmpty()) {
            client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(BUCKET)
                    .delete(Delete.builder()
                            .objects(objects.stream()
                                    .map(value -> ObjectIdentifier.builder().key(value.key()).build())
                                    .toList())
                            .build())
                    .build());
        }
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void storesReadsAndDeletesObjectsWithoutAnonymousAccess() throws Exception {
        S3ObjectStorage storage = new S3ObjectStorage(client, BUCKET, 1024);
        ObjectKey key = ObjectKey.parse(
                "specifications/80782e7c-337d-4d4d-bd4d-ad478359563c/source");
        byte[] body = "openapi: 3.1.0\n".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(body);

        StoredObject stored = storage.put(
                key,
                new ByteArrayInputStream(body),
                body.length,
                sha256,
                "application/yaml");

        assertEquals(new StoredObject(key, body.length, sha256, "application/yaml"), stored);
        try (StoredObjectContent content = storage.get(key)) {
            assertEquals(body.length, content.size());
            assertEquals(sha256, content.sha256());
            assertEquals("application/yaml", content.contentType());
            assertArrayEquals(body, content.body().readAllBytes());
        }

        HttpResponse<Void> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(endpoint().resolve("/" + BUCKET + "/" + key.value()))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(403, anonymous.statusCode());

        storage.delete(key);
        assertFailure(
                ObjectStorageFailure.Code.NOT_FOUND,
                "Stored object is unavailable",
                () -> storage.get(key));
    }

    @Test
    void rejectsSizeChecksumAndReadBoundViolationsWithoutPublishingContent() {
        S3ObjectStorage storage = new S3ObjectStorage(client, BUCKET, 32);
        ObjectKey key = ObjectKey.parse(
                "artifacts/1a803410-a22a-4bc6-b951-7dbc301ae800/archive");
        byte[] body = "bounded-body".getBytes(StandardCharsets.UTF_8);

        assertFailure(ObjectStorageFailure.Code.REJECTED, "Stored object write was rejected",
                () -> storage.put(key, new ByteArrayInputStream(body), body.length + 1,
                        sha256(body), "application/zip"));
        assertFailure(ObjectStorageFailure.Code.REJECTED, "Stored object write was rejected",
                () -> storage.put(key, new ByteArrayInputStream(body), body.length,
                        "f".repeat(64), "application/zip"));
        assertFailure(ObjectStorageFailure.Code.REJECTED, "Stored object write was rejected",
                () -> storage.put(key, new ByteArrayInputStream(new byte[33]), 33,
                        sha256(new byte[33]), "application/zip"));

        client.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(key.value())
                        .contentType("application/zip")
                        .metadata(java.util.Map.of("sha256", sha256(new byte[33])))
                        .build(),
                RequestBody.fromBytes(new byte[33]));
        assertFailure(ObjectStorageFailure.Code.REJECTED, "Stored object read was rejected",
                () -> storage.get(key));
    }

    @Test
    void preservesPrivateObjectsAcrossAServiceRestart() throws Exception {
        S3ObjectStorage storage = new S3ObjectStorage(client, BUCKET, 1024);
        ObjectKey key = ObjectKey.parse(
                "artifacts/1a803410-a22a-4bc6-b951-7dbc301ae800/report");
        byte[] body = "{\"status\":\"VALIDATED\"}".getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(body), body.length, sha256(body), "application/json");
        client.close();

        MINIO.getDockerClient().restartContainerCmd(MINIO.getContainerId()).withTimeout(5).exec();
        client = awaitClient();

        try (StoredObjectContent content = new S3ObjectStorage(client, BUCKET, 1024).get(key)) {
            assertArrayEquals(body, content.body().readAllBytes());
        }
    }

    @Test
    void rejectsAReadableBucketPolicyDuringReadiness() {
        assertDoesNotThrow(() -> MinioPrivateBucketProbe.verify(client, BUCKET));
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",
                "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}
                """.formatted(BUCKET);
        client.putBucketPolicy(PutBucketPolicyRequest.builder().bucket(BUCKET).policy(policy).build());
        try {
            assertThrows(IllegalStateException.class, () -> MinioPrivateBucketProbe.verify(client, BUCKET));
        } finally {
            client.deleteBucketPolicy(DeleteBucketPolicyRequest.builder().bucket(BUCKET).build());
        }
    }

    private S3Client awaitClient() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        HttpClient healthClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<Void> response = healthClient.send(
                        HttpRequest.newBuilder(endpoint().resolve("/minio/health/ready"))
                                .timeout(Duration.ofSeconds(1))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    S3Client candidate = client();
                    candidate.headBucket(request -> request.bucket(BUCKET));
                    return candidate;
                }
            } catch (Exception failure) {
                lastFailure = failure;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("MinIO did not become ready after restart", lastFailure);
    }

    private S3Client client() {
        return S3Client.builder()
                .endpointOverride(endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private URI endpoint() {
        var bindings = MINIO.getDockerClient()
                .inspectContainerCmd(MINIO.getContainerId())
                .exec()
                .getNetworkSettings()
                .getPorts()
                .getBindings()
                .get(ExposedPort.tcp(9000));
        if (bindings == null) {
            throw new IllegalStateException("MinIO port binding is unavailable");
        }
        String hostPort = null;
        for (var binding : bindings) {
            if (binding != null && (hostPort == null || "0.0.0.0".equals(binding.getHostIp()))) {
                hostPort = binding.getHostPortSpec();
            }
        }
        if (hostPort == null) {
            throw new IllegalStateException("MinIO port binding is unavailable");
        }
        return URI.create("http://" + MINIO.getHost() + ":" + hostPort);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private void assertFailure(ObjectStorageFailure.Code code, String message, Runnable action) {
        ObjectStorageFailure failure = assertThrows(ObjectStorageFailure.class, action::run);
        assertEquals(code, failure.code());
        assertEquals(message, failure.getMessage());
    }
}
