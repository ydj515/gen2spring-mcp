package io.gen2spring.mcp.adapter.emitter.springai2.render;

import java.util.Map;

interface ProgrammingModelSourceRenderer {
    Map<String, String> render(ProgrammingModelRenderRequest request);
}
