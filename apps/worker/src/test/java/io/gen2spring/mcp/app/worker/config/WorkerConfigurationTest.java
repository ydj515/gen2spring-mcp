package io.gen2spring.mcp.app.worker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.adapter.container.DockerCommandRunner;
import io.gen2spring.mcp.app.worker.infrastructure.readiness.WorkerReadiness;
import io.gen2spring.mcp.app.worker.infrastructure.readiness.WorkerStartupFailure;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

class WorkerConfigurationTest {
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
    void infrastructureReadinessRequiresDatabasePrivateBucketRootlessDockerAndExactImage() throws Exception {
        WorkerProperties properties = settings(
                Path.of("/run/user/10001/docker.sock"), PINNED, PINNED, "gen2spring-import");
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        FakeDocker commands = new FakeDocker(properties.docker().generationImage());

        WorkerReadiness readiness = new WorkerInfrastructureConfiguration()
                .workerReadiness(dataSource, s3, commands, properties);
        readiness.verify();

        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        assertThrows(WorkerStartupFailure.class, readiness::verify);
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());

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
        wrongProtocol.generationProtocol = "1";
        assertThrows(
                WorkerStartupFailure.class,
                () -> new WorkerInfrastructureConfiguration()
                        .workerReadiness(dataSource, s3, wrongProtocol, properties)
                        .verify());

        FakeDocker wrongImportProtocol = new FakeDocker(properties.docker().generationImage());
        wrongImportProtocol.importProtocol = "2";
        assertThrows(
                WorkerStartupFailure.class,
                () -> new WorkerInfrastructureConfiguration()
                        .workerReadiness(dataSource, s3, wrongImportProtocol, properties)
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
                        URI.create("http://garage:3900"),
                        "us-east-1",
                        "gen2spring-private",
                        Path.of("/run/secrets/garage-app-access-key"),
                        Path.of("/run/secrets/garage-app-secret-key"),
                        100L * 1024 * 1024),
                new WorkerProperties.Gateway(
                        URI.create("https://fetch-gateway:8443/internal/fetch"),
                        Path.of("/run/secrets/fetch-client.p12"),
                        Path.of("/run/secrets/fetch-client-password"),
                        Path.of("/run/secrets/fetch-ca.p12"),
                        Path.of("/run/secrets/fetch-ca-password")),
                new WorkerProperties.Encryption(
                        "key-1",
                        Map.of("key-1", Path.of("/run/secrets/import-target-key"))),
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
        private String generationProtocol = "2";
        private String importProtocol = "1";
        private int protocolCalls;

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
                return new CommandResult(0, "\"" + (protocolCalls++ == 0
                        ? generationProtocol : importProtocol) + "\"");
            }
            return new CommandResult(0, "[\"" + image + "\"]");
        }
    }
}
