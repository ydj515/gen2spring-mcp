package io.gen2spring.mcp.adapter.emitter.springai2.render;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class AsyncProgrammingModelSourceRenderer implements ProgrammingModelSourceRenderer {
    private final AsyncToolClassRenderer toolRenderer = new AsyncToolClassRenderer();
    private final AsyncToolSpecificationRenderer specificationRenderer =
            new AsyncToolSpecificationRenderer();
    private final ReactiveRuntimeSourceRenderer runtimeRenderer = new ReactiveRuntimeSourceRenderer();
    private final RuntimeTelemetryRenderer telemetryRenderer;

    AsyncProgrammingModelSourceRenderer(CompatibilityProfile profile) {
        this.telemetryRenderer = new RuntimeTelemetryRenderer(profile);
    }

    @Override
    public Map<String, String> render(ProgrammingModelRenderRequest request) {
        Map<String, String> sources = new LinkedHashMap<>();
        String toolRoot = "src/main/java/" + request.packagePath() + "/generated/tool/";
        put(sources, toolRoot + request.domainClass() + "McpTools.java",
                toolRenderer.render(request.packageName(), request.domainClass(), request.tools()));
        put(sources, toolRoot + request.domainClass() + "McpToolSpecifications.java",
                specificationRenderer.render(
                        request.packageName(),
                        request.domainClass(),
                        request.tools(),
                        request.toolSchemas()));
        runtimeRenderer.render(request).forEach((path, source) -> put(sources, path, source));
        put(sources, "src/main/java/" + request.packagePath() + "/runtime/RuntimeTelemetry.java",
                telemetryRenderer.renderReactive(request.packageName(), request.tools()));
        return Collections.unmodifiableMap(sources);
    }

    private void put(Map<String, String> target, String path, String source) {
        if (target.putIfAbsent(path, source) != null) {
            throw JavaSourceRenderer.invalid("Generated source paths must be unique");
        }
    }
}
