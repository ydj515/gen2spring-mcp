package io.gen2spring.mcp.app.worker.infrastructure.readiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.adapter.container.DockerCommandRunner;
import io.gen2spring.mcp.adapter.storage.MinioPrivateBucketProbe;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import software.amazon.awssdk.services.s3.S3Client;

public final class WorkerDependencyProbes {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkerDependencyProbes() {}

    public static WorkerReadiness.Probe database(DataSource dataSource) {
        return () -> {
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(2)) {
                    throw new IllegalStateException();
                }
            }
        };
    }

    public static WorkerReadiness.Probe storage(S3Client s3, String bucket) {
        return () -> MinioPrivateBucketProbe.verify(s3, bucket);
    }

    public static WorkerReadiness.Probe docker(
            DockerCommandRunner docker,
            Path executable,
            Path socket,
            String generationImage,
            String importImage) {
        return () -> {
            List<String> base = List.of(executable.toString(), "--host", "unix://" + socket);
            DockerCommandRunner.CommandResult info = docker.run(
                    command(base, "info", "--format", "{{json .SecurityOptions}}"),
                    PROBE_TIMEOUT);
            if (info.exitCode() != 0 || !stringArray(info.output()).contains("name=rootless")) {
                throw new IllegalStateException();
            }
            verifyImage(docker, base, generationImage, "2");
            verifyImage(docker, base, importImage, "1");
        };
    }

    private static void verifyImage(
            DockerCommandRunner docker,
            List<String> base,
            String expected,
            String expectedProtocol) throws Exception {
        DockerCommandRunner.CommandResult digest = docker.run(
                command(base, "image", "inspect", "--format", "{{json .RepoDigests}}", expected),
                PROBE_TIMEOUT);
        if (digest.exitCode() != 0 || !stringArray(digest.output()).contains(expected)) {
            throw new IllegalStateException();
        }
        DockerCommandRunner.CommandResult protocol = docker.run(
                command(
                        base,
                        "image",
                        "inspect",
                        "--format",
                        "{{json (index .Config.Labels \"io.gen2spring.runner.protocol\")}}",
                        expected),
                PROBE_TIMEOUT);
        JsonNode value = protocol.exitCode() == 0 ? JSON.readTree(protocol.output()) : null;
        if (value == null || !value.isTextual() || !expectedProtocol.equals(value.textValue())) {
            throw new IllegalStateException();
        }
    }

    private static List<String> command(List<String> base, String... values) {
        List<String> command = new ArrayList<>(base);
        command.addAll(List.of(values));
        return List.copyOf(command);
    }

    private static List<String> stringArray(String value) throws Exception {
        JsonNode root = JSON.readTree(value);
        if (root == null || !root.isArray()) {
            throw new IllegalStateException();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode entry : root) {
            if (!entry.isTextual()) {
                throw new IllegalStateException();
            }
            result.add(entry.textValue());
        }
        return List.copyOf(result);
    }
}
