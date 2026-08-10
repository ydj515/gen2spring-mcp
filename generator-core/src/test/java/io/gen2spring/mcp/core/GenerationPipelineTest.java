package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.ARTIFACT_PACKAGE_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.COMPILE_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_COMBINATION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_PROFILE_NOT_FOUND;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.FAILED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.SUCCESS;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.VALIDATED;
import static io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterSource.SERVER_SECRET;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.config.GenerationRequest.OperationSelection;
import io.gen2spring.mcp.domain.config.GenerationRequest.ParameterOverride;
import io.gen2spring.mcp.domain.config.GenerationRequest.ProjectCoordinates;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectFiles;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectValidator;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ObservedTool;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ProjectGenerator;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationReport;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationRequest;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStageResult;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.openapi.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.openapi.SpecificationAnalyzer;
import io.gen2spring.mcp.policy.ToolModelFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationPipelineTest {
    private static final String TOOL_NAME = "kma_weather_get_forecast";
    private static final String TOOL_DESCRIPTION = "Get the public weather forecast for a grid location.";
    private static final String SPECIFICATION = """
            openapi: 3.0.3
            info:
              title: Weather API
              version: 1.0.0
            servers:
              - url: https://weather.example.test
            paths:
              /forecast:
                get:
                  operationId: getForecast
                  summary: Get a weather forecast
                  security:
                    - serviceKey: []
                  parameters:
                    - name: serviceKey
                      in: query
                      required: true
                      schema:
                        type: string
                    - name: nx
                      in: query
                      required: true
                      schema:
                        type: integer
                    - name: tenantCredential
                      in: header
                      required: true
                      schema:
                        type: string
              /health:
                get:
                  operationId: getHealth
                  summary: Get service health
            components:
              securitySchemes:
                serviceKey:
                  type: apiKey
                  in: query
                  name: serviceKey
            """;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path specification;
    private Path safeTempDir;

    @BeforeEach
    void writeSpecification() throws IOException {
        safeTempDir = tempDir.toRealPath();
        specification = Files.writeString(safeTempDir.resolve("weather.yaml"), SPECIFICATION, UTF_8);
    }

    @Test
    void validationFailureKeepsAnUnverifiedDirectoryAndDoesNotCreateAZip() throws IOException {
        var validator = (GeneratedProjectValidator) validationRequest -> new ValidationReport(
                UNVERIFIED,
                List.of(new ValidationStageResult("COMPILE", FAILED, 10, 0, 1, "Compilation failed")),
                List.of());
        var pipeline = pipelineWithValidator(validator);

        var outcome = pipeline.generate(specification, weatherGenerationRequest(), safeTempDir.resolve("weather"));

        assertEquals(UNVERIFIED, outcome.validationStatus());
        assertTrue(Files.exists(outcome.projectRoot().resolve("GENERATION_MANIFEST.json")));
        assertTrue(Files.exists(outcome.projectRoot().resolve("VALIDATION_REPORT.json")));
        assertTrue(Files.exists(outcome.projectRoot().resolve("openapi/source.yaml")));
        assertFalse(Files.exists(safeTempDir.resolve("weather.zip")));
        JsonNode report = objectMapper.readTree(outcome.projectRoot().resolve("VALIDATION_REPORT.json").toFile());
        assertEquals("UNVERIFIED", report.path("status").asText());
        assertEquals("COMPILE", report.path("stages").get(0).path("stage").asText());
    }

    @Test
    void validatedProjectRecordsPinnedManifestAndCreatesAZip() throws IOException {
        var validator = (GeneratedProjectValidator) validationRequest -> new ValidationReport(
                VALIDATED,
                List.of(new ValidationStageResult("COMPILE", SUCCESS, 12, 0, 0, "Compilation passed")),
                List.of(new ObservedTool(TOOL_NAME, TOOL_DESCRIPTION, true)));

        var outcome = pipelineWithValidator(validator)
                .generate(specification, weatherGenerationRequest(), safeTempDir.resolve("weather"));

        assertEquals(VALIDATED, outcome.validationStatus());
        assertNotNull(outcome.archive());
        assertTrue(Files.isRegularFile(outcome.archive()));
        JsonNode manifest = objectMapper.readTree(outcome.projectRoot().resolve("GENERATION_MANIFEST.json").toFile());
        assertEquals("0.1.0", manifest.path("generatorVersion").asText());
        assertEquals("spring-ai-2-v2", manifest.path("templateVersion").asText());
        assertEquals("0.2.0", manifest.path("runtimeVersion").asText());
        assertEquals("spring-ai-2.0-java21-mvc-streamable", manifest.path("targetProfileId").asText());
        assertEquals("4.1.0", manifest.path("springBootVersion").asText());
        assertEquals("2.0.0", manifest.path("springAiVersion").asText());
        assertEquals(21, manifest.path("javaVersion").asInt());
        assertEquals("9.6.1", manifest.path("gradleVersion").asText());
        assertEquals(CompatibilityProfile.p0().containerImage(), manifest.path("containerImage").asText());
        assertEquals(outcome.sourceChecksum(), manifest.path("sourceChecksum").asText());
        assertEquals(64, manifest.path("originalSpecificationChecksum").asText().length());
        assertEquals("getForecast", manifest.path("operationMappings").get(0).path("operationId").asText());
        assertEquals(TOOL_NAME, manifest.path("operationMappings").get(0).path("toolName").asText());
        try (var zip = new ZipFile(outcome.archive().toFile())) {
            assertNotNull(zip.getEntry("GENERATION_MANIFEST.json"));
            assertNotNull(zip.getEntry("VALIDATION_REPORT.json"));
            assertNotNull(zip.getEntry("openapi/source.yaml"));
            assertNull(zip.getEntry("process-logs/compile.log"));
            Map<String, byte[]> archivedFiles = new java.util.LinkedHashMap<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                archivedFiles.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
            assertEquals(outcome.sourceChecksum(),
                    new SourceTreeChecksum().calculate(new GeneratedProjectFiles(archivedFiles)));
        }
    }

    @Test
    void writesResponseNormalizationOnlyForOperationsThatDeclareIt() throws IOException {
        var outcome = pipelineWithValidator(request -> new ValidationReport(UNVERIFIED, List.of(), List.of()))
                .generate(specification, normalizationGenerationRequest(), safeTempDir.resolve("normalization"));

        JsonNode manifest = objectMapper.readTree(outcome.projectRoot().resolve("GENERATION_MANIFEST.json").toFile());
        JsonNode normalization = manifest.path("operationMappings").get(0).path("responseNormalization");
        assertEquals("/response/body/items", normalization.path("dataPath").asText());
        assertEquals("/response/header/code", normalization.path("successCodePath").asText());
        assertEquals(List.of("00", 0, false),
                objectMapper.convertValue(normalization.path("successValues"), List.class));
        assertEquals("/response/header/message", normalization.path("errorMessagePath").asText());
        assertEquals("/response/body/totalCount", normalization.path("totalCountPath").asText());
        JsonNode rawOperation = manifest.path("operationMappings").get(1);
        assertFalse(rawOperation.path("responseNormalization").isObject());
    }

    @Test
    void rejectsSameInodeSameSizeSourceMutationAfterChecksumAndValidation() throws IOException {
        Path root = safeTempDir.resolve("weather");
        GeneratedProjectValidator validator = request -> {
            Path readme = root.resolve("README.md");
            try {
                BasicFileAttributes before = Files.readAttributes(readme, BasicFileAttributes.class);
                Files.writeString(readme, "# Climate MCP\n", UTF_8,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                BasicFileAttributes after = Files.readAttributes(readme, BasicFileAttributes.class);
                assertEquals(before.fileKey(), after.fileKey());
                assertEquals(before.size(), after.size());
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
            return new ValidationReport(
                    VALIDATED,
                    List.of(new ValidationStageResult("COMPILE", SUCCESS, 1, 0, 0, "Compilation passed")),
                    List.of());
        };

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipelineWithValidator(validator).generate(
                        specification, weatherGenerationRequest(), root));

        assertEquals(ARTIFACT_PACKAGE_FAILED, failure.code());
        assertEquals("Generated project source content changed after checksum calculation", failure.safeMessage());
        assertFalse(Files.exists(safeTempDir.resolve("weather.zip")));
        JsonNode report = objectMapper.readTree(root.resolve("VALIDATION_REPORT.json").toFile());
        assertEquals("UNVERIFIED", report.path("status").asText());
        assertEquals("PACKAGE", report.path("stages").get(0).path("stage").asText());
    }

    @Test
    void rejectsSourceDeletionAfterChecksumAndValidation() {
        Path root = safeTempDir.resolve("deleted-source");
        GeneratedProjectValidator validator = request -> {
            try {
                Files.delete(root.resolve("README.md"));
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
            return validatedReport();
        };

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipelineWithValidator(validator).generate(
                        specification, weatherGenerationRequest(), root));

        assertEquals(ARTIFACT_PACKAGE_FAILED, failure.code());
        assertEquals("Generated project source paths changed after checksum calculation", failure.safeMessage());
        assertFalse(Files.exists(safeTempDir.resolve("deleted-source.zip")));
    }

    @Test
    void rejectsNewSourceAfterChecksumAndValidation() {
        Path root = safeTempDir.resolve("added-source");
        GeneratedProjectValidator validator = request -> {
            try {
                Files.writeString(root.resolve("injected.txt"), "injected", UTF_8);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
            return validatedReport();
        };

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipelineWithValidator(validator).generate(
                        specification, weatherGenerationRequest(), root));

        assertEquals(ARTIFACT_PACKAGE_FAILED, failure.code());
        assertEquals("Generated project source paths changed after checksum calculation", failure.safeMessage());
        assertFalse(Files.exists(safeTempDir.resolve("added-source.zip")));
    }

    @Test
    void pipelinePackagingRejectsCaseFoldedSourceAddedAfterChecksumIndependentOfDefaultLocale() throws IOException {
        Path probe = Files.createDirectory(safeTempDir.resolve("case-fold-probe"));
        Files.writeString(probe.resolve("FILE.txt"), "upper", UTF_8);
        Files.writeString(probe.resolve("file.txt"), "lower", UTF_8);
        assumeCaseDistinctPaths(probe);
        Path root = safeTempDir.resolve("case-fold-added-source");
        GeneratedProjectValidator validator = request -> {
            try {
                Files.writeString(root.resolve("readme.md"), "injected", UTF_8);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
            return validatedReport();
        };
        Locale original = Locale.getDefault();
        GeneratorException failure;
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            failure = assertThrows(GeneratorException.class,
                    () -> pipelineWithValidator(validator).generate(
                            specification, weatherGenerationRequest(), root));
        } finally {
            Locale.setDefault(original);
        }

        assertEquals(ARTIFACT_PACKAGE_FAILED, failure.code());
        assertFalse(Files.exists(safeTempDir.resolve("case-fold-added-source.zip")));
    }

    @Test
    void rejectsProcessLogsAddedAfterChecksumInsteadOfPackagingThem() {
        Path root = safeTempDir.resolve("process-output");
        GeneratedProjectValidator validator = request -> {
            try {
                Path processLogs = Files.createDirectory(root.resolve("process-logs"));
                Files.writeString(processLogs.resolve("compile.log"), "private process output", UTF_8);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
            return validatedReport();
        };

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipelineWithValidator(validator).generate(
                        specification, weatherGenerationRequest(), root));

        assertEquals(ARTIFACT_PACKAGE_FAILED, failure.code());
        assertEquals("Generated project archive contains excluded process output", failure.safeMessage());
        assertFalse(Files.exists(safeTempDir.resolve("process-output.zip")));
    }

    @Test
    void reportBoundsAndRedactsSafeStageSummariesWithoutChangingSourceChecksum() throws IOException {
        String secret = "secret-value-that-must-not-appear";
        String longSummary = "Authorization: Bearer " + secret + " " + "x".repeat(5_000);
        GeneratedProjectValidator firstValidator = request -> new ValidationReport(
                UNVERIFIED,
                List.of(new ValidationStageResult("COMPILE", FAILED, 20, 1, 2, longSummary)),
                List.of());
        GeneratedProjectValidator secondValidator = request -> new ValidationReport(
                UNVERIFIED,
                List.of(new ValidationStageResult("COMPILE", FAILED, 999, 9, 9, "different execution")),
                List.of());

        var first = pipelineWithValidator(firstValidator)
                .generate(specification, weatherGenerationRequest(), safeTempDir.resolve("first"));
        var second = pipelineWithValidator(secondValidator)
                .generate(specification, weatherGenerationRequest(), safeTempDir.resolve("second"));

        String report = Files.readString(first.projectRoot().resolve("VALIDATION_REPORT.json"), UTF_8);
        assertFalse(report.contains(secret));
        assertTrue(report.contains("<redacted>"));
        JsonNode summary = objectMapper.readTree(report).path("stages").get(0).path("summary");
        assertTrue(summary.asText().length() <= ValidationReportWriter.MAX_SUMMARY_CHARACTERS);
        assertEquals(first.sourceChecksum(), second.sourceChecksum());
    }

    @Test
    void validatorStageErrorsKeepTheirCodeAndSafeMessageInTheReport() throws IOException {
        GeneratedProjectValidator validator = request -> {
            throw GeneratorException.user(COMPILE_FAILED, "COMPILE", "Compilation failed safely");
        };
        Path root = safeTempDir.resolve("weather");

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipelineWithValidator(validator).generate(specification, weatherGenerationRequest(), root));

        assertEquals(COMPILE_FAILED, failure.code());
        assertEquals("COMPILE", failure.stage());
        assertEquals("Compilation failed safely", failure.safeMessage());
        assertTrue(Files.exists(root.resolve("VALIDATION_REPORT.json")));
        String report = Files.readString(root.resolve("VALIDATION_REPORT.json"), UTF_8);
        assertTrue(report.contains("COMPILE_FAILED"));
        assertTrue(report.contains("Compilation failed safely"));
        assertFalse(Files.exists(safeTempDir.resolve("weather.zip")));
    }

    @Test
    void rejectsARequestForAnythingExceptTheExactPinnedProfileBeforeWritingOutput() {
        GenerationRequest valid = weatherGenerationRequest();
        GenerationRequest unsupported = new GenerationRequest(
                valid.project(), valid.provider(), valid.domain(), "spring-ai-latest", valid.validationLevel(),
                valid.validation(), valid.operations());
        Path root = safeTempDir.resolve("weather");

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipelineWithValidator(request -> new ValidationReport(VALIDATED, List.of(), List.of()))
                        .generate(specification, unsupported, root));

        assertEquals(TARGET_PROFILE_NOT_FOUND, failure.code());
        assertFalse(Files.exists(root));
    }

    @Test
    void rejectsAnUnknownProfileBeforeSpecificationAnalysisWithoutFallback() {
        AtomicBoolean analyzed = new AtomicBoolean();
        SpecificationAnalyzer trackingAnalyzer = (path, maxBytes) -> {
            analyzed.set(true);
            throw new AssertionError("Specification analysis must not run");
        };
        GenerationPipeline pipeline = pipelineWithRegistries(
                trackingAnalyzer,
                CompatibilityProfileRegistry.defaults(),
                ProjectGeneratorRegistry.of(Map.of("generator-spring-ai-2", minimalGenerator())),
                request -> validatedReport());

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipeline.generate(
                        specification,
                        requestWithProfile("spring-ai-latest"),
                        safeTempDir.resolve("unknown-profile")));

        assertEquals(TARGET_PROFILE_NOT_FOUND, failure.code());
        assertEquals("TARGET_VALIDATE", failure.stage());
        assertEquals("The requested compatibility profile is unavailable", failure.safeMessage());
        assertFalse(analyzed.get());
        assertFalse(Files.exists(safeTempDir.resolve("unknown-profile")));
    }

    @Test
    void rejectsAnOmittedSpringAi1EmitterBeforeSpecificationAnalysisOrSourceWrites() {
        AtomicBoolean analyzed = new AtomicBoolean();
        SpecificationAnalyzer trackingAnalyzer = (path, maxBytes) -> {
            analyzed.set(true);
            throw new AssertionError("Specification analysis must not run");
        };
        GenerationPipeline pipeline = pipelineWithRegistries(
                trackingAnalyzer,
                CompatibilityProfileRegistry.defaults(),
                ProjectGeneratorRegistry.of(Map.of("generator-spring-ai-2", minimalGenerator())),
                request -> validatedReport());

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipeline.generate(
                        specification,
                        requestWithProfile("spring-ai-1.1-java17-mvc-streamable"),
                        safeTempDir.resolve("missing-emitter")));

        assertEquals(TARGET_COMBINATION_UNSUPPORTED, failure.code());
        assertEquals("TARGET_VALIDATE", failure.stage());
        assertEquals("The requested compatibility profile is unsupported", failure.safeMessage());
        assertFalse(analyzed.get());
        assertFalse(Files.exists(safeTempDir.resolve("missing-emitter")));
    }

    @Test
    void passesTheCanonicalSpringAi1Java17ProfileToGenerationAndValidation() {
        CompatibilityProfileRegistry profiles = CompatibilityProfileRegistry.defaults();
        CompatibilityProfile java17 = profiles.find("spring-ai-1.1-java17-mvc-streamable").orElseThrow();
        AtomicReference<CompatibilityProfile> generatedProfile = new AtomicReference<>();
        AtomicReference<ValidationRequest> validationRequest = new AtomicReference<>();
        ProjectGenerator generator = context -> {
            generatedProfile.set(context.profile());
            return minimalGenerator().generate(context);
        };
        GeneratedProjectValidator validator = request -> {
            validationRequest.set(request);
            return new ValidationReport(UNVERIFIED, List.of(), List.of());
        };
        GenerationPipeline pipeline = pipelineWithRegistries(
                new SwaggerOpenApiAnalyzer(),
                profiles,
                ProjectGeneratorRegistry.of(Map.of("generator-spring-ai-1", generator)),
                validator);

        pipeline.generate(
                specification,
                requestWithProfile(java17.id()),
                safeTempDir.resolve("canonical-java17"));

        assertSame(java17, generatedProfile.get());
        assertSame(java17, validationRequest.get().profile());
    }

    @Test
    void writesDeterministicManifestMetadataForEachResolvedProfile() throws IOException {
        CompatibilityProfileRegistry profiles = CompatibilityProfileRegistry.defaults();
        GenerationPipeline pipeline = pipelineWithRegistries(
                new SwaggerOpenApiAnalyzer(),
                profiles,
                ProjectGeneratorRegistry.of(Map.of("generator-spring-ai-2", profileAwareMinimalGenerator())),
                request -> new ValidationReport(UNVERIFIED, List.of(), List.of()));
        CompatibilityProfile java17 = profiles.find("spring-ai-2.0-java17-mvc-streamable").orElseThrow();
        CompatibilityProfile java21 = profiles.find("spring-ai-2.0-java21-mvc-streamable").orElseThrow();

        var firstJava17 = pipeline.generate(
                specification, requestWithProfile(java17.id()), safeTempDir.resolve("java17-first"));
        var secondJava17 = pipeline.generate(
                specification, requestWithProfile(java17.id()), safeTempDir.resolve("java17-second"));
        var java21Outcome = pipeline.generate(
                specification, requestWithProfile(java21.id()), safeTempDir.resolve("java21"));

        byte[] firstManifest = Files.readAllBytes(
                firstJava17.projectRoot().resolve(GenerationManifestWriter.MANIFEST_FILE));
        byte[] secondManifest = Files.readAllBytes(
                secondJava17.projectRoot().resolve(GenerationManifestWriter.MANIFEST_FILE));
        assertArrayEquals(firstManifest, secondManifest);
        assertEquals(firstJava17.sourceChecksum(), secondJava17.sourceChecksum());
        assertNotEquals(firstJava17.sourceChecksum(), java21Outcome.sourceChecksum());
        assertManifestProfile(firstJava17.projectRoot(), java17);
        assertManifestProfile(java21Outcome.projectRoot(), java21);
    }

    @Test
    void masksBuiltInAndConfiguredSecretNamesInTheReportAndArchive() throws IOException {
        String accessToken = "first\\\"second";
        String parameterSecret = "first second with spaces";
        String environmentSecret = "environment\\\"secret";
        String summary = "{\"accessToken\":\"" + accessToken + "\"}\n"
                + "TENANTCREDENTIAL=" + parameterSecret + "\n"
                + "{\"weather_credential_42\" : \"" + environmentSecret + "\"}";
        GeneratedProjectValidator validator = request -> new ValidationReport(
                VALIDATED,
                List.of(new ValidationStageResult("COMPILE", SUCCESS, 1, 0, 0, summary)),
                List.of(new ObservedTool(TOOL_NAME, TOOL_DESCRIPTION, true)));

        var outcome = pipelineWithValidator(validator)
                .generate(specification, weatherGenerationRequest(), safeTempDir.resolve("weather"));

        byte[] report = Files.readAllBytes(outcome.projectRoot().resolve("VALIDATION_REPORT.json"));
        assertSecretsAbsent(report, accessToken, parameterSecret, environmentSecret);
        try (var zip = new ZipFile(outcome.archive().toFile())) {
            byte[] archivedReport = zip.getInputStream(zip.getEntry("VALIDATION_REPORT.json")).readAllBytes();
            assertSecretsAbsent(archivedReport, accessToken, parameterSecret, environmentSecret);
        }
    }

    @Test
    void redactsSecretsWithinLinearTimeForRepeatedEscapedQuotes() throws IOException {
        String secret = "linear-time-secret";
        String prefix = "accessToken=" + secret + "\n\"";
        String adversarial = prefix + "\\\"".repeat((65_536 - prefix.length()) / 2);
        Path root = Files.createDirectory(safeTempDir.resolve("adversarial-report"));
        var report = new ValidationReport(
                UNVERIFIED,
                List.of(new ValidationStageResult("COMPILE", FAILED, 1, 0, 1, adversarial)),
                List.of());

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> new ValidationReportWriter(objectMapper).write(root, report, List.of()));

        String persisted = Files.readString(root.resolve("VALIDATION_REPORT.json"), UTF_8);
        assertFalse(persisted.contains(secret));
        assertTrue(persisted.contains("<redacted>"));
    }

    @Test
    void validationCleanupNeverDeletesAReplacementWorkspace() throws IOException {
        AtomicReference<Path> replacement = new AtomicReference<>();
        AtomicReference<Path> original = new AtomicReference<>();
        GeneratedProjectValidator validator = request -> {
            try {
                Path validationRoot = request.projectRoot();
                Path moved = validationRoot.resolveSibling(validationRoot.getFileName() + ".original");
                Files.move(validationRoot, moved);
                original.set(moved);
                Files.createDirectory(validationRoot);
                Files.writeString(validationRoot.resolve("competitor.txt"), "keep", UTF_8);
                replacement.set(validationRoot);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
            return new ValidationReport(UNVERIFIED, List.of(), List.of());
        };

        assertThrows(GeneratorException.class, () -> pipelineWithValidator(validator)
                .generate(specification, weatherGenerationRequest(), safeTempDir.resolve("weather")));

        assertEquals("keep", Files.readString(replacement.get().resolve("competitor.txt"), UTF_8));
        deleteTestTree(replacement.get());
        deleteTestTree(original.get());
    }

    @Test
    void validatesInATemporaryCopyWithoutPollutingTheCanonicalProject() throws IOException {
        AtomicReference<Path> validationRoot = new AtomicReference<>();
        GeneratedProjectValidator validator = request -> {
            validationRoot.set(request.projectRoot());
            try {
                Files.createDirectories(request.projectRoot().resolve("build/classes"));
                Files.createDirectories(request.projectRoot().resolve(".gradle/cache"));
                Files.writeString(request.projectRoot().resolve("build/classes/output.bin"), "validation output", UTF_8);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
            return new ValidationReport(UNVERIFIED, List.of(), List.of());
        };
        Path canonicalRoot = safeTempDir.resolve("weather");

        var outcome = pipelineWithValidator(validator)
                .generate(specification, weatherGenerationRequest(), canonicalRoot);

        assertEquals(canonicalRoot, outcome.projectRoot());
        assertNotEquals(canonicalRoot, validationRoot.get());
        assertFalse(Files.exists(canonicalRoot.resolve("build")));
        assertFalse(Files.exists(canonicalRoot.resolve(".gradle")));
        assertFalse(Files.exists(validationRoot.get()));
    }

    @Test
    void derivesExactExpectedInputSchemaFromUserInputsAndExcludesSecrets() {
        AtomicReference<ValidationRequest> captured = new AtomicReference<>();
        GeneratedProjectValidator validator = request -> {
            captured.set(request);
            return new ValidationReport(UNVERIFIED, List.of(), List.of());
        };

        pipelineWithValidator(validator)
                .generate(specification, weatherGenerationRequest(), safeTempDir.resolve("schema-contract"));

        var expected = captured.get().expectedTools().get(TOOL_NAME);
        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of("nx", Map.of(
                        "type", "integer",
                        "format", "int32",
                        "description", "nx")),
                "required", List.of("nx")), expected.inputSchema());
        assertFalse(expected.inputSchema().toString().contains("serviceKey"));
        assertFalse(expected.inputSchema().toString().contains("tenantCredential"));
        assertEquals("getForecast", captured.get().expectedToolCall().tool().operationId());
        assertEquals(TOOL_NAME, captured.get().expectedToolCall().tool().name());
        assertEquals(Map.of("nx", 60),
                captured.get().expectedToolCall().arguments());
        assertThrows(UnsupportedOperationException.class,
                () -> captured.get().expectedToolCall().arguments().put("nx", 61));
    }

    @Test
    void rejectsInvalidRepresentativeArgumentsBeforeGeneratingSources() {
        AtomicBoolean generated = new AtomicBoolean();
        ProjectGenerator generator = context -> {
            generated.set(true);
            return new GeneratedProjectFiles(Map.of());
        };
        GenerationRequest valid = weatherGenerationRequest();
        GenerationRequest invalid = new GenerationRequest(
                valid.project(), valid.provider(), valid.domain(), valid.targetProfileId(), valid.validationLevel(),
                new GenerationRequest.ValidationConfiguration(new GenerationRequest.ToolCallValidation(
                        "getForecast", Map.of("nx", "not-a-number"))),
                valid.operations());

        GeneratorException exception = assertThrows(GeneratorException.class,
                () -> pipelineWith(generator, request -> validatedReport())
                        .generate(specification, invalid, safeTempDir.resolve("invalid-call")));

        assertEquals("TOOL_MODEL_VALIDATE", exception.stage());
        assertFalse(generated.get());
        assertFalse(Files.exists(safeTempDir.resolve("invalid-call")));
    }

    @Test
    void rejectsAnExistingArchiveBeforeCreatingTheProjectRoot() throws IOException {
        Path archive = Files.writeString(safeTempDir.resolve("weather.zip"), "keep", UTF_8);
        Path root = safeTempDir.resolve("weather");

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipelineWithValidator(request -> new ValidationReport(VALIDATED, List.of(), List.of()))
                        .generate(specification, weatherGenerationRequest(), root));

        assertEquals(ARTIFACT_PACKAGE_FAILED, failure.code());
        assertFalse(Files.exists(root));
        assertEquals("keep", Files.readString(archive, UTF_8));
    }

    @Test
    void latePackagingFailureReplacesValidatedReportWithPackageFailureAndPreservesCollision() throws IOException {
        Path archive = safeTempDir.resolve("weather.zip");
        GeneratedProjectValidator validator = request -> {
            try {
                Files.writeString(archive, "concurrent collision", UTF_8);
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
            return new ValidationReport(
                    VALIDATED,
                    List.of(new ValidationStageResult("COMPILE", SUCCESS, 1, 0, 0, "Compilation passed")),
                    List.of());
        };
        Path root = safeTempDir.resolve("weather");

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipelineWithValidator(validator).generate(specification, weatherGenerationRequest(), root));

        assertEquals(ARTIFACT_PACKAGE_FAILED, failure.code());
        JsonNode report = objectMapper.readTree(root.resolve("VALIDATION_REPORT.json").toFile());
        assertEquals("UNVERIFIED", report.path("status").asText());
        assertEquals("PACKAGE", report.path("stages").get(0).path("stage").asText());
        assertEquals("concurrent collision", Files.readString(archive, UTF_8));
    }

    private GenerationPipeline pipelineWithValidator(GeneratedProjectValidator validator) {
        return pipelineWith(minimalGenerator(), validator);
    }

    private ProjectGenerator minimalGenerator() {
        return context -> new GeneratedProjectFiles(Map.of(
                "README.md", "# Weather MCP\n".getBytes(UTF_8),
                "settings.gradle.kts", "rootProject.name = \"weather-mcp-server\"\n".getBytes(UTF_8),
                "build.gradle.kts", "plugins { java }\n".getBytes(UTF_8)));
    }

    private ProjectGenerator profileAwareMinimalGenerator() {
        return context -> new GeneratedProjectFiles(Map.of(
                "README.md", ("# " + context.profile().id() + "\n").getBytes(UTF_8),
                "settings.gradle.kts", "rootProject.name = \"weather-mcp-server\"\n".getBytes(UTF_8),
                "build.gradle.kts", "plugins { java }\n".getBytes(UTF_8)));
    }

    private GenerationPipeline pipelineWith(ProjectGenerator generator, GeneratedProjectValidator validator) {
        return new GenerationPipeline(
                new SwaggerOpenApiAnalyzer(),
                new ToolModelFactory(),
                CompatibilityProfile.p0(),
                generator,
                new SafeProjectWriter(),
                new SourceTreeChecksum(),
                new GenerationManifestWriter(objectMapper),
                validator,
                new ValidationReportWriter(objectMapper),
                new DeterministicZipPackager());
    }

    private GenerationPipeline pipelineWithRegistries(
            SpecificationAnalyzer analyzer,
            CompatibilityProfileRegistry profiles,
            ProjectGeneratorRegistry generators,
            GeneratedProjectValidator validator) {
        return new GenerationPipeline(
                analyzer,
                new ToolModelFactory(),
                profiles,
                generators,
                new SafeProjectWriter(),
                new SourceTreeChecksum(),
                new GenerationManifestWriter(objectMapper),
                validator,
                new ValidationReportWriter(objectMapper),
                new DeterministicZipPackager());
    }

    private void assertManifestProfile(Path projectRoot, CompatibilityProfile profile) throws IOException {
        JsonNode manifest = objectMapper.readTree(
                projectRoot.resolve(GenerationManifestWriter.MANIFEST_FILE).toFile());
        assertEquals(profile.id(), manifest.path("targetProfileId").asText());
        assertEquals(profile.target().javaVersion(), manifest.path("javaVersion").asInt());
        assertEquals(profile.gradleVersion(), manifest.path("gradleVersion").asText());
        assertEquals(profile.containerImage(), manifest.path("containerImage").asText());
        assertEquals(profile.templateVersion(), manifest.path("templateVersion").asText());
        assertEquals(profile.runtimeVersion(), manifest.path("runtimeVersion").asText());
    }

    private ValidationReport validatedReport() {
        return new ValidationReport(
                VALIDATED,
                List.of(new ValidationStageResult("COMPILE", SUCCESS, 1, 0, 0, "Compilation passed")),
                List.of());
    }

    private GenerationRequest weatherGenerationRequest() {
        return new GenerationRequest(
                new ProjectCoordinates("com.example", "weather-mcp-server", "com.example.weather"),
                "kma",
                "weather",
                CompatibilityProfile.p0().id(),
                GenerationRequest.ValidationLevel.MCP_PROTOCOL,
                representativeToolCallValidation(),
                List.of(new OperationSelection(
                        "getForecast",
                        true,
                        TOOL_NAME,
                        TOOL_DESCRIPTION,
                        Map.of(
                                "serviceKey", new ParameterOverride(SERVER_SECRET, "KMA_SERVICE_KEY"),
                                "tenantCredential", new ParameterOverride(
                                        SERVER_SECRET, "WEATHER_CREDENTIAL_42")))));
    }

    private GenerationRequest requestWithProfile(String profileId) {
        GenerationRequest request = weatherGenerationRequest();
        return new GenerationRequest(
                request.project(), request.provider(), request.domain(), profileId, request.validationLevel(),
                request.validation(), request.operations());
    }

    private GenerationRequest normalizationGenerationRequest() {
        GenerationRequest request = weatherGenerationRequest();
        return new GenerationRequest(
                request.project(), request.provider(), request.domain(), request.targetProfileId(), request.validationLevel(),
                request.validation(), List.of(
                        new OperationSelection(
                                "getForecast", true, TOOL_NAME, TOOL_DESCRIPTION,
                                Map.of(
                                        "serviceKey", new ParameterOverride(SERVER_SECRET, "KMA_SERVICE_KEY"),
                                        "tenantCredential", new ParameterOverride(
                                                SERVER_SECRET, "WEATHER_CREDENTIAL_42")),
                                new ResponseNormalizationPolicy(
                                        "/response/body/items", "/response/header/code", List.of("00", 0, false),
                                        "/response/header/message", "/response/body/totalCount")),
                        new OperationSelection("getHealth", true, null, null, Map.of())));
    }

    private GenerationRequest.ValidationConfiguration representativeToolCallValidation() {
        return new GenerationRequest.ValidationConfiguration(new GenerationRequest.ToolCallValidation(
                "getForecast", Map.of("nx", new BigDecimal("60.0"))));
    }

    private void assertSecretsAbsent(byte[] bytes, String... secrets) {
        String content = new String(bytes, UTF_8);
        for (String secret : secrets) {
            assertFalse(content.contains(secret), "Secret leaked: " + secret);
        }
        assertTrue(content.contains("<redacted>"));
    }

    private void assumeCaseDistinctPaths(Path root) throws IOException {
        try (var paths = Files.list(root)) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    paths.map(Path::getFileName).distinct().count() == 2,
                    "Case-distinct paths are unavailable on this filesystem");
        }
    }

    private void deleteTestTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
