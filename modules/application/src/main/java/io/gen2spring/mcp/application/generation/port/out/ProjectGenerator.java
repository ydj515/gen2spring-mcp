package io.gen2spring.mcp.application.generation.port.out;

import io.gen2spring.mcp.application.generation.usecase.GenerationContext;

public interface ProjectGenerator {
    GeneratedProjectFiles generate(GenerationContext context);
}
