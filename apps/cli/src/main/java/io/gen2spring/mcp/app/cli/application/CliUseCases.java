package io.gen2spring.mcp.app.cli.application;

import static io.gen2spring.mcp.application.validation.ValidationStatus.UNVERIFIED;
import static io.gen2spring.mcp.application.validation.ValidationStatus.VALIDATED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.INTERNAL_ERROR;

import io.gen2spring.mcp.app.cli.application.port.out.CliFilePort;
import io.gen2spring.mcp.app.cli.application.port.out.ConfigurationPort;
import io.gen2spring.mcp.application.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;
import io.gen2spring.mcp.application.usecase.GenerationOutcome;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class CliUseCases {
    private static final long MAX_SPECIFICATION_BYTES = 10L * 1024 * 1024;

    private final ConfigurationPort configuration;
    private final CliFilePort files;
    private final SpecificationAnalyzer analyzer;
    private final GenerationExecutor generator;
    private final CompatibilityProfileRegistry profiles;

    public CliUseCases(ConfigurationPort configuration, CliFilePort files, SpecificationAnalyzer analyzer,
            GenerationExecutor generator, CompatibilityProfileRegistry profiles) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.files = Objects.requireNonNull(files, "files");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    public List<CompatibilityProfile> profiles() {
        return profiles.profiles();
    }

    public Inspection inspect(Path specificationPath, Path output) {
        CliFilePort.AnalysisOutput target = files.newAnalysisOutput(output);
        SpecificationAnalyzer.AnalysisResult result;
        try (CliFilePort.SpecificationCopy specification = files.specificationCopy(specificationPath)) {
            result = analyzer.analyze(specification.path(), MAX_SPECIFICATION_BYTES);
            specification.path();
            if (result == null || result.document() == null) {
                throw GeneratorException.system(INTERNAL_ERROR, "SPEC_ANALYSIS",
                        "Specification analysis returned no result", null);
            }
        }
        target.publish(SpecificationAnalysisView.from(result.document()));
        return new Inspection(target.path(), result.document().checksum());
    }

    public GenerationOutcome generate(Path specificationPath, Path configurationPath, Path output) {
        GenerationCommand request = configuration.read(configurationPath);
        CliFilePort.ProjectOutput target = files.newProjectOutput(output);
        GenerationOutcome outcome;
        try (CliFilePort.SpecificationCopy specification = files.specificationCopy(specificationPath)) {
            target.verifyAvailable();
            outcome = generator.generate(specification.path(), request, target.path());
            specification.path();
        }
        requireOutcome(target.path(), outcome);
        return outcome;
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

    public record Inspection(Path analysis, String checksum) {}

    @FunctionalInterface
    public interface GenerationExecutor {
        GenerationOutcome generate(Path specification, GenerationCommand request, Path outputRoot);
    }
}
