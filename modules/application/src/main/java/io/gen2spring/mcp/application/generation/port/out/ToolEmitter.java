package io.gen2spring.mcp.application.generation.port.out;

import io.gen2spring.mcp.application.generation.model.GenerationContext;

public interface ToolEmitter {
    GeneratedToolSources emit(GenerationContext context);
}
