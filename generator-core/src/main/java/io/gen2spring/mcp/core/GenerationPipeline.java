package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.INTERNAL_ERROR;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_COMBINATION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_PROFILE_NOT_FOUND;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.StageStatus.FAILED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus.VALIDATED;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.error.GeneratorErrorCode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectFiles;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectValidator;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationOutcome;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedToolCall;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ProjectGenerator;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationReport;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationRequest;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStageResult;
import io.gen2spring.mcp.domain.generation.ExpectedToolSchemaFactory;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.openapi.SpecificationAnalyzer;
import io.gen2spring.mcp.policy.ToolModelFactory;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;

public final class GenerationPipeline {
    public static final long DEFAULT_MAX_SPECIFICATION_BYTES = 10L * 1024 * 1024;

    private final SpecificationAnalyzer analyzer;
    private final ToolModelFactory toolModelFactory;
    private final CompatibilityProfileRegistry profiles;
    private final ProjectGeneratorRegistry projectGenerators;
    private final SafeProjectWriter projectWriter;
    private final SourceTreeChecksum sourceTreeChecksum;
    private final GenerationManifestWriter manifestWriter;
    private final GeneratedProjectValidator validator;
    private final ValidationReportWriter reportWriter;
    private final DeterministicZipPackager zipPackager;
    private final ExpectedToolSchemaFactory expectedToolSchemaFactory = new ExpectedToolSchemaFactory();
    private final ExpectedToolCallFactory expectedToolCallFactory = new ExpectedToolCallFactory();

    public GenerationPipeline(
            SpecificationAnalyzer analyzer,
            ToolModelFactory toolModelFactory,
            CompatibilityProfile profile,
            ProjectGenerator projectGenerator,
            SafeProjectWriter projectWriter,
            SourceTreeChecksum sourceTreeChecksum,
            GenerationManifestWriter manifestWriter,
            GeneratedProjectValidator validator,
            ValidationReportWriter reportWriter,
            DeterministicZipPackager zipPackager) {
        this(
                analyzer,
                toolModelFactory,
                CompatibilityProfileRegistry.of(List.of(Objects.requireNonNull(profile, "profile"))),
                ProjectGeneratorRegistry.of(Map.of(
                        profile.generatorModule(), Objects.requireNonNull(projectGenerator, "projectGenerator"))),
                projectWriter,
                sourceTreeChecksum,
                manifestWriter,
                validator,
                reportWriter,
                zipPackager);
    }

