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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
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
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Testcontainers
@Timeout(30)
class S3ObjectStorageTest {
    private static final String ACCESS_KEY = "GKtestaccesskey0000000000000000000000";
    private static final String SECRET_KEY = "test-secret-key-123456789";
    private static final String BOOTSTRAP_ACCESS_KEY = "GKbootstrapkey000000000000000000000";
    private static final String BOOTSTRAP_SECRET_KEY = "bootstrap-secret-key-123456789";
    private static final String BUCKET = "private-hosted-objects";

    @Container
    private static final GenericContainer<?> GARAGE = new GenericContainer<>(DockerImageName.parse(
            "dxflrs/garage:v2.4.1"
                    + "@sha256:9c96caa2612d3411acc5b0e6701fb238dbfba33e533a6d7d3d811a4b12d0d020"))
            .withEnv("GARAGE_RPC_SECRET", "a".repeat(64))
            .withEnv("GARAGE_DEFAULT_ACCESS_KEY", BOOTSTRAP_ACCESS_KEY)
            .withEnv("GARAGE_DEFAULT_SECRET_KEY", BOOTSTRAP_SECRET_KEY)
            .withEnv("GARAGE_DEFAULT_BUCKET", BUCKET)
            .withCopyToContainer(Transferable.of("""
                    metadata_dir = "/data/meta"
                    data_dir = "/data/data"
                    db_engine = "sqlite"
                    replication_factor = 1
                    rpc_bind_addr = "0.0.0.0:3901"
                    [s3_api]
                    s3_region = "garage"
                    api_bind_addr = "0.0.0.0:3900"
                    """), "/etc/garage.toml")
            .withCommand("/garage", "server", "--single-node", "--default-bucket")
            .withExposedPorts(3900)
            .waitingFor(Wait.forHttp("/").forPort(3900).forStatusCode(403));

    private S3Client client;

    @BeforeAll
    static void provisionScopedAppKey() throws Exception {
        assertEquals(0, GARAGE.execInContainer(
                "/garage", "key", "import", "--yes", "-n", "test-app", ACCESS_KEY, SECRET_KEY).getExitCode());
        assertEquals(0, GARAGE.execInContainer(
                "/garage", "bucket", "allow", "--read", "--write", "--key", ACCESS_KEY, BUCKET).getExitCode());
        assertEquals(0, GARAGE.execInContainer(
                "/garage", "bucket", "create", "other-hosted-objects").getExitCode());
    }

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

        GARAGE.getDockerClient().restartContainerCmd(GARAGE.getContainerId()).withTimeout(5).exec();
        client = awaitClient();

        try (StoredObjectContent content = new S3ObjectStorage(client, BUCKET, 1024).get(key)) {
            assertArrayEquals(body, content.body().readAllBytes());
        }
    }

    @Test
    void requiresAnAccessibleBucketDuringReadiness() {
        assertDoesNotThrow(() -> S3BucketReadinessProbe.verify(client, BUCKET));
        assertThrows(S3Exception.class, () -> S3BucketReadinessProbe.verify(client, "missing-bucket"));
        assertThrows(S3Exception.class, () -> S3BucketReadinessProbe.verify(client, "other-hosted-objects"));
        assertThrows(S3Exception.class,
                () -> client.createBucket(CreateBucketRequest.builder().bucket("other-hosted-objects").build()));
    }

    private S3Client awaitClient() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                S3Client candidate = client();
                candidate.headBucket(request -> request.bucket(BUCKET));
                return candidate;
            } catch (Exception failure) {
                lastFailure = failure;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Garage did not become ready after restart", lastFailure);
    }

    private S3Client client() {
        return S3Client.builder()
                .endpointOverride(endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .region(Region.of("garage"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private URI endpoint() {
        var bindings = GARAGE.getDockerClient()
                .inspectContainerCmd(GARAGE.getContainerId())
                .exec()
                .getNetworkSettings()
                .getPorts()
                .getBindings()
                .get(ExposedPort.tcp(3900));
        if (bindings == null) {
            throw new IllegalStateException("Garage port binding is unavailable");
        }
        String hostPort = null;
        for (var binding : bindings) {
            if (binding != null && (hostPort == null || "0.0.0.0".equals(binding.getHostIp()))) {
                hostPort = binding.getHostPortSpec();
            }
        }
        if (hostPort == null) {
            throw new IllegalStateException("Garage port binding is unavailable");
        }
        return URI.create("http://" + GARAGE.getHost() + ":" + hostPort);
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
