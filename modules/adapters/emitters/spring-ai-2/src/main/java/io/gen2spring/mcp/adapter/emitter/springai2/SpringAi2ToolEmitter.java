package io.gen2spring.mcp.adapter.emitter.springai2;

import io.gen2spring.mcp.application.generation.port.out.GeneratedToolSources;
import io.gen2spring.mcp.application.generation.usecase.GenerationContext;
import io.gen2spring.mcp.application.generation.port.out.ToolEmitter;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;

public final class SpringAi2ToolEmitter implements ToolEmitter {
    @Override
    public GeneratedToolSources emit(GenerationContext context) {
        CompatibilityProfile profile = context == null ? null : context.profile();
        return new GeneratedToolSources(new JavaSourceRenderer(profile).render(context));
    }
}
