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
            Map<String, ExpectedTool> expectedTools) {}

    public record ExpectedTool(String description, Map<String, Object> inputSchema) {
        public ExpectedTool {
            if (description == null || inputSchema == null) {
                throw new IllegalArgumentException("Expected Tool metadata is incomplete");
            }
            inputSchema = immutableMap(inputSchema);
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

    private static Map<String, Object> immutableMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("Schema object keys must be non-blank strings");
            }
            copy.put(name, immutableJsonValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableJsonValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("Schema values must use JSON-compatible immutable types");
    }
}
