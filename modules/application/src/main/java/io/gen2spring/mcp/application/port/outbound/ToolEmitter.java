package io.gen2spring.mcp.application.port.outbound;

import io.gen2spring.mcp.application.usecase.GenerationContext;

public interface ToolEmitter {
    GeneratedToolSources emit(GenerationContext context);
}
