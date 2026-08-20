package io.gen2spring.mcp.application.usecase;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.INTERNAL_ERROR;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.RUNTIME_METADATA_INVALID;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_COMBINATION_UNSUPPORTED;
import static io.gen2spring.mcp.application.validation.StageStatus.FAILED;
import static io.gen2spring.mcp.application.validation.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.application.validation.ValidationStatus.VALIDATED;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.planning.GenerationPlanner;
import io.gen2spring.mcp.application.planning.ProjectGeneratorRegistry;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataDocumentFactory;
import io.gen2spring.mcp.application.port.outbound.ArtifactPackager;
import io.gen2spring.mcp.domain.error.GeneratorErrorCode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.application.port.outbound.GeneratedProjectFiles;
import io.gen2spring.mcp.application.port.outbound.GeneratedProjectValidator;
import io.gen2spring.mcp.application.port.outbound.ManifestWriter;
import io.gen2spring.mcp.application.port.outbound.ProjectWorkspace;
import io.gen2spring.mcp.application.port.outbound.ProjectGenerator;
import io.gen2spring.mcp.application.port.outbound.SourceSnapshot;
import io.gen2spring.mcp.application.port.outbound.SourceSnapshotter;
import io.gen2spring.mcp.application.port.outbound.ValidationReportStore;
import io.gen2spring.mcp.application.validation.ValidationReport;
import io.gen2spring.mcp.application.validation.ValidationRequest;
import io.gen2spring.mcp.application.validation.ValidationStageResult;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.ParameterSource;
import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;
import io.gen2spring.mcp.application.toolmodel.ToolModelFactory;
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
    private final GenerationPlanner planner;
    private final ProjectWorkspace projectWorkspace;
    private final SourceSnapshotter sourceSnapshotter;
    private final ManifestWriter manifestWriter;
    private final GeneratedProjectValidator validator;
    private final ValidationReportStore reportStore;
    private final ArtifactPackager artifactPackager;
    private final RuntimeMetadataDocumentFactory metadataFactory;
    private final CanonicalRuntimeMetadataCodec metadataCodec;

    public GenerationPipeline(
            SpecificationAnalyzer analyzer,
            ToolModelFactory toolModelFactory,
            CompatibilityProfile profile,
            ProjectGenerator projectGenerator,
            ProjectWorkspace projectWorkspace,
            SourceSnapshotter sourceSnapshotter,
            ManifestWriter manifestWriter,
            GeneratedProjectValidator validator,
            ValidationReportStore reportStore,
            ArtifactPackager artifactPackager) {
        this(
                analyzer,
                toolModelFactory,
                CompatibilityProfileRegistry.of(List.of(Objects.requireNonNull(profile, "profile"))),
                ProjectGeneratorRegistry.of(Map.of(
                        profile.generatorModule(), Objects.requireNonNull(projectGenerator, "projectGenerator"))),
                projectWorkspace,
                sourceSnapshotter,
                manifestWriter,
                validator,
                reportStore,
                artifactPackager);
    }

    public GenerationPipeline(
            SpecificationAnalyzer analyzer,
            ToolModelFactory toolModelFactory,
            CompatibilityProfileRegistry profiles,
            ProjectGeneratorRegistry projectGenerators,
            ProjectWorkspace projectWorkspace,
            SourceSnapshotter sourceSnapshotter,
            ManifestWriter manifestWriter,
            GeneratedProjectValidator validator,
            ValidationReportStore reportStore,
            ArtifactPackager artifactPackager) {
        this(
                analyzer,
                new GenerationPlanner(toolModelFactory, profiles, projectGenerators),
                projectWorkspace,
                sourceSnapshotter,
                manifestWriter,
                validator,
                reportStore,
                artifactPackager);
    }

    public GenerationPipeline(
            SpecificationAnalyzer analyzer,
            GenerationPlanner planner,
            ProjectWorkspace projectWorkspace,
            SourceSnapshotter sourceSnapshotter,
            ManifestWriter manifestWriter,
            GeneratedProjectValidator validator,
            ValidationReportStore reportStore,
            ArtifactPackager artifactPackager) {
        this(
                analyzer,
                planner,
                projectWorkspace,
                sourceSnapshotter,
                manifestWriter,
                validator,
                reportStore,
                artifactPackager,
                new RuntimeMetadataDocumentFactory(),
                new CanonicalRuntimeMetadataCodec());
    }

    public GenerationPipeline(
            SpecificationAnalyzer analyzer,
            GenerationPlanner planner,
            ProjectWorkspace projectWorkspace,
            SourceSnapshotter sourceSnapshotter,
            ManifestWriter manifestWriter,
            GeneratedProjectValidator validator,
            ValidationReportStore reportStore,
            ArtifactPackager artifactPackager,
            RuntimeMetadataDocumentFactory metadataFactory,
            CanonicalRuntimeMetadataCodec metadataCodec) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.projectWorkspace = Objects.requireNonNull(projectWorkspace, "projectWorkspace");
        this.sourceSnapshotter = Objects.requireNonNull(sourceSnapshotter, "sourceSnapshotter");
        this.manifestWriter = Objects.requireNonNull(manifestWriter, "manifestWriter");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.reportStore = Objects.requireNonNull(reportStore, "reportStore");
        this.artifactPackager = Objects.requireNonNull(artifactPackager, "artifactPackager");
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory");
        this.metadataCodec = Objects.requireNonNull(metadataCodec, "metadataCodec");
    }

    public GenerationOutcome generate(Path specification, GenerationCommand request, Path outputRoot) {
        return generate(specification, request, outputRoot, GenerationProgressListener.NOOP);
    }

    public GenerationOutcome generate(
            Path specification,
            GenerationCommand request,
            Path outputRoot,
            GenerationProgressListener listener) {
        ProgressTracker progress = new ProgressTracker(listener);
        try {
            return generate(specification, request, outputRoot, progress);
        } catch (Error | RuntimeException failure) {
            progress.failActiveAndSkipRemaining();
            throw failure;
        }
    }

    private GenerationOutcome generate(
            Path specification,
            GenerationCommand request,
            Path outputRoot,
            ProgressTracker progress) {
        Path requestedRoot = normalizedOutputRoot(outputRoot);
        Path requestedArchive = archivePath(requestedRoot);
        artifactPackager.requireArchiveAvailable(requestedRoot, requestedArchive);
        GenerationPlanner.ResolvedTarget target = planner.resolve(request);
        progress.start("ANALYZE");
        var analysis = analyzer.analyze(specification, DEFAULT_MAX_SPECIFICATION_BYTES);
        progress.succeed("ANALYZE");
        progress.start("GENERATE");
        GenerationPlanner.PlannedGeneration plan = planner.plan(analysis.document(), request, target);
        List<ToolDefinition> tools = plan.tools();
        List<String> sensitiveNames = sensitiveNames(request, tools);

        GenerationContext context = new GenerationContext(
                analysis.document(), tools, request, plan.profile(), analysis.originalSpecification());
        GeneratedProjectFiles generated = execute(
                SOURCE_GENERATION_FAILED,
                "SOURCE_GENERATE",
                "Generated project sources could not be created",
                () -> plan.projectGenerator().generate(context));
        GeneratedProjectFiles completeProject = includeRuntimeMetadata(
                includeOriginalSpecification(generated, context), context);
        Path projectRoot = projectWorkspace.write(requestedRoot, completeProject);
        SourceSnapshot sourceSnapshot = sourceSnapshotter.snapshot(projectRoot);
        String sourceChecksum = sourceSnapshot.checksum();
        manifestWriter.write(projectRoot, plan.profile(), analysis.document(), sourceChecksum, tools);
        progress.succeed("GENERATE");

        ValidationReport report;
        progress.start("COMPILE");
        try (ProjectWorkspace.ValidationWorkspace workspace = projectWorkspace.openValidationWorkspace(projectRoot)) {
            report = validator.validate(new ValidationRequest(
                    workspace.root(), request.project().artifactId(), request.validationLevel(),
                    plan.expectedTools(), plan.expectedToolCall(), plan.profile()), progress);
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

        progress.completeValidation(report);
        reportStore.write(projectRoot, report, sensitiveNames);
        Path archive = null;
        if (report.status() == VALIDATED) {
            progress.start("PACKAGE");
            archive = archivePath(projectRoot);
            try {
                artifactPackager.packageProject(projectRoot, archive, sourceSnapshot);
            } catch (GeneratorException exception) {
                replaceReportAfterPackagingFailure(projectRoot, exception, sensitiveNames);
                throw exception;
            }
            progress.succeed("PACKAGE");
        } else {
            progress.skip("PACKAGE");
        }
        return new GenerationOutcome(projectRoot, archive, report.status(), sourceChecksum);
    }

    public GenerationPreview preview(Path specification, GenerationCommand request) {
        GenerationPlanner.ResolvedTarget target = planner.resolve(request);
        var analysis = analyzer.analyze(specification, DEFAULT_MAX_SPECIFICATION_BYTES);
        GenerationPlanner.PlannedGeneration plan = planner.plan(analysis.document(), request, target);
        GenerationContext context = new GenerationContext(
                analysis.document(), plan.tools(), request, plan.profile(), analysis.originalSpecification());
        GeneratedProjectFiles generated = execute(
                SOURCE_GENERATION_FAILED,
                "SOURCE_GENERATE",
                "Generated project sources could not be created",
                () -> plan.projectGenerator().generate(context));
        GeneratedProjectFiles completeProject = includeRuntimeMetadata(
                includeOriginalSpecification(generated, context), context);
        TreeSet<String> paths = new TreeSet<>(completeProject.files().keySet());
        paths.add(ManifestWriter.MANIFEST_FILE);
        paths.add(ValidationReportStore.REPORT_FILE);
        paths.add(request.project().artifactId() + ".zip");
        List<GenerationPreview.Tool> tools = plan.tools().stream()
                .map(tool -> GenerationPreview.Tool.from(
                        tool, plan.expectedTools().get(tool.name()).inputSchema()))
                .toList();
        return new GenerationPreview(
                plan.profile(),
                tools,
                plan.secretEnvironmentVariables(),
                analysis.document().warnings(),
                List.copyOf(paths));
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

    private GeneratedProjectFiles includeRuntimeMetadata(
            GeneratedProjectFiles generated,
            GenerationContext context) {
        RuntimeMetadataArtifact metadata = metadataCodec.encode(
                metadataFactory.create(context.document().checksum(), context.tools()));
        Map<String, byte[]> files = new LinkedHashMap<>(generated.files());
        if (files.putIfAbsent(RuntimeMetadataDocument.FILE_NAME, metadata.content()) != null) {
            throw GeneratorException.user(
                    RUNTIME_METADATA_INVALID,
                    "RUNTIME_METADATA",
                    "Runtime metadata could not be generated");
        }
        return new GeneratedProjectFiles(Collections.unmodifiableMap(files));
    }

    private void preserveValidationFailure(
            Path projectRoot,
            GeneratorException failure,
            List<String> sensitiveNames) {
        ValidationReport failureReport = failureReport(failure);
        try {
            reportStore.write(projectRoot, failureReport, sensitiveNames);
        } catch (GeneratorException reportFailure) {
            failure.addSuppressed(reportFailure);
        }
    }

    private void replaceReportAfterPackagingFailure(
            Path projectRoot,
            GeneratorException failure,
            List<String> sensitiveNames) {
        try {
            reportStore.replaceAfterPipelineFailure(projectRoot, failureReport(failure), sensitiveNames);
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

    private List<String> sensitiveNames(GenerationCommand request, List<ToolDefinition> tools) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ToolDefinition tool : tools) {
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
                                && override.source() == ParameterSource.SERVER_SECRET) {
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

    private static final class ProgressTracker implements GenerationProgressListener {
        private static final List<String> VALIDATION_STAGES = List.of(
                "COMPILE", "APPLICATION_CONTEXT", "MCP_INITIALIZE", "MCP_TOOLS_LIST", "MCP_TOOL_CALL");

        private final GenerationProgressListener listener;
        private final Map<String, ProgressStatus> statuses = new LinkedHashMap<>();

        private ProgressTracker(GenerationProgressListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            GenerationProgress.STAGES.forEach(stage -> statuses.put(stage, ProgressStatus.PENDING));
        }

        @Override
        public synchronized void onProgress(GenerationProgress progress) {
            Objects.requireNonNull(progress, "progress");
            if (progress.status() == ProgressStatus.PENDING) {
                throw invalidTransition();
            }
            if (progress.status() == ProgressStatus.RUNNING) {
                start(progress.stage());
                return;
            }
            finish(progress.stage(), progress.status());
        }

        private synchronized void start(String stage) {
            ProgressStatus current = status(stage);
            if (current == ProgressStatus.RUNNING) {
                return;
            }
            if (current != ProgressStatus.PENDING) {
                throw invalidTransition();
            }
            publish(stage, ProgressStatus.RUNNING);
        }

        private synchronized void succeed(String stage) {
            finish(stage, ProgressStatus.SUCCESS);
        }

        private synchronized void skip(String stage) {
            finish(stage, ProgressStatus.SKIPPED);
        }

        private synchronized void completeValidation(ValidationReport report) {
            for (ValidationStageResult result : report.stages()) {
                if (!VALIDATION_STAGES.contains(result.stage())) {
                    continue;
                }
                ProgressStatus terminal = switch (result.status()) {
                    case SUCCESS -> ProgressStatus.SUCCESS;
                    case FAILED -> ProgressStatus.FAILED;
                    case SKIPPED -> ProgressStatus.SKIPPED;
                };
                if (status(result.stage()) == ProgressStatus.PENDING && terminal != ProgressStatus.SKIPPED) {
                    start(result.stage());
                }
                finish(result.stage(), terminal);
            }
            for (String stage : VALIDATION_STAGES) {
                if (status(stage) == ProgressStatus.PENDING) {
                    skip(stage);
                } else if (status(stage) == ProgressStatus.RUNNING) {
                    finish(stage, ProgressStatus.FAILED);
                }
            }
        }

        private synchronized void failActiveAndSkipRemaining() {
            for (String stage : GenerationProgress.STAGES) {
                if (status(stage) == ProgressStatus.RUNNING) {
                    publish(stage, ProgressStatus.FAILED);
                    break;
                }
            }
            for (String stage : GenerationProgress.STAGES) {
                if (status(stage) == ProgressStatus.PENDING) {
                    publish(stage, ProgressStatus.SKIPPED);
                }
            }
        }

        private void finish(String stage, ProgressStatus terminal) {
            if (terminal == ProgressStatus.PENDING || terminal == ProgressStatus.RUNNING) {
                throw invalidTransition();
            }
            ProgressStatus current = status(stage);
            if (current == terminal) {
                return;
            }
            if (terminal == ProgressStatus.SKIPPED) {
                if (current != ProgressStatus.PENDING) {
                    throw invalidTransition();
                }
            } else if (current != ProgressStatus.RUNNING) {
                throw invalidTransition();
            }
            publish(stage, terminal);
        }

        private ProgressStatus status(String stage) {
            ProgressStatus status = statuses.get(stage);
            if (status == null) {
                throw invalidTransition();
            }
            return status;
        }

        private void publish(String stage, ProgressStatus status) {
            statuses.put(stage, status);
            listener.onProgress(new GenerationProgress(stage, status));
        }

        private IllegalStateException invalidTransition() {
            return new IllegalStateException("Generation progress transition is invalid");
        }
    }
}
