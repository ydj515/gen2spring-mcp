package io.gen2spring.mcp.app.web.infrastructure.local.job;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.port.outbound.GenerationProgressListener;
import io.gen2spring.mcp.application.usecase.GenerationOutcome;
import java.nio.file.Path;

@FunctionalInterface
public interface GenerationExecutor {
    GenerationOutcome generate(
            Path specification,
            GenerationCommand request,
            Path outputRoot,
            GenerationProgressListener progress) throws Exception;
}
