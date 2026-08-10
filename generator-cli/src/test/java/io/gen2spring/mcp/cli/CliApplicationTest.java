package io.gen2spring.mcp.cli;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.ARTIFACT_PACKAGE_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.COMPILE_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_PARSE_FAILED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.VALIDATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.domain.error.GeneratorErrorCode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationOutcome;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.openapi.SpecificationAnalyzer;
import io.gen2spring.mcp.openapi.SwaggerOpenApiAnalyzer;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliApplicationTest {
    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void profilesPrintsOnlyStableSuccessJson() throws Exception {
        var result = run(application(unusedAnalyzer(), unusedGenerator()), "profiles");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        JsonNode json = JSON.readTree(result.stdout());
        JsonNode profiles = json.path("profiles");
        assertEquals(2, profiles.size());
        assertProfile(
                profiles.get(0),
                "spring-ai-2.0-java17-mvc-streamable",
                17,
                "eclipse-temurin:17.0.19_10-jre-noble@sha256:"
                        + "543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8");
        assertProfile(
                profiles.get(1),
                "spring-ai-2.0-java21-mvc-streamable",
                21,
                "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
                        + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64");
        assertTrue(result.stdout().endsWith("\n"));
        assertEquals(result.stdout(), run(application(unusedAnalyzer(), unusedGenerator()), "profiles").stdout());
    }

    @Test
    void compatibilityProfileConstructorExposesOnlyItsAdaptedProfile() throws Exception {
        var application = new CliApplication(
                new CommandLine(), new GenerationConfigurationReader(), unusedAnalyzer(), unusedGenerator(),
                CompatibilityProfile.p0(), JSON);

        JsonNode profiles = JSON.readTree(run(application, "profiles").stdout()).path("profiles");

        assertEquals(1, profiles.size());
        assertEquals("spring-ai-2.0-java21-mvc-streamable", profiles.get(0).path("id").asText());
    }

    @Test
    void inspectUsesTheAnalyzerAndPublishesOnlyANewStableJsonFile() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("weather.yaml"), simpleSpecification());
        Path output = safeTemp.resolve("analysis.json");
        var app = application(new SwaggerOpenApiAnalyzer(), unusedGenerator());

        var first = run(app, "inspect", "--spec", specification.toString(), "--output", output.toString());

        assertEquals(0, first.exitCode(), first.stderr());
        assertEquals("", first.stderr());
        assertEquals(safeTemp.resolve("analysis.json").toString(),
                JSON.readTree(first.stdout()).path("analysis").asText());
        JsonNode analysis = JSON.readTree(Files.readString(output));
        assertEquals("3.0.3", analysis.path("openApiVersion").asText());
        assertEquals("getForecast", analysis.path("operations").get(0).path("operationId").asText());

        String original = Files.readString(output);
        var second = run(app, "inspect", "--spec", specification.toString(), "--output", output.toString());
        assertEquals(2, second.exitCode());
        assertEquals(original, Files.readString(output));
        assertEquals("", second.stdout());
    }

    @Test
    void inspectUsesAndRemovesAnOwnerOnlyPrivateStagingDirectory() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("private-staging.yaml"), simpleSpecification());
        Path output = safeTemp.resolve("private-staging.json");
        AtomicReference<Path> stagingPath = new AtomicReference<>();
        AtomicReference<Set<PosixFilePermission>> permissions = new AtomicReference<>();
        var hook = new CliApplication.PublicationHook() {
            @Override
            public void afterStagingIdentityRecorded(Path staging) throws java.io.IOException {
                stagingPath.set(staging);
                permissions.set(Files.getPosixFilePermissions(staging.getParent()));
            }
        };

        var result = run(application(new SwaggerOpenApiAnalyzer(), unusedGenerator(), hook),
                "inspect", "--spec", specification.toString(), "--output", output.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertNotEquals(safeTemp, stagingPath.get().getParent());
        assertEquals(PosixFilePermissions.fromString("rwx------"), permissions.get());
        assertFalse(Files.exists(stagingPath.get()));
        assertFalse(Files.exists(stagingPath.get().getParent()));
        assertTrue(Files.isRegularFile(output));
    }

    @Test
    void inspectNeverDeletesThePublicTargetAfterLinkPublication() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("post-link-failure.yaml"), simpleSpecification());
        Path output = safeTemp.resolve("post-link-failure.json");
        AtomicReference<Path> stagingPath = new AtomicReference<>();
        var hook = new CliApplication.PublicationHook() {
            @Override
            public void afterTargetLinked(Path target, Path staging) throws java.io.IOException {
                stagingPath.set(staging);
                throw new java.io.IOException("forced post-link failure");
            }
        };

        var result = run(application(new SwaggerOpenApiAnalyzer(), unusedGenerator(), hook),
                "inspect", "--spec", specification.toString(), "--output", output.toString());

        assertEquals(2, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(Files.isRegularFile(output));
        assertFalse(Files.exists(stagingPath.get()));
    }

    @Test
    void inspectRejectsAStagingReplacementAndPreservesTheReplacement() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("staging-race.yaml"), simpleSpecification());
        Path output = safeTemp.resolve("staging-race.json");
        AtomicReference<Path> stagingPath = new AtomicReference<>();
        var hook = new CliApplication.PublicationHook() {
            @Override
            public void afterStagingIdentityRecorded(Path staging) throws java.io.IOException {
                stagingPath.set(staging);
                Files.move(staging, staging.resolveSibling(staging.getFileName() + ".original"));
                Files.writeString(staging, "replacement");
            }
        };

        var result = run(application(new SwaggerOpenApiAnalyzer(), unusedGenerator(), hook),
                "inspect", "--spec", specification.toString(), "--output", output.toString());

        assertEquals(2, result.exitCode());
        assertEquals("", result.stdout());
        assertFalse(Files.exists(output));
        assertNotEquals(safeTemp, stagingPath.get().getParent());
        assertEquals("replacement", Files.readString(stagingPath.get()));
        assertTrue(Files.isDirectory(stagingPath.get().getParent()));
    }

    @Test
    void inspectRejectsASameByteTargetReplacementAndPreservesIt() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("target-race.yaml"), simpleSpecification());
        Path output = safeTemp.resolve("target-race.json");
        AtomicReference<Path> stagingPath = new AtomicReference<>();
        AtomicReference<Path> originalTargetPath = new AtomicReference<>();
        var hook = new CliApplication.PublicationHook() {
            @Override
            public void afterTargetLinked(Path target, Path staging) throws java.io.IOException {
                stagingPath.set(staging);
                byte[] sameBytes = Files.readAllBytes(target);
                Path original = target.resolveSibling(target.getFileName() + ".original");
                originalTargetPath.set(original);
                Files.move(target, original);
                Files.write(target, sameBytes);
            }
        };

        var result = run(application(new SwaggerOpenApiAnalyzer(), unusedGenerator(), hook),
                "inspect", "--spec", specification.toString(), "--output", output.toString());

        assertEquals(2, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(Files.isRegularFile(output));
        assertFalse(Files.exists(stagingPath.get()));
        assertFalse(Files.exists(stagingPath.get().getParent()));
        assertFalse(Files.isSameFile(output, originalTargetPath.get()));
    }

    @Test
    void generateReadsStrictConfigurationAndReportsValidatedAndUnverifiedOutcomes() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("weather.yaml"), simpleSpecification());
        Path configuration = writeConfiguration();
        Path validatedRoot = safeTemp.resolve("validated");
        var validated = application(unusedAnalyzer(), (spec, request, output) -> new GenerationOutcome(
                output.toAbsolutePath().normalize(), output.resolveSibling(output.getFileName() + ".zip"),
                VALIDATED, "checksum"));

        var success = run(validated, "generate", "--spec", specification.toString(),
                "--config", configuration.toString(), "--output", validatedRoot.toString());

        assertEquals(0, success.exitCode(), success.stderr());
        JsonNode successJson = JSON.readTree(success.stdout());
        assertEquals(safeTemp.resolve("validated").toString(), successJson.path("project").asText());
        assertTrue(successJson.path("archive").asText().endsWith("validated.zip"));
        assertTrue(successJson.path("report").asText().endsWith("VALIDATION_REPORT.json"));
        assertEquals("VALIDATED", successJson.path("status").asText());

        Path unverifiedRoot = safeTemp.resolve("unverified");
        var unverified = application(unusedAnalyzer(), (spec, request, output) -> new GenerationOutcome(
                output.toAbsolutePath().normalize(), null, UNVERIFIED, "checksum"));
        var failedValidation = run(unverified, "generate", "--spec", specification.toString(),
                "--config", configuration.toString(), "--output", unverifiedRoot.toString());
        assertEquals(5, failedValidation.exitCode());
        assertEquals("UNVERIFIED", JSON.readTree(failedValidation.stdout()).path("status").asText());
        assertEquals("", failedValidation.stderr());
    }

    @Test
    void mapsUsageAndGeneratorFailuresWithoutLeakingArgumentsOrCauses() throws Exception {
        var usage = run(application(unusedAnalyzer(), unusedGenerator()),
                "generate", "--config", "TOP-SECRET", "--output", "out");
        assertEquals(2, usage.exitCode());
        assertFalse(usage.stderr().contains("TOP-SECRET"));
        assertEquals("", usage.stdout());

        Map<GeneratorErrorCode, Integer> expectedCodes = Map.ofEntries(
                Map.entry(GeneratorErrorCode.SPEC_FILE_UNSUPPORTED, 3),
                Map.entry(GeneratorErrorCode.SPEC_TOO_LARGE, 3),
                Map.entry(SPEC_PARSE_FAILED, 3),
                Map.entry(GeneratorErrorCode.SPEC_REFERENCE_UNRESOLVED, 3),
                Map.entry(GeneratorErrorCode.SPEC_VERSION_UNSUPPORTED, 3),
                Map.entry(GeneratorErrorCode.OPERATION_ID_DUPLICATED, 3),
                Map.entry(GeneratorErrorCode.OPERATION_UNSUPPORTED, 3),
                Map.entry(GeneratorErrorCode.VALIDATION_ARGUMENT_INVALID, 3),
                Map.entry(GeneratorErrorCode.SECRET_EXPOSURE_DETECTED, 3),
                Map.entry(GeneratorErrorCode.TARGET_PROFILE_NOT_FOUND, 2),
                Map.entry(GeneratorErrorCode.TARGET_COMBINATION_UNSUPPORTED, 2),
                Map.entry(SOURCE_GENERATION_FAILED, 4),
                Map.entry(GeneratorErrorCode.COMPILE_TIMEOUT, 5),
                Map.entry(COMPILE_FAILED, 5),
                Map.entry(GeneratorErrorCode.APPLICATION_CONTEXT_FAILED, 5),
                Map.entry(GeneratorErrorCode.MCP_INITIALIZE_FAILED, 5),
                Map.entry(GeneratorErrorCode.MCP_TOOLS_LIST_FAILED, 5),
                Map.entry(GeneratorErrorCode.MCP_TOOL_CALL_FAILED, 5),
                Map.entry(ARTIFACT_PACKAGE_FAILED, 6),
                Map.entry(GeneratorErrorCode.INTERNAL_ERROR, 4));
        for (var expected : expectedCodes.entrySet()) {
            assertFailureCode(expected.getKey(), expected.getValue());
        }
    }

    @Test
    void rejectsUnsafeInputAndOutputPathsBeforeCallingDependencies() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve("weather.yaml"), simpleSpecification());
        Path configuration = writeConfiguration();
        Path missingParentOutput = safeTemp.resolve("missing/analysis.json");
        var inspect = run(application(new SwaggerOpenApiAnalyzer(), unusedGenerator()),
                "inspect", "--spec", specification.toString(), "--output", missingParentOutput.toString());
        assertEquals(2, inspect.exitCode());
        assertFalse(Files.exists(missingParentOutput));

        Path existingProject = Files.createDirectory(safeTemp.resolve("existing"));
        boolean[] called = {false};
        var generate = run(application(unusedAnalyzer(), (spec, request, output) -> {
            called[0] = true;
            return new GenerationOutcome(output, null, UNVERIFIED, "checksum");
        }), "generate", "--spec", specification.toString(), "--config", configuration.toString(),
                "--output", existingProject.toString());
        assertEquals(2, generate.exitCode());
        assertFalse(called[0]);

        Path archivedProject = safeTemp.resolve("archived");
        Files.writeString(safeTemp.resolve("archived.zip"), "existing archive");
        var existingArchive = run(application(unusedAnalyzer(), (spec, request, output) -> {
            called[0] = true;
            return new GenerationOutcome(output, null, UNVERIFIED, "checksum");
        }), "generate", "--spec", specification.toString(), "--config", configuration.toString(),
                "--output", archivedProject.toString());
        assertEquals(2, existingArchive.exitCode());
        assertFalse(called[0]);

        Path linkedSpecification = safeTemp.resolve("linked.yaml");
        try {
            Files.createSymbolicLink(linkedSpecification, specification);
            var linkedInput = run(application(new SwaggerOpenApiAnalyzer(), unusedGenerator()),
                    "inspect", "--spec", linkedSpecification.toString(),
                    "--output", safeTemp.resolve("linked-analysis.json").toString());
            assertEquals(3, linkedInput.exitCode());
            assertFalse(Files.exists(safeTemp.resolve("linked-analysis.json")));
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            // Symbolic links are not available on every supported test filesystem.
        }

        Path physicalDirectory = Files.createDirectory(safeTemp.resolve("physical-directory"));
        Path physicalSpecification = Files.writeString(
                physicalDirectory.resolve("weather.yaml"), simpleSpecification());
        Path alias = safeTemp.resolve("directory-alias");
        try {
            Files.createSymbolicLink(alias, physicalDirectory);
            var ancestorInput = run(application(new SwaggerOpenApiAnalyzer(), unusedGenerator()),
                    "inspect", "--spec", alias.resolve(physicalSpecification.getFileName()).toString(),
                    "--output", safeTemp.resolve("ancestor-input-analysis.json").toString());
            assertEquals(3, ancestorInput.exitCode());
            assertFalse(Files.exists(safeTemp.resolve("ancestor-input-analysis.json")));

            var ancestorOutput = run(application(new SwaggerOpenApiAnalyzer(), unusedGenerator()),
                    "inspect", "--spec", specification.toString(),
                    "--output", alias.resolve("analysis.json").toString());
            assertEquals(2, ancestorOutput.exitCode());
            assertFalse(Files.exists(physicalDirectory.resolve("analysis.json")));
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            // Symbolic links are not available on every supported test filesystem.
        }
    }

    private void assertFailureCode(GeneratorErrorCode code, int expectedExitCode) throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path specification = Files.writeString(safeTemp.resolve(code + ".yaml"), simpleSpecification());
        Path configuration = writeConfiguration("generation-" + code + ".yaml");
        var app = application(unusedAnalyzer(), (spec, request, output) -> {
            throw GeneratorException.system(code, "SAFE_STAGE", "safe failure", new IllegalStateException("secret cause"));
        });

        var result = run(app, "generate", "--spec", specification.toString(),
                "--config", configuration.toString(), "--output", safeTemp.resolve("out-" + code).toString());

        assertEquals(expectedExitCode, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains(code.name()));
        assertTrue(result.stderr().contains("safe failure"));
        assertFalse(result.stderr().contains("secret cause"));
    }

    private CliApplication application(
            SpecificationAnalyzer analyzer,
            CliApplication.GenerationExecutor generationExecutor) {
        return application(analyzer, generationExecutor, CliApplication.PublicationHook.NONE);
    }

    private CliApplication application(
            SpecificationAnalyzer analyzer,
            CliApplication.GenerationExecutor generationExecutor,
            CliApplication.PublicationHook publicationHook) {
        return new CliApplication(
                new CommandLine(), new GenerationConfigurationReader(), analyzer, generationExecutor,
                CompatibilityProfileRegistry.defaults(), JSON, publicationHook);
    }

    private void assertProfile(JsonNode profile, String id, int javaVersion, String containerImage) {
        assertEquals(id, profile.path("id").asText());
        assertEquals("generator-spring-ai-2", profile.path("generatorModule").asText());
        assertEquals("spring-ai-2-v2", profile.path("templateVersion").asText());
        assertEquals("0.2.0", profile.path("runtimeVersion").asText());
        assertEquals("9.6.1", profile.path("gradleVersion").asText());
        assertEquals(containerImage, profile.path("containerImage").asText());
        JsonNode target = profile.path("target");
        assertEquals(javaVersion, target.path("javaVersion").asInt());
        assertEquals("4.1.0", target.path("springBootVersion").asText());
        assertEquals("2.0.0", target.path("springAiVersion").asText());
        assertEquals("GRADLE_KOTLIN", target.path("buildTool").asText());
        assertEquals("MVC", target.path("webStack").asText());
        assertEquals("SYNC", target.path("programmingModel").asText());
        assertEquals("STREAMABLE_HTTP", target.path("transport").asText());
    }

    private SpecificationAnalyzer unusedAnalyzer() {
        return (path, maxBytes) -> new SpecificationAnalyzer.AnalysisResult(emptyDocument(), new byte[0]);
    }

    private CliApplication.GenerationExecutor unusedGenerator() {
        return (specification, request, output) -> {
            throw new AssertionError("generator must not be called");
        };
    }

    private OpenApiDocument emptyDocument() {
        return new OpenApiDocument("3.0.3", "checksum", "yaml", URI.create("https://example.test"),
                List.of(), Map.of(), List.of());
    }

    private Result run(CliApplication application, String... args) {
        var stdoutBytes = new ByteArrayOutputStream();
        var stderrBytes = new ByteArrayOutputStream();
        var stdout = new PrintWriter(stdoutBytes, true, StandardCharsets.UTF_8);
        var stderr = new PrintWriter(stderrBytes, true, StandardCharsets.UTF_8);
        int exitCode = application.run(args, stdout, stderr);
        stdout.flush();
        stderr.flush();
        return new Result(exitCode, stdoutBytes.toString(StandardCharsets.UTF_8),
                stderrBytes.toString(StandardCharsets.UTF_8));
    }

    private Path writeConfiguration() throws Exception {
        return writeConfiguration("generation.yaml");
    }

    private Path writeConfiguration(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/config/weather-generation.yaml")) {
            if (input == null) {
                throw new IllegalStateException("missing configuration fixture");
            }
            return Files.write(tempDir.toRealPath().resolve(name), input.readAllBytes());
        }
    }

    private String simpleSpecification() {
        return """
                openapi: 3.0.3
                info:
                  title: Weather
                  version: 1.0.0
                servers:
                  - url: https://api.example.test
                paths:
                  /forecast:
                    get:
                      operationId: getForecast
                      summary: Get forecast
                      responses:
                        '200':
                          description: Success
                """;
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}
