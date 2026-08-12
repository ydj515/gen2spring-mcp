package io.gen2spring.mcp.adapter.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class RunnerImageContractTest {
    private static final String JDK_17 = "eclipse-temurin:17.0.19_10-jdk-noble@sha256:"
            + "f8857ccb2fe800666293d0da2526d162774e1314adfffb274fbadb877679d7a1";
    private static final String JDK_21 = "eclipse-temurin:21.0.11_10-jdk-noble@sha256:"
            + "a871f3e3caddad75608fd4531ed8bbca5cc42a27dc1da3ea3a2e554772b0ee15";
    private final Path repository = Path.of(System.getProperty("gen2spring.repositoryRoot"));

    @TempDir
    private Path temporaryDirectory;

    @Test
    void pinsTheOfflineNonRootRunnerSupplyChainAndSchema() throws Exception {
        String dockerfile = Files.readString(repository.resolve("deploy/hosted/runner/Dockerfile"));
        assertTrue(dockerfile.contains("ARG JDK17_IMAGE=" + JDK_17));
        assertTrue(dockerfile.contains("ARG JDK21_IMAGE=" + JDK_21));
        assertTrue(dockerfile.contains("USER 10001:10001"));
        assertTrue(dockerfile.lines().anyMatch(line -> line.equals(
                "ENV GRADLE_RO_DEP_CACHE=/opt/gen2spring/gradle-seed/caches")));
        assertTrue(dockerfile.contains(":apps:cli:installDist"));
        assertTrue(dockerfile.contains("weather-generation-spring-ai1-java17.yaml"));
        assertTrue(dockerfile.contains("weather-generation-spring-ai1-java21.yaml"));
        assertTrue(dockerfile.contains("weather-generation-java17.yaml"));
        assertTrue(dockerfile.contains("weather-generation.yaml"));
        String runtimeStage = dockerfile.substring(dockerfile.lastIndexOf("FROM "));
        assertFalse(runtimeStage.contains("apt-get install"));
        assertTrue(runtimeStage.contains(
                "rm -f /usr/bin/apt /usr/bin/apt-* /usr/bin/dpkg /usr/bin/dpkg-*;"));
        String dockerignore = Files.readString(repository.resolve(".dockerignore"));
        assertEquals("""
                **
                !build.gradle.kts
                !settings.gradle.kts
                !gradle.properties
                !gradlew
                !gradle/
                !gradle/libs.versions.toml
                !gradle/wrapper/
                !gradle/wrapper/gradle-wrapper.jar
                !gradle/wrapper/gradle-wrapper.properties
                !apps/
                !apps/cli/
                !apps/cli/build.gradle.kts
                !apps/cli/src/
                !apps/cli/src/main/
                !apps/cli/src/main/**
                !apps/cli/src/integrationTest/
                !apps/cli/src/integrationTest/resources/
                !apps/cli/src/integrationTest/resources/**
                !modules/
                !modules/domain/
                !modules/domain/build.gradle.kts
                !modules/domain/src/
                !modules/domain/src/main/
                !modules/domain/src/main/**
                !modules/application/
                !modules/application/build.gradle.kts
                !modules/application/src/
                !modules/application/src/main/
                !modules/application/src/main/**
                !modules/adapters/
                !modules/adapters/configuration/
                !modules/adapters/configuration/build.gradle.kts
                !modules/adapters/configuration/src/
                !modules/adapters/configuration/src/main/
                !modules/adapters/configuration/src/main/**
                !modules/adapters/openapi/
                !modules/adapters/openapi/build.gradle.kts
                !modules/adapters/openapi/src/
                !modules/adapters/openapi/src/main/
                !modules/adapters/openapi/src/main/**
                !modules/adapters/filesystem/
                !modules/adapters/filesystem/build.gradle.kts
                !modules/adapters/filesystem/src/
                !modules/adapters/filesystem/src/main/
                !modules/adapters/filesystem/src/main/**
                !modules/adapters/emitters/
                !modules/adapters/emitters/support/
                !modules/adapters/emitters/support/build.gradle.kts
                !modules/adapters/emitters/support/src/
                !modules/adapters/emitters/support/src/main/
                !modules/adapters/emitters/support/src/main/**
                !modules/adapters/emitters/spring-ai-1/
                !modules/adapters/emitters/spring-ai-1/build.gradle.kts
                !modules/adapters/emitters/spring-ai-1/src/
                !modules/adapters/emitters/spring-ai-1/src/main/
                !modules/adapters/emitters/spring-ai-1/src/main/**
                !modules/adapters/emitters/spring-ai-2/
                !modules/adapters/emitters/spring-ai-2/build.gradle.kts
                !modules/adapters/emitters/spring-ai-2/src/
                !modules/adapters/emitters/spring-ai-2/src/main/
                !modules/adapters/emitters/spring-ai-2/src/main/**
                !modules/adapters/validation/
                !modules/adapters/validation/build.gradle.kts
                !modules/adapters/validation/src/
                !modules/adapters/validation/src/main/
                !modules/adapters/validation/src/main/**
                !modules/bootstrap/
                !modules/bootstrap/build.gradle.kts
                !modules/bootstrap/src/
                !modules/bootstrap/src/main/
                !modules/bootstrap/src/main/**
                !deploy/
                !deploy/hosted/
                !deploy/hosted/runner/
                !deploy/hosted/runner/job-entrypoint.sh
                !deploy/hosted/runner/job-result.schema.json
                """, dockerignore);
        assertTrue(Files.readString(repository.resolve("deploy/hosted/runner/job-entrypoint.sh"))
                .contains("chmod -R u+rwX \"${work}/gradle-home\""));

        JsonNode schema = new ObjectMapper().readTree(
                repository.resolve("deploy/hosted/runner/job-result.schema.json").toFile());
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText());
        assertEquals(Set.of("outcome", "exitCode"), new ObjectMapper().convertValue(
                schema.path("required"), new com.fasterxml.jackson.core.type.TypeReference<Set<String>>() {}));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void entrypointPublishesOnlyTheAllowListedSuccessfulArtifacts() throws Exception {
        Path root = fixtureRoot(0);

        Process process = start(root);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        Path output = root.resolve("job/output");
        assertEquals(Set.of("archive.zip", "manifest.json", "validation-report.json", "result.json"),
                Files.list(output).map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("SUCCESS", new ObjectMapper().readTree(output.resolve("result.json").toFile())
                .path("outcome").asText());
        assertFalse(Files.exists(output.resolve("process.log")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void entrypointFailsClosedWithoutCopyingPrivateProcessOutput() throws Exception {
        Path root = fixtureRoot(5);

        Process process = start(root);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(5, process.exitValue());
        Path output = root.resolve("job/output");
        assertEquals(Set.of("result.json"),
                Files.list(output).map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        String result = Files.readString(output.resolve("result.json"));
        assertTrue(result.contains("\"outcome\":\"FAILED\""));
        assertFalse(result.contains("private process marker"));
    }

    private Process start(Path root) throws Exception {
        Path entrypoint = repository.resolve("deploy/hosted/runner/job-entrypoint.sh");
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", entrypoint.toString());
        builder.environment().put("GEN2SPRING_RUNNER_ROOT", root.toString());
        return builder.redirectErrorStream(true).start();
    }

    private Path fixtureRoot(int exitCode) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve("root-" + exitCode));
        Path input = Files.createDirectories(root.resolve("job/input"));
        Files.createDirectories(root.resolve("job/output"));
        Files.createDirectories(root.resolve("job/work"));
        Files.writeString(input.resolve("specification.yaml"), "openapi: 3.0.3\ninfo: {}\npaths: {}\n");
        Files.writeString(input.resolve("generation-config.json"), "{}");
        Files.createDirectories(root.resolve("tmp"));
        Files.createDirectories(root.resolve("opt/gen2spring/gradle-seed/caches/modules-2"));
        Files.createDirectories(root.resolve("opt/gen2spring/gradle-seed/wrapper/dists/gradle-9.6.1-bin/test"));
        Path cli = root.resolve("opt/gen2spring/openapi-mcp/bin/openapi-mcp");
        Files.createDirectories(cli.getParent());
        Files.writeString(cli, """
                #!/bin/sh
                set -eu
                output=""
                while [ "$#" -gt 0 ]; do
                  if [ "$1" = "--output" ]; then output="$2"; shift 2; else shift; fi
                done
                echo "private process marker" >&2
                if [ %d -ne 0 ]; then exit %d; fi
                mkdir -p "$output"
                printf '{}' > "$output/GENERATION_MANIFEST.json"
                printf '{}' > "$output/VALIDATION_REPORT.json"
                printf 'zip' > "$output.zip"
                """.formatted(exitCode, exitCode), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(cli, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return root;
    }
}
