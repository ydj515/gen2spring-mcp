package io.gen2spring.mcp.app.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.adapter.container.DockerCommandRunner;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
import software.amazon.awssdk.services.s3.model.GetBucketAclRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

class WorkerApplicationTest {
    private static final String PINNED = "registry.example/gen2spring/runner@sha256:" + "a".repeat(64);

    @Test
    void rejectsRootDockerUnpinnedImagesAndUnsafeImportNetworksWithOneFixedFailure() {
        assertInvalid(() -> settings(
                Path.of("/var/run/docker.sock"), PINNED, PINNED, "gen2spring-import"));
        assertInvalid(() -> settings(
                Path.of("/run/user/10001/docker.sock"), "registry.example/runner:latest", PINNED,
                "gen2spring-import"));
        assertInvalid(() -> settings(
                Path.of("/run/user/10001/docker.sock"), PINNED, PINNED, "bridge"));
    }

    @Test
    void rejectsMissingGatewayMutualTlsMaterialWithoutLeakingConfiguration() {
        WorkerProperties valid = settings(
                Path.of("/run/user/10001/docker.sock"), PINNED, PINNED, "gen2spring-import");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new WorkerProperties(
                        valid.workerId(),
                        valid.pollInterval(),
                        valid.leaseDuration(),
                        valid.artifactRetention(),
                        valid.docker(),
                        valid.storage(),
                        new WorkerProperties.Gateway(
                                valid.gateway().endpoint(),
                                null,
                                valid.gateway().keyStorePasswordFile(),
                                valid.gateway().trustStore(),
                                valid.gateway().trustStorePasswordFile()),
                        valid.encryption(),
                        valid.limits()));

        assertEquals("Hosted worker configuration is invalid", failure.getMessage());
        assertFalse(failure.toString().contains("private-marker"));
    }

    @Test
    void readinessRunsEveryDependencyProbeAndFailsClosed() {
        AtomicInteger calls = new AtomicInteger();
        WorkerReadiness ready = new WorkerReadiness(List.of(
                calls::incrementAndGet,
                calls::incrementAndGet,
                calls::incrementAndGet));
        ready.verify();
        assertEquals(3, calls.get());

        WorkerReadiness failed = new WorkerReadiness(List.of(
                calls::incrementAndGet,
                () -> { throw new IllegalStateException("private-marker"); },
                calls::incrementAndGet));
        WorkerStartupFailure failure = assertThrows(WorkerStartupFailure.class, failed::verify);
        assertEquals("Hosted worker dependencies are unavailable", failure.getMessage());
        assertFalse(failure.toString().contains("private-marker"));
    }

    @Test
    void pollingStartsOnlyAfterReadinessAndStopsWithinTheBound() throws Exception {
        AtomicInteger readinessCalls = new AtomicInteger();
        AtomicInteger pollCalls = new AtomicInteger();
        WorkerLoop loop = new WorkerLoop(
                new WorkerReadiness(List.of(readinessCalls::incrementAndGet)),
                () -> {
                    pollCalls.incrementAndGet();
                    return false;
                },
                Duration.ofMillis(10));

        loop.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (pollCalls.get() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        loop.close();

        assertEquals(1, readinessCalls.get());
        assertTrue(pollCalls.get() >= 2);
        assertFalse(loop.running());

        WorkerLoop rejected = new WorkerLoop(
                new WorkerReadiness(List.of(() -> { throw new IllegalStateException("private-marker"); })),
                () -> { throw new AssertionError("polling started before readiness"); },
                Duration.ofMillis(10));
        assertThrows(WorkerStartupFailure.class, rejected::start);
        assertFalse(rejected.running());
    }

    @Test
    void infrastructureReadinessRequiresDatabasePrivateBucketRootlessDockerAndExactImage() throws Exception {
        WorkerProperties properties = settings(
                Path.of("/run/user/10001/docker.sock"), PINNED, PINNED, "gen2spring-import");
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(s3.getBucketAcl(any(GetBucketAclRequest.class)))
                .thenReturn(GetBucketAclResponse.builder().grants(List.of()).build());
        FakeDocker commands = new FakeDocker(properties.docker().generationImage());

        WorkerReadiness readiness = new WorkerInfrastructureConfiguration()
                .workerReadiness(dataSource, s3, commands, properties);
        readiness.verify();

        assertEquals(List.of("info", "image", "image", "image", "image"), commands.operations);

        FakeDocker notRootless = new FakeDocker(properties.docker().generationImage());
        notRootless.rootless = false;
        WorkerStartupFailure failure = assertThrows(
                WorkerStartupFailure.class,
                () -> new WorkerInfrastructureConfiguration()
                        .workerReadiness(dataSource, s3, notRootless, properties)
                        .verify());
        assertEquals("Hosted worker dependencies are unavailable", failure.getMessage());
        assertFalse(failure.toString().contains("private-marker"));

        FakeDocker wrongProtocol = new FakeDocker(properties.docker().generationImage());
        wrongProtocol.protocol = "2";
        assertThrows(
                WorkerStartupFailure.class,
                () -> new WorkerInfrastructureConfiguration()
                        .workerReadiness(dataSource, s3, wrongProtocol, properties)
                        .verify());
    }

    private void assertInvalid(Executable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertEquals("Hosted worker configuration is invalid", failure.getMessage());
        assertFalse(failure.toString().contains("private-marker"));
    }

    private WorkerProperties settings(
            Path socket,
            String generationImage,
            String importImage,
            String importNetwork) {
        return new WorkerProperties(
                "worker-01",
                Duration.ofMillis(250),
                Duration.ofSeconds(30),
                Duration.ofDays(30),
                new WorkerProperties.Docker(
                        Path.of("/usr/bin/docker"),
                        socket,
                        generationImage,
                        importImage,
                        importNetwork,
                        Path.of("/var/lib/gen2spring/work"),
                        Path.of("/dev/shm/gen2spring")),
                new WorkerProperties.Storage(
                        URI.create("http://minio:9000"),
                        "us-east-1",
                        "gen2spring-private",
                        Path.of("/run/secrets/minio-access-key"),
                        Path.of("/run/secrets/minio-secret-key"),
                        100L * 1024 * 1024),
                new WorkerProperties.Gateway(
                        URI.create("https://fetch-gateway:8443/internal/fetch"),
                        Path.of("/run/secrets/fetch-client.p12"),
                        Path.of("/run/secrets/fetch-client-password"),
                        Path.of("/run/secrets/fetch-ca.p12"),
                        Path.of("/run/secrets/fetch-ca-password")),
                new WorkerProperties.Encryption(
                        "key-1",
                        java.util.Map.of("key-1", Path.of("/run/secrets/import-target-key"))),
                new WorkerProperties.Limits(
                        2.0,
                        4L * 1024 * 1024 * 1024,
                        256,
                        Duration.ofMinutes(10)));
    }

    private static final class FakeDocker implements DockerCommandRunner {
        private final String image;
        private final List<String> operations = new ArrayList<>();
        private boolean rootless = true;
        private String protocol = "1";

        private FakeDocker(String image) {
            this.image = image;
        }

        @Override
        public CommandResult run(List<String> argv, Duration timeout) {
            String operation = argv.get(3);
            operations.add(operation);
            if (operation.equals("info")) {
                return new CommandResult(0, rootless ? "[\"name=rootless\"]" : "[]");
            }
            if (argv.contains("{{json (index .Config.Labels \"io.gen2spring.runner.protocol\")}}")) {
                return new CommandResult(0, "\"" + protocol + "\"");
            }
            return new CommandResult(0, "[\"" + image + "\"]");
        }
    }
}