    public GenerationPipeline(
            SpecificationAnalyzer analyzer,
            ToolModelFactory toolModelFactory,
            CompatibilityProfileRegistry profiles,
            ProjectGeneratorRegistry projectGenerators,
            SafeProjectWriter projectWriter,
            SourceTreeChecksum sourceTreeChecksum,
            GenerationManifestWriter manifestWriter,
            GeneratedProjectValidator validator,
            ValidationReportWriter reportWriter,
            DeterministicZipPackager zipPackager) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.toolModelFactory = Objects.requireNonNull(toolModelFactory, "toolModelFactory");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.projectGenerators = Objects.requireNonNull(projectGenerators, "projectGenerators");
        this.projectWriter = Objects.requireNonNull(projectWriter, "projectWriter");
        this.sourceTreeChecksum = Objects.requireNonNull(sourceTreeChecksum, "sourceTreeChecksum");
        this.manifestWriter = Objects.requireNonNull(manifestWriter, "manifestWriter");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.reportWriter = Objects.requireNonNull(reportWriter, "reportWriter");
        this.zipPackager = Objects.requireNonNull(zipPackager, "zipPackager");
    }

    public GenerationOutcome generate(Path specification, GenerationRequest request, Path outputRoot) {
        Path requestedRoot = normalizedOutputRoot(outputRoot);
        Path requestedArchive = archivePath(requestedRoot);
        zipPackager.requireArchiveAvailable(requestedRoot, requestedArchive);
        CompatibilityProfile profile = resolveProfile(request);
        ProjectGenerator projectGenerator = projectGenerators.require(profile);
        validateTargetConfiguration(request);
        var analysis = analyzer.analyze(specification, DEFAULT_MAX_SPECIFICATION_BYTES);
        List<McpToolDefinition> tools = toolModelFactory.create(analysis.document(), request);
        ExpectedToolCall expectedCall = expectedToolCallFactory.create(tools, request.validation());
        List<String> sensitiveNames = sensitiveNames(request, tools);

        GenerationContext context = new GenerationContext(
                analysis.document(), tools, request, profile, analysis.originalSpecification());
        GeneratedProjectFiles generated = execute(
                SOURCE_GENERATION_FAILED,
                "SOURCE_GENERATE",
                "Generated project sources could not be created",
                () -> projectGenerator.generate(context));
        GeneratedProjectFiles completeProject = includeOriginalSpecification(generated, context);
        Path projectRoot = projectWriter.write(requestedRoot, completeProject);
        SourceTreeChecksum.SourceSnapshot sourceSnapshot = sourceTreeChecksum.snapshot(projectRoot);
        String sourceChecksum = sourceSnapshot.checksum();
        manifestWriter.write(projectRoot, profile, analysis.document(), sourceChecksum, tools);

        ValidationReport report;
        try (ValidationWorkspace workspace = ValidationWorkspace.copyOf(projectRoot, projectWriter)) {
            report = validator.validate(new ValidationRequest(
                    workspace.root(), request.project().artifactId(), request.validationLevel(),
                    expectedToolSchemaFactory.create(tools), expectedCall, profile));
            if (report == null) {
                throw GeneratorException.system(
                        INTERNAL_ERROR, "VALIDATION", "Generated project validation returned no report", null);
            }
        } catch (GeneratorException exception) {
            preserveValidationFailure(projectRoot, exception, sensitiveNames);
            throw exception;
        } catch (RuntimeException exception) {
            GeneratorException wrapped = GeneratorException.system(
                    INTERNAL_ERROR, "VALIDATION", "Generated project validation failed", exception);
            preserveValidationFailure(projectRoot, wrapped, sensitiveNames);
            throw wrapped;
        }

        reportWriter.write(projectRoot, report, sensitiveNames);
        Path archive = null;
        if (report.status() == VALIDATED) {
            archive = archivePath(projectRoot);
            try {
                zipPackager.packageProject(projectRoot, archive, sourceSnapshot);
            } catch (GeneratorException exception) {
                replaceReportAfterPackagingFailure(projectRoot, exception, sensitiveNames);
                throw exception;
            }
        }
        return new GenerationOutcome(projectRoot, archive, report.status(), sourceChecksum);
    }

    private CompatibilityProfile resolveProfile(GenerationRequest request) {
        if (request == null) {
            throw GeneratorException.user(
                    TARGET_PROFILE_NOT_FOUND, "TARGET_VALIDATE", "The requested compatibility profile is unavailable");
        }
        return profiles.find(request.targetProfileId()).orElseThrow(() -> GeneratorException.user(
                TARGET_PROFILE_NOT_FOUND, "TARGET_VALIDATE", "The requested compatibility profile is unavailable"));
    }

    private void validateTargetConfiguration(GenerationRequest request) {
        if (request.project() == null || request.validationLevel() == null) {
            throw GeneratorException.user(
                    TARGET_COMBINATION_UNSUPPORTED, "TARGET_VALIDATE", "Generation target configuration is incomplete");
        }
    }

    private GeneratedProjectFiles includeOriginalSpecification(
            GeneratedProjectFiles generated,
            GenerationContext context) {
        if (generated == null || generated.files() == null) {
            throw GeneratorException.system(
                    SOURCE_GENERATION_FAILED, "SOURCE_GENERATE", "Project generator returned no files", null);
        }
        String sourcePath = "openapi/source." + context.document().sourceExtension();
        Map<String, byte[]> files = new LinkedHashMap<>(generated.files());
        if (files.putIfAbsent(sourcePath, context.originalSpecification()) != null) {
            throw GeneratorException.user(
                    SOURCE_GENERATION_FAILED,
                    "SOURCE_GENERATE",
                    "Project generator attempted to replace the original specification copy");
        }
        return new GeneratedProjectFiles(Collections.unmodifiableMap(files));
    }

    private void preserveValidationFailure(
            Path projectRoot,
            GeneratorException failure,
            List<String> sensitiveNames) {
        ValidationReport failureReport = failureReport(failure);
        try {
            reportWriter.write(projectRoot, failureReport, sensitiveNames);
        } catch (GeneratorException reportFailure) {
            failure.addSuppressed(reportFailure);
        }
    }

    private void replaceReportAfterPackagingFailure(
            Path projectRoot,
            GeneratorException failure,
            List<String> sensitiveNames) {
        try {
            reportWriter.replaceAfterPipelineFailure(projectRoot, failureReport(failure), sensitiveNames);
        } catch (GeneratorException reportFailure) {
            failure.addSuppressed(reportFailure);
        }
    }

    private ValidationReport failureReport(GeneratorException failure) {
        return new ValidationReport(
                UNVERIFIED,
                List.of(new ValidationStageResult(
                        failure.stage(), FAILED, 0, 0, 1, failure.code().name() + ": " + failure.safeMessage())),
                List.of());
    }

    private List<String> sensitiveNames(GenerationRequest request, List<McpToolDefinition> tools) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (McpToolDefinition tool : tools) {
            tool.secretBindings().forEach(secret -> {
                addSensitiveName(names, secret.environmentVariable());
                addSensitiveName(names, secret.propertyName());
                addSensitiveName(names, secret.targetName());
            });
        }
        if (request.operations() != null) {
            request.operations().stream()
                    .filter(operation -> operation.parameters() != null)
                    .forEach(operation -> operation.parameters().forEach((parameterName, override) -> {
                        if (override != null
                                && override.source() == McpToolDefinition.ParameterSource.SERVER_SECRET) {
                            addSensitiveName(names, parameterName);
                            addSensitiveName(names, override.environmentVariable());
                        }
                    }));
        }
        return List.copyOf(names);
    }

    private void addSensitiveName(TreeSet<String> names, String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            names.add(candidate);
        }
    }

    private Path normalizedOutputRoot(Path outputRoot) {
        if (outputRoot == null) {
            throw GeneratorException.user(
                    TARGET_COMBINATION_UNSUPPORTED, "TARGET_VALIDATE", "Project output path is required");
        }
        return outputRoot.toAbsolutePath().normalize();
    }

    private Path archivePath(Path projectRoot) {
        Path name = projectRoot.getFileName();
        Path parent = projectRoot.getParent();
        if (name == null || parent == null) {
            throw GeneratorException.user(
                    TARGET_COMBINATION_UNSUPPORTED, "PACKAGE", "Project output cannot be packaged at this path");
        }
        return parent.resolve(name + ".zip");
    }

    private <T> T execute(
            GeneratorErrorCode errorCode,
            String stage,
            String safeMessage,
            Supplier<T> action) {
        try {
            return action.get();
        } catch (GeneratorException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw GeneratorException.system(errorCode, stage, safeMessage, exception);
        }
    }
}
