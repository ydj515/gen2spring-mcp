package io.gen2spring.mcp.application.port.outbound;

import io.gen2spring.mcp.application.usecase.GenerationProgress;

@FunctionalInterface
public interface GenerationProgressListener {
    GenerationProgressListener NOOP = progress -> {};

    void onProgress(GenerationProgress progress);
}
