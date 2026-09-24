package io.gen2spring.mcp.adapter.emitter.springai2.render;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class ReactiveRuntimeSourceRenderer {
    private final RuntimeSourceRenderer sharedRuntimeRenderer = new RuntimeSourceRenderer();
    private final ReactiveExecutorSourceRenderer executorRenderer = new ReactiveExecutorSourceRenderer();

    Map<String, String> render(ProgrammingModelRenderRequest request) {
        Map<String, String> sources = new LinkedHashMap<>(sharedRuntimeRenderer.render(
                request.packageName(),
                request.packagePath(),
                request.domainClass(),
                request.tools().getFirst().operationId(),
                request.hasTypedOutputs(),
                request.hasRetryPolicies() || request.hasPaginationPolicies(),
                request.hasPaginationPolicies()));
        String runtimePath = "src/main/java/" + request.packagePath() + "/runtime/";
        sources.remove(runtimePath + "ToolArgumentContext.java");
        sources.put(runtimePath + "OpenApiOperationExecutor.java",
                executorRenderer.render(
                        request.packageName(),
                        request.hasTypedOutputs(),
                        request.hasRetryPolicies(),
                        request.hasPaginationPolicies()));
        sources.remove("src/test/java/" + request.packagePath() + "/application/"
                + request.domainClass() + "McpApplicationTest.java");
        return Collections.unmodifiableMap(sources);
    }
}
