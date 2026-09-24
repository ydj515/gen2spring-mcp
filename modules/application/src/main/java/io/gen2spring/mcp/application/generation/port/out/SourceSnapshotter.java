package io.gen2spring.mcp.application.generation.port.out;

import java.nio.file.Path;

public interface SourceSnapshotter {
    String calculate(GeneratedProjectFiles projectFiles);

    String calculate(Path projectRoot);

    SourceSnapshot snapshot(Path projectRoot);
}
