package io.gen2spring.mcp.application.port.outbound;

import java.nio.file.Path;

public interface SourceSnapshotter {
    String calculate(GeneratedProjectFiles projectFiles);

    String calculate(Path projectRoot);

    SourceSnapshot snapshot(Path projectRoot);
}
