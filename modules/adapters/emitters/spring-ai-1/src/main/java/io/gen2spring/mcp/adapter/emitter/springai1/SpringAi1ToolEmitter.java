package io.gen2spring.mcp.adapter.emitter.springai1;

import io.gen2spring.mcp.adapter.emitter.springai1.render.JavaSourceRenderer;
import io.gen2spring.mcp.application.generation.model.GenerationContext;
import io.gen2spring.mcp.application.generation.port.out.GeneratedToolSources;
import io.gen2spring.mcp.application.generation.port.out.ToolEmitter;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;

public final class SpringAi1ToolEmitter implements ToolEmitter {
    @Override
    public GeneratedToolSources emit(GenerationContext context) {
        CompatibilityProfile profile = context == null ? null : context.profile();
        return new GeneratedToolSources(new JavaSourceRenderer(profile).render(context));
    }
}
