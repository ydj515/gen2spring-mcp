package io.gen2spring.mcp.app.cli.infrastructure.file;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_FILE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_TOO_LARGE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.app.cli.application.exception.CliConfigurationException;
import io.gen2spring.mcp.app.cli.application.port.out.CliFilePort;
import io.gen2spring.mcp.application.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.domain.error.GeneratorErrorCode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

public final class LocalCliFiles implements CliFilePort {
    private static final long MAX_SPECIFICATION_BYTES = 10L * 1024 * 1024;
    private final LocalPathBoundary pathBoundary = new LocalPathBoundary();
    private final ObjectMapper json;
    private final PublicationHook publicationHook;

    public LocalCliFiles(ObjectMapper json) {
        this(json, PublicationHook.NONE);
    }

    public LocalCliFiles(ObjectMapper json, PublicationHook publicationHook) {
        this.json = Objects.requireNonNull(json, "json");
        this.publicationHook = Objects.requireNonNull(publicationHook, "publicationHook");
    }

    @Override
    public AnalysisOutput newAnalysisOutput(Path requested) {
        LocalPathBoundary.NewFile target = newOutputPath(requested, "Analysis output");
        return new AnalysisOutput() {
            @Override
            public Path path() {
                return target.path();
            }

            @Override
            public void publish(SpecificationAnalysisView analysis) {
                publishNewJson(target, analysis);
            }
        };
    }

    @Override
    public CliFilePort.ProjectOutput newProjectOutput(Path requested) {
        return newProjectPath(requested);
    }

    @Override
    public CliFilePort.SpecificationCopy specificationCopy(Path requested) {
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

    private void publishNewJson(LocalPathBoundary.NewFile targetBoundary, SpecificationAnalysisView value) {
        Path target = targetBoundary.path();
        ObjectNode jsonValue = json.valueToTree(value);
        byte[] bytes;
        try {
            bytes = (json.writerWithDefaultPrettyPrinter().writeValueAsString(jsonValue) + "\n").getBytes(UTF_8);
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
            stagingIdentity.refreshAfterWrite();
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

    public interface PublicationHook {
        PublicationHook NONE = new PublicationHook() {};

        default void afterStagingIdentityRecorded(Path staging) throws IOException {}

        default void afterTargetLinked(Path target, Path staging) throws IOException {}
    }

    private record ProjectOutput(LocalPathBoundary.NewFile project, LocalPathBoundary.NewFile archive)
            implements CliFilePort.ProjectOutput {
        @Override
        public Path path() {
            return project.path();
        }

        @Override
        public void verifyAvailable() {
            project.verifyAvailable();
            archive.verifyAvailable();
        }
    }
}
