package io.gen2spring.mcp.application.port.outbound;

import java.nio.file.Path;

public interface ProjectWorkspace {
    Path write(Path outputRoot, GeneratedProjectFiles projectFiles);

    ValidationWorkspace openValidationWorkspace(Path canonicalProjectRoot);

    interface ValidationWorkspace extends AutoCloseable {
        Path root();

        @Override
        void close();
    }
}
