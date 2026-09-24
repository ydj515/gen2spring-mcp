package io.gen2spring.mcp.application.generation.port.out;

import io.gen2spring.mcp.application.generation.usecase.GenerationProgress;

@FunctionalInterface
public interface GenerationProgressListener {
    GenerationProgressListener NOOP = progress -> {};

    void onProgress(GenerationProgress progress);
}
