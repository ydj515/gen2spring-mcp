package io.gen2spring.mcp.adapter.emitter.springai2;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class SyncProgrammingModelSourceRenderer implements ProgrammingModelSourceRenderer {
    private final ToolClassRenderer toolRenderer = new ToolClassRenderer();
    private final ToolCallbackConfigurationRenderer toolCallbackRenderer =
            new ToolCallbackConfigurationRenderer();
    private final RuntimeSourceRenderer runtimeRenderer = new RuntimeSourceRenderer();
    private final RuntimeTelemetryRenderer telemetryRenderer;

    SyncProgrammingModelSourceRenderer(CompatibilityProfile profile) {
        this.telemetryRenderer = new RuntimeTelemetryRenderer(profile);
    }

    @Override
    public Map<String, String> render(ProgrammingModelRenderRequest request) {
        Map<String, String> sources = new LinkedHashMap<>();
        String generatedToolRoot = "src/main/java/" + request.packagePath() + "/generated/tool/";
        put(sources, generatedToolRoot + request.domainClass() + "McpTools.java",
                toolRenderer.render(request.packageName(), request.domainClass(), request.tools()));
        put(sources, generatedToolRoot + request.domainClass() + "McpToolCallbacks.java",
                toolCallbackRenderer.render(
                        request.packageName(), request.domainClass(), request.tools(), request.toolSchemas()));
        putAll(sources, runtimeRenderer.render(
                request.packageName(),
                request.packagePath(),
                request.domainClass(),
                request.tools().getFirst().operationId(),
                request.hasTypedOutputs(),
                request.hasRetryPolicies() || request.hasPaginationPolicies(),
                request.hasPaginationPolicies()));
        put(sources, "src/main/java/" + request.packagePath() + "/runtime/RuntimeTelemetry.java",
                telemetryRenderer.render(request.packageName(), request.tools()));
        return Collections.unmodifiableMap(sources);
    }

    private void putAll(Map<String, String> target, Map<String, String> values) {
        values.forEach((path, source) -> put(target, path, source));
    }

    private void put(Map<String, String> target, String path, String source) {
        if (target.putIfAbsent(path, source) != null) {
            throw JavaSourceRenderer.invalid("Generated source paths must be unique");
        }
    }
}
