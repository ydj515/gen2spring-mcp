package io.gen2spring.mcp.application.port.outbound;

import io.gen2spring.mcp.application.usecase.GenerationContext;

public interface ProjectGenerator {
    GeneratedProjectFiles generate(GenerationContext context);
}
