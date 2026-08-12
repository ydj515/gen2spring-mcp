package io.gen2spring.mcp.springai1;

import io.gen2spring.mcp.application.port.outbound.GeneratedToolSources;
import io.gen2spring.mcp.application.usecase.GenerationContext;
import io.gen2spring.mcp.application.port.outbound.ToolEmitter;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;

public final class SpringAi1ToolEmitter implements ToolEmitter {
    @Override
    public GeneratedToolSources emit(GenerationContext context) {
        CompatibilityProfile profile = context == null ? null : context.profile();
        return new GeneratedToolSources(new JavaSourceRenderer(profile).render(context));
    }
}
