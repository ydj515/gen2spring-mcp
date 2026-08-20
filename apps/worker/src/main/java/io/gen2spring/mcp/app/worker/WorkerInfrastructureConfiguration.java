package io.gen2spring.mcp.app.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.adapter.container.DockerCommandRunner;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetBucketAclRequest;
import software.amazon.awssdk.services.s3.model.Grant;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@Configuration(proxyBeanMethods = false)
class WorkerInfrastructureConfiguration {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    WorkerReadiness workerReadiness(
            DataSource dataSource,
            S3Client s3,
            DockerCommandRunner docker,
            WorkerProperties properties) {
        return new WorkerReadiness(List.of(
                databaseProbe(dataSource),
                storageProbe(s3, properties.storage().bucket()),
                dockerProbe(docker, properties)));
    }

    private WorkerReadiness.Probe databaseProbe(DataSource dataSource) {
        return () -> {
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(2)) {
                    throw new IllegalStateException();
                }
            }
        };
    }

    private WorkerReadiness.Probe storageProbe(S3Client s3, String bucket) {
        return () -> {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            List<Grant> grants = s3.getBucketAcl(
                            GetBucketAclRequest.builder().bucket(bucket).build())
                    .grants();
            if (grants == null || grants.stream().anyMatch(grant ->
                    grant == null || grant.grantee() == null || grant.grantee().uri() != null)) {
                throw new IllegalStateException();
            }
        };
    }

    private WorkerReadiness.Probe dockerProbe(DockerCommandRunner docker, WorkerProperties properties) {
        return () -> {
            List<String> base = List.of(
                    properties.docker().executable().toString(),
                    "--host",
                    "unix://" + properties.docker().socket());
            DockerCommandRunner.CommandResult info = docker.run(
                    command(base, "info", "--format", "{{json .SecurityOptions}}"),
                    PROBE_TIMEOUT);
            if (info.exitCode() != 0 || !stringArray(info.output()).contains("name=rootless")) {
                throw new IllegalStateException();
            }
            verifyImage(docker, base, properties.docker().generationImage(), "2");
            verifyImage(docker, base, properties.docker().importImage(), "1");
        };
    }

    private void verifyImage(
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

    private List<String> command(List<String> base, String... values) {
        List<String> command = new ArrayList<>(base);
        command.addAll(List.of(values));
        return List.copyOf(command);
    }

    private List<String> stringArray(String value) throws Exception {
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
