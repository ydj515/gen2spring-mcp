package io.gen2spring.mcp.web;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationOutcome;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationProgressListener;
import java.nio.file.Path;

@FunctionalInterface
interface GenerationExecutor {
    GenerationOutcome generate(
            Path specification,
            GenerationRequest request,
            Path outputRoot,
            GenerationProgressListener progress) throws Exception;
}
