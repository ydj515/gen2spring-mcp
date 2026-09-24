package io.gen2spring.mcp.app.web.infrastructure.hosted.submission;

import io.gen2spring.mcp.app.web.application.hosted.port.out.HostedSpecificationProcessor;
import io.gen2spring.mcp.application.generation.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.application.generation.usecase.GenerationPreview;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

public final class GeneratorHostedSpecificationProcessor implements HostedSpecificationProcessor {
    private static final int MAX_SPECIFICATION_BYTES = 10 * 1024 * 1024;
    private final GeneratorRuntime generator;
    private final Path workRoot;

    public GeneratorHostedSpecificationProcessor(GeneratorRuntime generator, Path workRoot) {
        this.generator = Objects.requireNonNull(generator, "generator");
        if (workRoot == null || !workRoot.isAbsolute() || Files.isSymbolicLink(workRoot)
                || !Files.isDirectory(workRoot)) {
            throw new IllegalArgumentException("Hosted Web configuration is invalid");
        }
        this.workRoot = workRoot.toAbsolutePath().normalize();
    }

    @Override
    public SpecificationAnalysisView analyze(byte[] source, String contentType) {
        return withPrivateCopy(source, contentType, temporary -> {
            var analysis = generator.analyzer().analyze(temporary, MAX_SPECIFICATION_BYTES);
            if (!Arrays.equals(source, analysis.originalSpecification())) {
                throw new IllegalStateException("Specification changed during analysis");
            }
            return SpecificationAnalysisView.from(analysis.document());
        });
    }

    @Override
    public void validateConfiguration(byte[] configurationBytes) {
        generator.configurationParser().parseJson(configurationBytes);
    }

    @Override
    public GenerationPreview preview(byte[] source, String contentType, byte[] configurationBytes) {
        var configuration = generator.configurationParser().parseJson(configurationBytes);
        return withPrivateCopy(source, contentType, temporary -> generator.pipeline().preview(temporary, configuration));
    }

    private <T> T withPrivateCopy(byte[] source, String contentType, FileAction<T> action) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(workRoot, "hosted-specification-",
                    contentType.contains("json") ? ".json" : ".yaml");
            try {
                Files.setPosixFilePermissions(temporary,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // The temp file API creates an owner-private file on non-POSIX platforms.
            }
            Files.write(temporary, source);
            return action.run(temporary);
        } catch (Error fatal) {
            throw fatal;
        } catch (Exception failure) {
            throw new IllegalStateException("Hosted specification processing failed", failure);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            }
        }
    }

    @FunctionalInterface
    private interface FileAction<T> {
        T run(Path temporary) throws Exception;
    }
}
