package io.gen2spring.mcp.application.usecase;

@FunctionalInterface
public interface GenerationProgressListener {
    GenerationProgressListener NOOP = progress -> {};

    void onProgress(GenerationProgress progress);
}
