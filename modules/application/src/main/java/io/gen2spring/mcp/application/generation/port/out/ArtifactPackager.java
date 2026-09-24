package io.gen2spring.mcp.application.generation.port.out;

import java.nio.file.Path;

public interface ArtifactPackager {
    void requireArchiveAvailable(Path projectRoot, Path archive);

    byte[] packageProject(Path projectRoot, Path archive, SourceSnapshot expectedSource);
}
