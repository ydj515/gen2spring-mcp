package io.gen2spring.mcp.adapter.filesystem;

import static io.gen2spring.mcp.application.generation.validation.StageStatus.FAILED;
import static io.gen2spring.mcp.application.generation.validation.StageStatus.SUCCESS;
import static io.gen2spring.mcp.application.generation.validation.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.application.generation.validation.ValidationStatus.VALIDATED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.ARTIFACT_PACKAGE_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.COMPILE_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_COMBINATION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_PROFILE_NOT_FOUND;
import static io.gen2spring.mcp.domain.tool.ParameterSource.SERVER_SECRET;
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
import io.gen2spring.mcp.adapter.filesystem.generation.DeterministicZipPackager;
import io.gen2spring.mcp.adapter.filesystem.generation.GenerationManifestWriter;
import io.gen2spring.mcp.adapter.filesystem.generation.SafeProjectWriter;
import io.gen2spring.mcp.adapter.filesystem.generation.SourceTreeChecksum;
import io.gen2spring.mcp.adapter.filesystem.generation.ValidationReportWriter;
import io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.application.generation.command.GenerationCommand.OperationSelection;
import io.gen2spring.mcp.application.generation.command.GenerationCommand.ParameterOverride;
import io.gen2spring.mcp.application.generation.command.GenerationCommand.ProjectCoordinates;
import io.gen2spring.mcp.application.generation.command.GenerationCommand;
import io.gen2spring.mcp.application.generation.planning.ProjectGeneratorRegistry;
import io.gen2spring.mcp.application.generation.port.out.GeneratedProjectFiles;
import io.gen2spring.mcp.application.generation.port.out.GeneratedProjectValidator;
import io.gen2spring.mcp.application.generation.port.out.ProjectGenerator;
import io.gen2spring.mcp.application.generation.port.out.SpecificationAnalyzer;
import io.gen2spring.mcp.application.generation.usecase.GenerationPipeline;
import io.gen2spring.mcp.application.generation.usecase.GenerationPreview;
import io.gen2spring.mcp.application.generation.usecase.GenerationProgress;
import io.gen2spring.mcp.application.generation.usecase.ProgressStatus;
import io.gen2spring.mcp.application.generation.validation.ObservedTool;
import io.gen2spring.mcp.application.generation.validation.ValidationReport;
import io.gen2spring.mcp.application.generation.validation.ValidationRequest;
import io.gen2spring.mcp.application.generation.validation.ValidationStageResult;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.toolmodel.ToolModelFactory;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.OutputKind;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.ToolOutput;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    void publishesTheExactSuccessfulGenerationProgressSequence() {
        List<GenerationProgress> progress = new java.util.ArrayList<>();
        ValidationReport report = new ValidationReport(
                VALIDATED,
                List.of(
                        new ValidationStageResult("COMPILE", SUCCESS, 1, 0, 0, "ok"),
                        new ValidationStageResult("APPLICATION_CONTEXT", SUCCESS, 1, 0, 0, "ok"),
                        new ValidationStageResult("MCP_INITIALIZE", SUCCESS, 1, 0, 0, "ok"),
                        new ValidationStageResult("MCP_TOOLS_LIST", SUCCESS, 1, 0, 0, "ok"),
                        new ValidationStageResult("MCP_TOOL_CALL", SUCCESS, 1, 0, 0, "ok")),
                List.of());

        pipelineWithValidator(request -> report).generate(
                specification, weatherGenerationCommand(), safeTempDir.resolve("progress-success"), progress::add);

        assertEquals(List.of(
                event("ANALYZE", ProgressStatus.RUNNING), event("ANALYZE", ProgressStatus.SUCCESS),
                event("GENERATE", ProgressStatus.RUNNING), event("GENERATE", ProgressStatus.SUCCESS),
                event("COMPILE", ProgressStatus.RUNNING), event("COMPILE", ProgressStatus.SUCCESS),
                event("APPLICATION_CONTEXT", ProgressStatus.RUNNING),
                event("APPLICATION_CONTEXT", ProgressStatus.SUCCESS),
                event("MCP_INITIALIZE", ProgressStatus.RUNNING),
                event("MCP_INITIALIZE", ProgressStatus.SUCCESS),
                event("MCP_TOOLS_LIST", ProgressStatus.RUNNING),
                event("MCP_TOOLS_LIST", ProgressStatus.SUCCESS),
                event("MCP_TOOL_CALL", ProgressStatus.RUNNING),
                event("MCP_TOOL_CALL", ProgressStatus.SUCCESS),
                event("PACKAGE", ProgressStatus.RUNNING), event("PACKAGE", ProgressStatus.SUCCESS)), progress);
    }

    @Test
    void failsTheActiveGenerationStageAndSkipsEveryLaterStage() {
        List<GenerationProgress> progress = new java.util.ArrayList<>();
        ProjectGenerator failing = context -> {
            throw new IllegalStateException("private-generation-marker");
        };

        assertThrows(GeneratorException.class, () -> pipelineWith(failing, request -> validatedReport()).generate(
                specification, weatherGenerationCommand(), safeTempDir.resolve("progress-failure"), progress::add));

        assertEquals(List.of(
                event("ANALYZE", ProgressStatus.RUNNING), event("ANALYZE", ProgressStatus.SUCCESS),
                event("GENERATE", ProgressStatus.RUNNING), event("GENERATE", ProgressStatus.FAILED),
                event("COMPILE", ProgressStatus.SKIPPED),
                event("APPLICATION_CONTEXT", ProgressStatus.SKIPPED),
                event("MCP_INITIALIZE", ProgressStatus.SKIPPED),
                event("MCP_TOOLS_LIST", ProgressStatus.SKIPPED),
                event("MCP_TOOL_CALL", ProgressStatus.SKIPPED),
                event("PACKAGE", ProgressStatus.SKIPPED)), progress);
        assertFalse(progress.toString().contains("private-generation-marker"));
    }

    private GenerationProgress event(String stage, ProgressStatus status) {
        return new GenerationProgress(stage, status);
    }

    @Test
    void validationFailureKeepsAnUnverifiedDirectoryAndDoesNotCreateAZip() throws IOException {
        var validator = (GeneratedProjectValidator) validationRequest -> new ValidationReport(
                UNVERIFIED,
                List.of(new ValidationStageResult("COMPILE", FAILED, 10, 0, 1, "Compilation failed")),
                List.of());
        var pipeline = pipelineWithValidator(validator);

        var outcome = pipeline.generate(specification, weatherGenerationCommand(), safeTempDir.resolve("weather"));

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
                .generate(specification, weatherGenerationCommand(), safeTempDir.resolve("weather"));

        assertEquals(VALIDATED, outcome.validationStatus());
        assertNotNull(outcome.archive());
        assertTrue(Files.isRegularFile(outcome.archive()));
        byte[] runtimeMetadata = Files.readAllBytes(
                outcome.projectRoot().resolve(RuntimeMetadataDocument.FILE_NAME));
        var decodedMetadata = new CanonicalRuntimeMetadataCodec().decode(runtimeMetadata);
        assertEquals(RuntimeMetadataDocument.VERSION, decodedMetadata.document().metadataVersion());
        assertEquals(1, decodedMetadata.document().tools().size());
        assertEquals(TOOL_NAME, decodedMetadata.document().tools().getFirst().name());
        assertFalse(new String(runtimeMetadata, UTF_8).contains("KMA_SERVICE_KEY"));
        assertFalse(new String(runtimeMetadata, UTF_8).contains("targetProfile"));
        JsonNode manifest = objectMapper.readTree(outcome.projectRoot().resolve("GENERATION_MANIFEST.json").toFile());
        assertEquals("0.1.0", manifest.path("generatorVersion").asText());
        assertEquals("spring-ai-2-v3", manifest.path("templateVersion").asText());
        assertEquals("0.3.0", manifest.path("runtimeVersion").asText());
        assertEquals("spring-ai-2.0-java21-mvc-streamable", manifest.path("targetProfileId").asText());
        assertEquals("4.1.0", manifest.path("springBootVersion").asText());
        assertEquals("2.0.0", manifest.path("springAiVersion").asText());
        assertEquals(21, manifest.path("javaVersion").asInt());
        assertEquals("GRADLE_KOTLIN", manifest.path("buildTool").path("type").asText());
        assertEquals("9.6.1", manifest.path("buildTool").path("distributionVersion").asText());
        assertEquals("9.6.1", manifest.path("buildTool").path("wrapperVersion").asText());
        assertEquals("9.6.1", manifest.path("gradleVersion").asText());
        assertEquals(CompatibilityProfile.p0().containerImage(), manifest.path("containerImage").asText());
        assertEquals(outcome.sourceChecksum(), manifest.path("sourceChecksum").asText());
        assertEquals(64, manifest.path("originalSpecificationChecksum").asText().length());
        assertEquals("getForecast", manifest.path("operationMappings").get(0).path("operationId").asText());
        assertEquals(TOOL_NAME, manifest.path("operationMappings").get(0).path("toolName").asText());
        try (var zip = new ZipFile(outcome.archive().toFile())) {
            assertNotNull(zip.getEntry("GENERATION_MANIFEST.json"));
            assertArrayEquals(runtimeMetadata,
                    zip.getInputStream(zip.getEntry(RuntimeMetadataDocument.FILE_NAME)).readAllBytes());
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
    void rejectsEmitterAttemptsToReplaceTheReservedRuntimeMetadataPath() {
        ProjectGenerator conflicting = context -> new GeneratedProjectFiles(Map.of(
                RuntimeMetadataDocument.FILE_NAME, "untrusted".getBytes(UTF_8)));

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> pipelineWith(conflicting, request -> validatedReport()).generate(
                        specification, weatherGenerationCommand(), safeTempDir.resolve("metadata-collision")));

        assertEquals(io.gen2spring.mcp.domain.error.GeneratorErrorCode.RUNTIME_METADATA_INVALID, failure.code());
        assertEquals("RUNTIME_METADATA", failure.stage());
        assertEquals("Runtime metadata could not be generated", failure.safeMessage());
        assertFalse(Files.exists(safeTempDir.resolve("metadata-collision")));
    }

    @Test
    void emitsIdenticalRuntimeMetadataForEquivalentToolIrAcrossAllProfiles() throws IOException {
        CompatibilityProfileRegistry profiles = CompatibilityProfileRegistry.defaults();
        ProjectGenerator generator = minimalGenerator();
        GenerationPipeline pipeline = pipelineWithRegistries(
                new SwaggerOpenApiAnalyzer(),
                profiles,
                ProjectGeneratorRegistry.of(Map.of(
                        "generator-spring-ai-1", generator,
                        "generator-spring-ai-2", generator)),
                request -> new ValidationReport(UNVERIFIED, List.of(), List.of()));
        byte[] expectedMetadata = null;
        String expectedChecksum = null;

        for (CompatibilityProfile profile : profiles.profiles()) {
            var outcome = pipeline.generate(
                    specification,
                    requestWithProfile(profile.id()),
                    safeTempDir.resolve("metadata-" + profile.id()));
            byte[] metadata = Files.readAllBytes(
                    outcome.projectRoot().resolve(RuntimeMetadataDocument.FILE_NAME));
            if (expectedMetadata == null) {
                expectedMetadata = metadata;
                expectedChecksum = outcome.sourceChecksum();
            } else {
                assertArrayEquals(expectedMetadata, metadata);
                assertEquals(expectedChecksum, outcome.sourceChecksum());
            }
        }
    }

    @Test
    void changesRuntimeMetadataAndSourceChecksumWhenToolSemanticsChange() throws IOException {
        GenerationCommand original = weatherGenerationCommand();
        OperationSelection operation = original.operations().getFirst();
        GenerationCommand changed = new GenerationCommand(
                original.project(), original.provider(), original.domain(), original.targetProfileId(),
                original.validationLevel(), original.validation(),
                List.of(new OperationSelection(
                        operation.operationId(), operation.enabled(), operation.toolName(),
                        operation.toolDescription() + " Changed", operation.parameters(),
                        operation.responseNormalization(), operation.output(), operation.retry(),
                        operation.pagination())));
        GenerationPipeline pipeline = pipelineWithValidator(
                request -> new ValidationReport(UNVERIFIED, List.of(), List.of()));

        var first = pipeline.generate(specification, original, safeTempDir.resolve("metadata-original"));
        var second = pipeline.generate(specification, changed, safeTempDir.resolve("metadata-changed"));

        assertFalse(java.util.Arrays.equals(
                Files.readAllBytes(first.projectRoot().resolve(RuntimeMetadataDocument.FILE_NAME)),
                Files.readAllBytes(second.projectRoot().resolve(RuntimeMetadataDocument.FILE_NAME))));
        assertNotEquals(first.sourceChecksum(), second.sourceChecksum());
    }

    @Test
    void writesResponseNormalizationOnlyForOperationsThatDeclareIt() throws IOException {
        var outcome = pipelineWithValidator(request -> new ValidationReport(UNVERIFIED, List.of(), List.of()))
                .generate(specification, normalizationGenerationCommand(), safeTempDir.resolve("normalization"));

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
    void writesDeterministicFinalToolPoliciesWithoutRuntimeCursorValues() throws IOException {
        Path firstRoot = Files.createDirectory(safeTempDir.resolve("policy-manifest-first"));
        Path secondRoot = Files.createDirectory(safeTempDir.resolve("policy-manifest-second"));
        OpenApiDocument document = new OpenApiDocument(
                "3.0.3", "a".repeat(64), "yaml", URI.create("https://weather.example.test"),
                List.of(), Map.of(), List.of());
        ToolDefinition tool = policyManifestTool();
        GenerationManifestWriter writer = new GenerationManifestWriter(objectMapper);

        Path first = writer.write(firstRoot, CompatibilityProfile.p0(), document, "b".repeat(64), List.of(tool));
        Path second = writer.write(secondRoot, CompatibilityProfile.p0(), document, "b".repeat(64), List.of(tool));

        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        JsonNode mapping = objectMapper.readTree(first.toFile()).path("operationMappings").get(0);
        JsonNode expected = objectMapper.readTree("""
                {
                  "operationId": "getForecast",
                  "toolName": "weather_get_forecast",
                  "output": {
                    "mode": "TYPED",
                    "schemaChecksum": "%s"
                  },
                  "pagination": {
                    "itemsPath": "/items",
                    "maxItems": 1000,
                    "maxPages": 10,
                    "nextValuePath": "/next",
                    "requestParameter": "cursor"
                  },
                  "retry": {
                    "initialBackoffMillis": 100,
                    "maxBackoffMillis": 1000,
                    "maxRetries": 2,
                    "networkErrors": true,
                    "respectRetryAfter": true,
                    "statusCodes": [429, 503]
                  }
                }
                """.formatted(GenerationPreview.Tool.from(tool, Map.of(
                        "type", "object", "properties", Map.of(), "required", List.of()))
                        .output().schemaChecksum()));
        assertEquals(expected, mapping);
        assertFalse(Files.readString(first, UTF_8).contains("initial-private-cursor"));
    }

    private ToolDefinition policyManifestTool() {
        ApiSchema id = new ApiSchema(
                SchemaType.INTEGER, "int64", false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema item = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, Map.of("id", id), List.of("id"), null, true, List.of());
        ApiSchema items = new ApiSchema(
                SchemaType.ARRAY, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), item, true, List.of());
        ApiSchema next = new ApiSchema(
                SchemaType.STRING, null, true, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema result = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, Map.of("next", next, "items", items),
                List.of("items"), null, true, List.of());
        return new ToolDefinition(
                "getForecast", "weather_get_forecast", "Get forecast", List.of(),
                new HttpExecution(
                        HttpMethod.GET, URI.create("https://weather.example.test"), "/forecast", List.of(),
                        false, false, null,
                        new RetryPolicy(List.of(503, 429), true, 2, 100, 1_000, true),
                        new PaginationPolicy(
                                "cursor", "initial-private-cursor", "/items", "/next", 10, 1_000)),
                List.of(), new ToolOutput(OutputKind.TYPED_DTO, result, result));
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
                        specification, weatherGenerationCommand(), root));

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
                        specification, weatherGenerationCommand(), root));

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
                        specification, weatherGenerationCommand(), root));

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
                            specification, weatherGenerationCommand(), root));
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
                        specification, weatherGenerationCommand(), root));

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
                .generate(specification, weatherGenerationCommand(), safeTempDir.resolve("first"));
        var second = pipelineWithValidator(secondValidator)
                .generate(specification, weatherGenerationCommand(), safeTempDir.resolve("second"));

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
                () -> pipelineWithValidator(validator).generate(specification, weatherGenerationCommand(), root));

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
        GenerationCommand valid = weatherGenerationCommand();
        GenerationCommand unsupported = new GenerationCommand(
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
        CompatibilityProfile java21Maven = profiles.find(
                "spring-ai-2.0-java21-maven-mvc-streamable").orElseThrow();

        var firstJava17 = pipeline.generate(
                specification, requestWithProfile(java17.id()), safeTempDir.resolve("java17-first"));
        var secondJava17 = pipeline.generate(
                specification, requestWithProfile(java17.id()), safeTempDir.resolve("java17-second"));
        var java21Outcome = pipeline.generate(
                specification, requestWithProfile(java21.id()), safeTempDir.resolve("java21"));
        var java21MavenOutcome = pipeline.generate(
                specification, requestWithProfile(java21Maven.id()), safeTempDir.resolve("java21-maven"));

        byte[] firstManifest = Files.readAllBytes(
                firstJava17.projectRoot().resolve(GenerationManifestWriter.MANIFEST_FILE));
        byte[] secondManifest = Files.readAllBytes(
                secondJava17.projectRoot().resolve(GenerationManifestWriter.MANIFEST_FILE));
        assertArrayEquals(firstManifest, secondManifest);
        assertEquals(firstJava17.sourceChecksum(), secondJava17.sourceChecksum());
        assertNotEquals(firstJava17.sourceChecksum(), java21Outcome.sourceChecksum());
        assertManifestProfile(firstJava17.projectRoot(), java17);
        assertManifestProfile(java21Outcome.projectRoot(), java21);
        assertManifestProfile(java21MavenOutcome.projectRoot(), java21Maven);
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
                .generate(specification, weatherGenerationCommand(), safeTempDir.resolve("weather"));

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
                .generate(specification, weatherGenerationCommand(), safeTempDir.resolve("weather")));

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
                .generate(specification, weatherGenerationCommand(), canonicalRoot);

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
                .generate(specification, weatherGenerationCommand(), safeTempDir.resolve("schema-contract"));

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
        GenerationCommand valid = weatherGenerationCommand();
        GenerationCommand invalid = new GenerationCommand(
                valid.project(), valid.provider(), valid.domain(), valid.targetProfileId(), valid.validationLevel(),
                new GenerationCommand.ValidationConfiguration(new GenerationCommand.ToolCallValidation(
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
                        .generate(specification, weatherGenerationCommand(), root));

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
                () -> pipelineWithValidator(validator).generate(specification, weatherGenerationCommand(), root));

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
        assertEquals(profile.target().buildTool(), manifest.path("buildTool").path("type").asText());
        assertEquals(profile.buildToolchain().distributionVersion(),
                manifest.path("buildTool").path("distributionVersion").asText());
        assertEquals(profile.buildToolchain().wrapperVersion(),
                manifest.path("buildTool").path("wrapperVersion").asText());
        if ("GRADLE_KOTLIN".equals(profile.target().buildTool())) {
            assertEquals(profile.gradleVersion(), manifest.path("gradleVersion").asText());
        } else {
            assertFalse(manifest.has("gradleVersion"));
        }
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

    private GenerationCommand weatherGenerationCommand() {
        return new GenerationCommand(
                new ProjectCoordinates("com.example", "weather-mcp-server", "com.example.weather"),
                "kma",
                "weather",
                CompatibilityProfile.p0().id(),
                GenerationCommand.ValidationLevel.MCP_PROTOCOL,
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

    private GenerationCommand requestWithProfile(String profileId) {
        GenerationCommand request = weatherGenerationCommand();
        return new GenerationCommand(
                request.project(), request.provider(), request.domain(), profileId, request.validationLevel(),
                request.validation(), request.operations());
    }

    private GenerationCommand normalizationGenerationCommand() {
        GenerationCommand request = weatherGenerationCommand();
        return new GenerationCommand(
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

    private GenerationCommand.ValidationConfiguration representativeToolCallValidation() {
        return new GenerationCommand.ValidationConfiguration(new GenerationCommand.ToolCallValidation(
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
