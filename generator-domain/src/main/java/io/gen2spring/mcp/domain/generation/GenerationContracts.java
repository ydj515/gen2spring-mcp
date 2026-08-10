package io.gen2spring.mcp.domain.generation;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GenerationContracts {
    private GenerationContracts() {}

    public interface ProjectGenerator {
        GeneratedProjectFiles generate(GenerationContext context);
    }

    public interface GeneratedProjectValidator {
        ValidationReport validate(ValidationRequest request);
    }

    public record GenerationContext(
            OpenApiDocument document,
            List<McpToolDefinition> tools,
            GenerationRequest request,
            CompatibilityProfile profile,
            byte[] originalSpecification) {}

    public record GeneratedProjectFiles(Map<String, byte[]> files) {}

    public record ValidationRequest(
            Path projectRoot,
            String artifactId,
            GenerationRequest.ValidationLevel level,
            Map<String, ExpectedTool> expectedTools,
            ExpectedToolCall expectedToolCall,
            CompatibilityProfile profile) {
        public ValidationRequest {
            if (expectedToolCall == null || profile == null) {
                throw new IllegalArgumentException("Validation request expected Tool call is incomplete");
            }
        }

        public ValidationRequest(
                Path projectRoot,
                String artifactId,
                GenerationRequest.ValidationLevel level,
                Map<String, ExpectedTool> expectedTools,
                ExpectedToolCall expectedToolCall) {
            this(projectRoot, artifactId, level, expectedTools, expectedToolCall, CompatibilityProfile.p0());
        }
    }

    public record ExpectedTool(String description, Map<String, Object> inputSchema) {
        public ExpectedTool {
            if (description == null || inputSchema == null) {
                throw new IllegalArgumentException("Expected Tool metadata is incomplete");
            }
            inputSchema = immutableMap(inputSchema, false, false);
        }
    }

    public record ExpectedUpstreamResponse(int status, String contentType, Object body) {
        public ExpectedUpstreamResponse {
            body = immutableJsonValue(body, true, true);
        }
    }

    public record ExpectedToolCall(
            McpToolDefinition tool,
            Map<String, Object> arguments,
            ExpectedUpstreamResponse upstreamResponse,
            Object expectedResult) {
        public ExpectedToolCall {
            if (tool == null || arguments == null || upstreamResponse == null) {
                throw new IllegalArgumentException("Expected Tool call is incomplete");
            }
            arguments = immutableMap(arguments, false, false);
            expectedResult = immutableJsonValue(expectedResult, true, true);
        }

        public ExpectedToolCall(McpToolDefinition tool, Map<String, Object> arguments) {
            this(tool, arguments, legacyResponse(tool), legacyResult(tool));
        }
    }
    public enum ValidationStatus { VALIDATED, UNVERIFIED }
    public enum StageStatus { SUCCESS, FAILED, SKIPPED }

    public record ValidationStageResult(
            String stage,
            StageStatus status,
            long durationMillis,
            int warningCount,
            int errorCount,
            String summary) {}

    public record ObservedTool(String name, String description, boolean inputSchemaPresent) {}

    public record ValidationReport(
            ValidationStatus status,
            List<ValidationStageResult> stages,
            List<ObservedTool> tools) {}

    public record GenerationOutcome(
            Path projectRoot,
            Path archive,
            ValidationStatus validationStatus,
            String sourceChecksum) {}

    private static ExpectedUpstreamResponse legacyResponse(McpToolDefinition tool) {
        return new ExpectedUpstreamResponse(200, "application/json", legacyResult(tool));
    }

    private static Map<String, Object> legacyResult(McpToolDefinition tool) {
        if (tool == null || tool.operationId() == null || tool.operationId().isBlank()) {
            throw new IllegalArgumentException("Expected Tool call is incomplete");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("validated", true);
        result.put("operationId", tool.operationId());
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> immutableMap(
            Map<?, ?> source,
            boolean allowNull,
            boolean allowBlankKeys) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String name) || invalidObjectKey(name, allowBlankKeys)) {
                throw new IllegalArgumentException(allowBlankKeys
                        ? "Response object keys must be safe JSON strings"
                        : "Schema object keys must be non-blank strings");
            }
            copy.put(name, immutableJsonValue(value, allowNull, allowBlankKeys));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static boolean invalidObjectKey(String name, boolean allowBlankKeys) {
        return allowBlankKeys
                ? name.chars().anyMatch(Character::isISOControl)
                : name.isBlank();
    }

    private static Object immutableJsonValue(
            Object value,
            boolean allowNull,
            boolean allowBlankKeys) {
        if (value == null) {
            if (allowNull) {
                return null;
            }
            throw new IllegalArgumentException("Schema values must use JSON-compatible immutable types");
        }
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map, allowNull, allowBlankKeys);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableJsonValue(item, allowNull, allowBlankKeys)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) {
            return value;
        }
        if (value instanceof Float number && Float.isFinite(number)) {
            return value;
        }
        if (value instanceof Double number && Double.isFinite(number)) {
            return value;
        }
        throw new IllegalArgumentException("Schema values must use JSON-compatible immutable types");
    }
}
