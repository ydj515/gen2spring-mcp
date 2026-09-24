package io.gen2spring.mcp.application.generation.port.out;

import io.gen2spring.mcp.application.generation.progress.GenerationProgress;

@FunctionalInterface
public interface GenerationProgressListener {
    GenerationProgressListener NOOP = progress -> {};

    void onProgress(GenerationProgress progress);
}
