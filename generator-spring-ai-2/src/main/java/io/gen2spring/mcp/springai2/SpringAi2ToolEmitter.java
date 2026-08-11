package io.gen2spring.mcp.springai2;

import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedToolSources;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ToolEmitter;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;

public final class SpringAi2ToolEmitter implements ToolEmitter {
    @Override
    public GeneratedToolSources emit(GenerationContext context) {
        CompatibilityProfile profile = context == null ? null : context.profile();
        return new GeneratedToolSources(new JavaSourceRenderer(profile).render(context));
    }
}
