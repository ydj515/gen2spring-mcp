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
import java.util.Objects;
import java.util.TreeMap;

public final class GenerationContracts {
    private GenerationContracts() {}

    public interface ProjectGenerator {
        GeneratedProjectFiles generate(GenerationContext context);
    }

    public interface ToolEmitter {
        GeneratedToolSources emit(GenerationContext context);
    }

    public interface GeneratedProjectValidator {
        ValidationReport validate(ValidationRequest request);

        default ValidationReport validate(
                ValidationRequest request,
                GenerationProgressListener listener) {
            Objects.requireNonNull(listener, "listener");
            return validate(request);
        }
    }

    public enum ProgressStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

    public record GenerationProgress(String stage, ProgressStatus status) {
        public static final List<String> STAGES = List.of(
                "ANALYZE",
                "GENERATE",
                "COMPILE",
                "APPLICATION_CONTEXT",
                "MCP_INITIALIZE",
                "MCP_TOOLS_LIST",
                "MCP_TOOL_CALL",
                "PACKAGE");

        public GenerationProgress {
            if (!STAGES.contains(stage) || status == null) {
                throw new IllegalArgumentException("Generation progress is invalid");
            }
        }
    }

    @FunctionalInterface
    public interface GenerationProgressListener {
        GenerationProgressListener NOOP = progress -> {};

        void onProgress(GenerationProgress progress);
    }

    public record GenerationContext(
            OpenApiDocument document,
            List<McpToolDefinition> tools,
            GenerationRequest request,
            CompatibilityProfile profile,
            byte[] originalSpecification) {}

    public record GeneratedProjectFiles(Map<String, byte[]> files) {}

    public record GeneratedToolSources(Map<String, byte[]> files) {
        private static final String INVALID_SOURCES_MESSAGE = "Generated Tool sources are invalid";

        public GeneratedToolSources {
            files = immutableFileBytes(files);
        }

        @Override
        public Map<String, byte[]> files() {
            return immutableFileBytes(files);
        }

        private static Map<String, byte[]> immutableFileBytes(Map<String, byte[]> source) {
            if (source == null) {
                throw new IllegalArgumentException(INVALID_SOURCES_MESSAGE);
            }
            Map<String, byte[]> sorted = new TreeMap<>();
            source.forEach((path, bytes) -> {
                if (invalidSourcePath(path) || bytes == null) {
                    throw new IllegalArgumentException(INVALID_SOURCES_MESSAGE);
                }
                sorted.put(path, bytes.clone());
            });
            return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        }

        private static boolean invalidSourcePath(String path) {
            if (path == null || path.isBlank() || path.startsWith("/")
                    || (path.length() >= 3 && isAsciiLetter(path.charAt(0))
                    && path.charAt(1) == ':' && path.charAt(2) == '/')
                    || path.indexOf('\\') >= 0
                    || path.chars().anyMatch(Character::isISOControl)) {
                return true;
            }
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isAsciiLetter(char value) {
            return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
        }
    }

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

    public enum ExpectedUpstreamOutcome { RESPONSE, DISCONNECT }

    public record ExpectedUpstreamInteraction(
            Map<String, Object> internalParameters,
            ExpectedUpstreamOutcome outcome,
            ExpectedUpstreamResponse response) {
        public ExpectedUpstreamInteraction {
            if (internalParameters == null || outcome == null
                    || outcome == ExpectedUpstreamOutcome.RESPONSE && response == null
                    || outcome == ExpectedUpstreamOutcome.DISCONNECT && response != null) {
                throw new IllegalArgumentException("Expected upstream interaction is incomplete");
            }
            internalParameters = immutableMap(internalParameters, false, false);
        }
    }

    public record ExpectedToolCall(
            McpToolDefinition tool,
            Map<String, Object> arguments,
            List<ExpectedUpstreamInteraction> upstreamInteractions,
            Object expectedResult) {
        public ExpectedToolCall {
            if (tool == null || arguments == null || upstreamInteractions == null || upstreamInteractions.isEmpty()
                    || upstreamInteractions.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Expected Tool call is incomplete");
            }
            arguments = immutableMap(arguments, false, false);
            upstreamInteractions = List.copyOf(upstreamInteractions);
            expectedResult = immutableJsonValue(expectedResult, true, true);
        }

        public ExpectedToolCall(
                McpToolDefinition tool,
                Map<String, Object> arguments,
                ExpectedUpstreamResponse upstreamResponse,
                Object expectedResult) {
            this(tool, arguments, List.of(new ExpectedUpstreamInteraction(
                    Map.of(), ExpectedUpstreamOutcome.RESPONSE, upstreamResponse)), expectedResult);
        }

        public ExpectedToolCall(McpToolDefinition tool, Map<String, Object> arguments) {
            this(tool, arguments, legacyResponse(tool), legacyResult(tool));
        }

        public ExpectedUpstreamResponse upstreamResponse() {
            ExpectedUpstreamInteraction first = upstreamInteractions.getFirst();
            if (first.outcome() != ExpectedUpstreamOutcome.RESPONSE) {
                throw new IllegalStateException("Expected upstream interaction has no response");
            }
            return first.response();
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
