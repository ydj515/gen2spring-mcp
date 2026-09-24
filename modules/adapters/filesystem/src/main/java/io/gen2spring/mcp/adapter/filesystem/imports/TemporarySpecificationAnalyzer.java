package io.gen2spring.mcp.adapter.filesystem.imports;

import io.gen2spring.mcp.application.generation.port.out.SpecificationAnalyzer;
import io.gen2spring.mcp.application.hosted.imports.port.out.ImportedSpecificationAnalyzer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class TemporarySpecificationAnalyzer implements ImportedSpecificationAnalyzer {
    private final SpecificationAnalyzer analyzer;
    private final Path workRoot;

    public TemporarySpecificationAnalyzer(SpecificationAnalyzer analyzer, Path workRoot) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        if (workRoot == null || !workRoot.isAbsolute() || !Files.isDirectory(workRoot)
                || Files.isSymbolicLink(workRoot)) {
            throw new IllegalArgumentException("Hosted import configuration is invalid");
        }
        this.workRoot = workRoot;
    }

    @Override
    public byte[] analyze(byte[] source, String mediaType, int maxBytes) {
        if (source == null || source.length < 1 || source.length > maxBytes || mediaType == null) {
            throw new IllegalArgumentException("Imported specification is invalid");
        }
        Path temporary = null;
        try {
            String extension = mediaType.endsWith("json") ? ".json" : ".yaml";
            temporary = Files.createTempFile(workRoot, "specification-import-", extension);
            Files.write(temporary, source, StandardOpenOption.TRUNCATE_EXISTING);
            return analyzer.analyze(temporary, maxBytes).originalSpecification();
        } catch (Exception failure) {
            throw new IllegalStateException("Imported specification analysis failed", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // Workspace retention cleanup can retry without hiding the original failure.
                }
            }
        }
    }
}
