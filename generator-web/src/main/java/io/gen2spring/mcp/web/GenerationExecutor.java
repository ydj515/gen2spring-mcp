package io.gen2spring.mcp.web;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.usecase.GenerationOutcome;
import io.gen2spring.mcp.application.usecase.GenerationProgressListener;
import java.nio.file.Path;

@FunctionalInterface
interface GenerationExecutor {
    GenerationOutcome generate(
            Path specification,
            GenerationCommand request,
            Path outputRoot,
            GenerationProgressListener progress) throws Exception;
}
