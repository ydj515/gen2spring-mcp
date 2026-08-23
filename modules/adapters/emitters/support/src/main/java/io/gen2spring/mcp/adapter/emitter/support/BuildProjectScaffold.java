package io.gen2spring.mcp.adapter.emitter.support;

import java.util.Map;

public interface BuildProjectScaffold {
    Map<String, byte[]> render(ProjectScaffoldModel model);
}
