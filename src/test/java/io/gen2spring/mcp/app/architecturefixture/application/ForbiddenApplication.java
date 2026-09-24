package io.gen2spring.mcp.app.architecturefixture.application;

import io.gen2spring.mcp.bootstrap.architecturefixture.CompositionTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public record ForbiddenApplication(CompositionTarget composition) {
    public void inspect(Path path) {
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.execute(() -> Files.isDirectory(path));
        }
    }
}
