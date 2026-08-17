package io.gen2spring.mcp.application.validation;

import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExpectedToolCall(
        ToolDefinition tool,
        Map<String, Object> arguments,
        List<ExpectedUpstreamInteraction> upstreamInteractions,
        Object expectedResult) {
    public ExpectedToolCall {
        if (tool == null || arguments == null || upstreamInteractions == null || upstreamInteractions.isEmpty()
                || upstreamInteractions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Expected Tool call is incomplete");
        }
        arguments = ValidationJsonValue.immutableMap(arguments, true, false);
        upstreamInteractions = List.copyOf(upstreamInteractions);
        expectedResult = ValidationJsonValue.immutableJsonValue(expectedResult, true, true);
    }

    public ExpectedToolCall(
            ToolDefinition tool,
            Map<String, Object> arguments,
            ExpectedUpstreamResponse upstreamResponse,
            Object expectedResult) {
        this(tool, arguments, List.of(new ExpectedUpstreamInteraction(
                Map.of(), ExpectedUpstreamOutcome.RESPONSE, upstreamResponse)), expectedResult);
    }

    public ExpectedToolCall(ToolDefinition tool, Map<String, Object> arguments) {
        this(tool, arguments, legacyResponse(tool), legacyResult(tool));
    }

    public ExpectedUpstreamResponse upstreamResponse() {
        ExpectedUpstreamInteraction first = upstreamInteractions.getFirst();
        if (first.outcome() != ExpectedUpstreamOutcome.RESPONSE) {
            throw new IllegalStateException("Expected upstream interaction has no response");
        }
        return first.response();
    }

    private static ExpectedUpstreamResponse legacyResponse(ToolDefinition tool) {
        return new ExpectedUpstreamResponse(200, "application/json", legacyResult(tool));
    }

    private static Map<String, Object> legacyResult(ToolDefinition tool) {
        if (tool == null || tool.operationId() == null || tool.operationId().isBlank()) {
            throw new IllegalArgumentException("Expected Tool call is incomplete");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("validated", true);
        result.put("operationId", tool.operationId());
        return Collections.unmodifiableMap(result);
    }
}
