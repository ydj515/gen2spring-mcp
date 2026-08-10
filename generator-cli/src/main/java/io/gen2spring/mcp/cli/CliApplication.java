package io.gen2spring.mcp.cli;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.INTERNAL_ERROR;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_FILE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_TOO_LARGE;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.VALIDATED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.error.GeneratorErrorCode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationOutcome;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.openapi.SpecificationAnalyzer;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class CliApplication {
    private static final long MAX_SPECIFICATION_BYTES = 10L * 1024 * 1024;
    private static final String REPORT_FILE = "VALIDATION_REPORT.json";

    private final CommandLine commandLine;
    private final GenerationConfigurationReader configurationReader;
    private final SpecificationAnalyzer analyzer;
    private final GenerationExecutor generationExecutor;
    private final CompatibilityProfileRegistry profiles;
    private final ObjectMapper json;
    private final LocalPathBoundary pathBoundary;
    private final PublicationHook publicationHook;

    CliApplication(
            CommandLine commandLine,
            GenerationConfigurationReader configurationReader,
            SpecificationAnalyzer analyzer,
            GenerationExecutor generationExecutor,
            CompatibilityProfile profile,
            ObjectMapper json) {
        this(
                commandLine,
                configurationReader,
                analyzer,
                generationExecutor,
                CompatibilityProfileRegistry.of(List.of(Objects.requireNonNull(profile, "profile"))),
                json,
                PublicationHook.NONE);
    }

    CliApplication(
            CommandLine commandLine,
            GenerationConfigurationReader configurationReader,
            SpecificationAnalyzer analyzer,
            GenerationExecutor generationExecutor,
            CompatibilityProfile profile,
            ObjectMapper json,
            PublicationHook publicationHook) {
        this(
                commandLine,
                configurationReader,
                analyzer,
                generationExecutor,
                CompatibilityProfileRegistry.of(List.of(Objects.requireNonNull(profile, "profile"))),
                json,
                publicationHook);
    }

    CliApplication(
            CommandLine commandLine,
            GenerationConfigurationReader configurationReader,
            SpecificationAnalyzer analyzer,
            GenerationExecutor generationExecutor,
            CompatibilityProfileRegistry profiles,
            ObjectMapper json) {
        this(commandLine, configurationReader, analyzer, generationExecutor, profiles, json, PublicationHook.NONE);
    }

    CliApplication(
            CommandLine commandLine,
            GenerationConfigurationReader configurationReader,
            SpecificationAnalyzer analyzer,
            GenerationExecutor generationExecutor,
            CompatibilityProfileRegistry profiles,
            ObjectMapper json,
            PublicationHook publicationHook) {
        this.commandLine = Objects.requireNonNull(commandLine, "commandLine");
        this.configurationReader = Objects.requireNonNull(configurationReader, "configurationReader");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.generationExecutor = Objects.requireNonNull(generationExecutor, "generationExecutor");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.json = Objects.requireNonNull(json, "json").copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.pathBoundary = new LocalPathBoundary();
        this.publicationHook = Objects.requireNonNull(publicationHook, "publicationHook");
    }

    public int run(String[] arguments, PrintWriter stdout, PrintWriter stderr) {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        try {
            CommandLine.Parsed parsed = commandLine.parse(arguments);
            return switch (parsed.command()) {
                case PROFILES -> profiles(stdout);
                case INSPECT -> inspect(parsed, stdout);
                case GENERATE -> generate(parsed, stdout);
            };
        } catch (CliUsageException | CliConfigurationException exception) {
            writeError(stderr, "CLI_CONFIGURATION_ERROR", "CLI", exception.getMessage());
            return 2;
        } catch (GeneratorException exception) {
            writeError(stderr, exception.code().name(), exception.stage(), exception.safeMessage());
            return exitCode(exception);
        } catch (RuntimeException exception) {
            writeError(stderr, INTERNAL_ERROR.name(), "CLI", "The command failed safely");
            return 4;
        }
    }

    private int profiles(PrintWriter stdout) {
        ObjectNode root = json.createObjectNode();
        var items = root.putArray("profiles");
        for (CompatibilityProfile profile : profiles.profiles()) {
            ObjectNode item = items.addObject();
            item.put("id", profile.id());
            item.put("generatorModule", profile.generatorModule());
            item.put("templateVersion", profile.templateVersion());
            item.put("runtimeVersion", profile.runtimeVersion());
            item.put("gradleVersion", profile.gradleVersion());
            item.put("containerImage", profile.containerImage());
            ObjectNode target = item.putObject("target");
            target.put("javaVersion", profile.target().javaVersion());
            target.put("springBootVersion", profile.target().springBootVersion());
            target.put("springAiVersion", profile.target().springAiVersion());
            target.put("buildTool", profile.target().buildTool());
            target.put("webStack", profile.target().webStack());
            target.put("programmingModel", profile.target().programmingModel());
            target.put("transport", profile.target().transport());
        }
        writeSuccess(stdout, root);
        return 0;
    }

    private int inspect(CommandLine.Parsed parsed, PrintWriter stdout) {
        LocalPathBoundary.NewFile output = newOutputPath(parsed.output(), "Analysis output");
        SpecificationAnalyzer.AnalysisResult result;
        try (var specification = specificationCopy(parsed.specification())) {
            result = analyzer.analyze(specification.path(), MAX_SPECIFICATION_BYTES);
            specification.path();
            if (result == null || result.document() == null) {
                throw GeneratorException.system(
                        INTERNAL_ERROR, "SPEC_ANALYSIS", "Specification analysis returned no result", null);
            }
        }
        ObjectNode analysis = analysisJson(result.document());
        publishNewJson(output, analysis);

        ObjectNode response = json.createObjectNode();
        response.put("analysis", output.path().toString());
        response.put("checksum", result.document().checksum());
        writeSuccess(stdout, response);
        return 0;
    }

    private int generate(CommandLine.Parsed parsed, PrintWriter stdout) {
        GenerationRequest request = configurationReader.read(parsed.configuration());
        ProjectOutput output = newProjectPath(parsed.output());
        GenerationOutcome outcome;
        try (var specification = specificationCopy(parsed.specification())) {
            output.verifyAvailable();
            outcome = generationExecutor.generate(specification.path(), request, output.project().path());
            specification.path();
        }
        requireOutcome(output.project().path(), outcome);

        ObjectNode response = json.createObjectNode();
        response.put("project", outcome.projectRoot().toAbsolutePath().normalize().toString());
        response.put("report", outcome.projectRoot().resolve(REPORT_FILE).toAbsolutePath().normalize().toString());
        if (outcome.archive() == null) {
            response.putNull("archive");
        } else {
            response.put("archive", outcome.archive().toAbsolutePath().normalize().toString());
        }
        response.put("status", outcome.validationStatus().name());
        response.put("sourceChecksum", outcome.sourceChecksum());
        writeSuccess(stdout, response);
        return outcome.validationStatus() == UNVERIFIED ? 5 : 0;
    }

    private ObjectNode analysisJson(OpenApiDocument document) {
        ObjectNode result = json.createObjectNode();
        result.put("checksum", document.checksum());
        result.put("openApiVersion", document.openApiVersion());
        result.put("sourceExtension", document.sourceExtension());
        if (document.baseUrl() == null) {
            result.putNull("baseUrl");
        } else {
            result.put("baseUrl", document.baseUrl().toString());
        }
        result.set("operations", json.valueToTree(document.operations()));
        result.set("securitySchemes", json.valueToTree(document.securitySchemes()));
        result.set("warnings", json.valueToTree(document.warnings()));
        return result;
    }

    private LocalPathBoundary.VerifiedCopy specificationCopy(Path requested) {
        try {
            var source = pathBoundary.regularFile(requested, "Specification");
            return pathBoundary.verifiedCopy(source, Math.toIntExact(MAX_SPECIFICATION_BYTES), suffix(source.path()));
        } catch (LocalPathBoundary.PathBoundaryException exception) {
            GeneratorErrorCode code = exception.reason() == LocalPathBoundary.Reason.TOO_LARGE
                    ? SPEC_TOO_LARGE : SPEC_FILE_UNSUPPORTED;
            throw GeneratorException.user(code, "SOURCE_LOAD", exception.getMessage(), exception);
        }
    }

    private String suffix(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            throw GeneratorException.user(
                    SPEC_FILE_UNSUPPORTED, "SOURCE_LOAD", "Specification extension is required");
        }
        return name.substring(dot);
    }

    private ProjectOutput newProjectPath(Path requested) {
        LocalPathBoundary.NewFile project = newOutputPath(requested, "Project output");
        try {
            LocalPathBoundary.NewFile archive = pathBoundary.newFile(
                    project.path().resolveSibling(project.path().getFileName() + ".zip"), "Project archive output");
            return new ProjectOutput(project, archive);
        } catch (LocalPathBoundary.PathBoundaryException exception) {
            throw new CliConfigurationException(exception.getMessage(), exception);
        }
    }

    private LocalPathBoundary.NewFile newOutputPath(Path requested, String label) {
        try {
            return pathBoundary.newFile(requested, label);
        } catch (LocalPathBoundary.PathBoundaryException exception) {
            throw new CliConfigurationException(exception.getMessage(), exception);
        }
    }

    private void requireOutcome(Path requestedOutput, GenerationOutcome outcome) {
        if (outcome == null || outcome.projectRoot() == null || outcome.validationStatus() == null
                || outcome.sourceChecksum() == null || outcome.sourceChecksum().isBlank()) {
            throw GeneratorException.system(INTERNAL_ERROR, "GENERATION", "Generation returned an incomplete result", null);
        }
        Path expectedProject = requestedOutput.toAbsolutePath().normalize();
        Path actualProject = outcome.projectRoot().toAbsolutePath().normalize();
        if (!expectedProject.equals(actualProject)) {
            throw GeneratorException.system(INTERNAL_ERROR, "GENERATION", "Generation returned an unexpected output path", null);
        }
        Path expectedArchive = actualProject.resolveSibling(actualProject.getFileName() + ".zip");
        if (outcome.validationStatus() == VALIDATED
                && (outcome.archive() == null || !expectedArchive.equals(outcome.archive().toAbsolutePath().normalize()))) {
            throw GeneratorException.system(INTERNAL_ERROR, "PACKAGE", "Validated generation returned no expected archive", null);
        }
        if (outcome.validationStatus() == UNVERIFIED && outcome.archive() != null) {
            throw GeneratorException.system(INTERNAL_ERROR, "PACKAGE", "Unverified generation returned an archive", null);
        }
    }

    private void publishNewJson(LocalPathBoundary.NewFile targetBoundary, ObjectNode value) {
        Path target = targetBoundary.path();
        byte[] bytes;
        try {
            bytes = (json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n").getBytes(UTF_8);
        } catch (JsonProcessingException exception) {
            throw new CliConfigurationException("Analysis output could not be serialized", exception);
        }
        Path staging = null;
        LocalPathBoundary.PrivateDirectory stagingDirectory = null;
        LocalPathBoundary.RegularFile stagingIdentity = null;
        try {
            targetBoundary.verifyAvailable();
            stagingDirectory = pathBoundary.privateDirectory(
                    targetBoundary, ".openapi-mcp-publish-", "Analysis private staging directory");
            stagingIdentity = stagingDirectory.createFile("analysis-", ".json", "Analysis staging file");
            staging = stagingIdentity.path();
            publicationHook.afterStagingIdentityRecorded(staging);
            stagingDirectory.verifyStable();
            stagingIdentity.verifyStable();
            Files.write(staging, bytes, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS);
            byte[] staged = stagingIdentity.readBounded(bytes.length);
            if (!Arrays.equals(bytes, staged)) {
                throw new IOException("staged analysis verification failed");
            }
            targetBoundary.verifyAvailable();
            Files.createLink(target, staging);
            publicationHook.afterTargetLinked(target, staging);
            targetBoundary.verifyParentStable();
            LocalPathBoundary.RegularFile targetIdentity = pathBoundary.regularFile(target, "Analysis output");
            stagingDirectory.verifyStable();
            stagingIdentity.requireSameFileIdentity(targetIdentity);
            if (!Arrays.equals(bytes, targetIdentity.readBounded(bytes.length))) {
                throw new IOException("published analysis verification failed");
            }
            stagingIdentity.requireSameFileIdentity(targetIdentity);
            stagingDirectory.verifyStable();
        } catch (CliConfigurationException exception) {
            cleanupPrivateStaging(stagingDirectory, stagingIdentity, exception);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            var failure = new CliConfigurationException("Analysis output could not be published safely", exception);
            cleanupPrivateStaging(stagingDirectory, stagingIdentity, failure);
            throw failure;
        }
        try {
            if (stagingDirectory == null || stagingIdentity == null
                    || !stagingDirectory.deleteStagingIfOwned(stagingIdentity)
                    || !stagingDirectory.deleteIfOwnedAndEmpty()) {
                throw new IOException("staging identity changed before cleanup");
            }
        } catch (IOException | RuntimeException exception) {
            var failure = new CliConfigurationException("Analysis output could not be published safely", exception);
            cleanupPrivateStaging(stagingDirectory, stagingIdentity, failure);
            throw failure;
        }
    }

    private void cleanupPrivateStaging(
            LocalPathBoundary.PrivateDirectory directory,
            LocalPathBoundary.RegularFile identity,
            RuntimeException failure) {
        if (directory == null) {
            return;
        }
        if (identity != null) {
            try {
                directory.deleteStagingIfOwned(identity);
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        try {
            directory.deleteIfOwnedAndEmpty();
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void writeSuccess(PrintWriter output, ObjectNode value) {
        try {
            output.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
            output.flush();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    private void writeError(PrintWriter output, String code, String stage, String safeMessage) {
        ObjectNode error = json.createObjectNode();
        error.put("code", safe(code));
        error.put("stage", safe(stage));
        error.put("message", safe(safeMessage));
        try {
            output.println(json.writeValueAsString(error));
        } catch (JsonProcessingException exception) {
            output.println("{\"code\":\"INTERNAL_ERROR\",\"stage\":\"CLI\",\"message\":\"The command failed safely\"}");
        }
        output.flush();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "Unavailable" : value.substring(0, Math.min(value.length(), 2_048));
    }

    private int exitCode(GeneratorException exception) {
        return switch (exception.code()) {
            case SPEC_FILE_UNSUPPORTED, SPEC_TOO_LARGE, SPEC_PARSE_FAILED, SPEC_REFERENCE_UNRESOLVED,
                    SPEC_VERSION_UNSUPPORTED, OPERATION_ID_DUPLICATED, OPERATION_UNSUPPORTED,
                    VALIDATION_ARGUMENT_INVALID, SECRET_EXPOSURE_DETECTED -> 3;
            case TARGET_PROFILE_NOT_FOUND, TARGET_COMBINATION_UNSUPPORTED -> 2;
            case SOURCE_GENERATION_FAILED -> 4;
            case COMPILE_TIMEOUT, COMPILE_FAILED, APPLICATION_CONTEXT_FAILED,
                    MCP_INITIALIZE_FAILED, MCP_TOOLS_LIST_FAILED, MCP_TOOL_CALL_FAILED -> 5;
            case ARTIFACT_PACKAGE_FAILED -> 6;
            case INTERNAL_ERROR -> internalExitCode(exception.stage());
        };
    }

    private int internalExitCode(String stage) {
        if (stage != null && (stage.startsWith("COMPILE") || stage.startsWith("APPLICATION")
                || stage.startsWith("MCP") || stage.startsWith("VALIDATION"))) {
            return 5;
        }
        return stage != null && stage.startsWith("PACKAGE") ? 6 : 4;
    }

    @FunctionalInterface
    interface GenerationExecutor {
        GenerationOutcome generate(Path specification, GenerationRequest request, Path outputRoot);
    }

    interface PublicationHook {
        PublicationHook NONE = new PublicationHook() {};

        default void afterStagingIdentityRecorded(Path staging) throws IOException {}

        default void afterTargetLinked(Path target, Path staging) throws IOException {}
    }

    private record ProjectOutput(LocalPathBoundary.NewFile project, LocalPathBoundary.NewFile archive) {
        void verifyAvailable() {
            project.verifyAvailable();
            archive.verifyAvailable();
        }
    }
}
