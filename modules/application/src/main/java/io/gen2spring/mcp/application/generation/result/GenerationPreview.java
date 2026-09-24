package io.gen2spring.mcp.application.generation.result;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.generation.validation.ExpectedTool;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.McpImplementation;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.AnalysisWarning;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.tool.OutputKind;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record GenerationPreview(
        CompatibilityProfile profile,
        List<Tool> tools,
        List<String> secretEnvironmentVariables,
        List<AnalysisWarning> warnings,
        List<String> generatedFilePaths,
        McpImplementation mcpImplementation) {
    public GenerationPreview(CompatibilityProfile profile, List<Tool> tools,
                             List<String> secretEnvironmentVariables, List<AnalysisWarning> warnings,
                             List<String> generatedFilePaths) {
        this(profile, tools, secretEnvironmentVariables, warnings, generatedFilePaths,
                McpImplementation.SPRING_AI_EXPLICIT);
    }
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public GenerationPreview {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(mcpImplementation, "mcpImplementation");
        tools = List.copyOf(tools);
        secretEnvironmentVariables = List.copyOf(secretEnvironmentVariables);
        warnings = List.copyOf(warnings);
        generatedFilePaths = List.copyOf(generatedFilePaths);
    }

    public record Tool(
            String operationId,
            String name,
            String description,
            Map<String, Object> inputSchema,
            Output output,
            Retry retry,
            Pagination pagination,
            ResponseNormalization responseNormalization) {
        public Tool {
            if (operationId == null || operationId.isBlank()
                    || name == null || name.isBlank()
                    || description == null || description.isBlank()
                    || output == null) {
                throw new IllegalArgumentException("Generation preview Tool is invalid");
            }
            inputSchema = new ExpectedTool(description, inputSchema).inputSchema();
        }

        public Tool(
                String operationId,
                String name,
                String description,
                Map<String, Object> inputSchema,
                ResponseNormalization responseNormalization) {
            this(operationId, name, description, inputSchema,
                    new Output("GENERIC_JSON", null), null, null, responseNormalization);
        }

        public static Tool from(ToolDefinition tool, Map<String, Object> inputSchema) {
            Objects.requireNonNull(tool, "tool");
            return new Tool(
                    tool.operationId(),
                    tool.name(),
                    tool.description(),
                    inputSchema,
                    Output.from(tool),
                    Retry.from(tool),
                    Pagination.from(tool),
                    ResponseNormalization.from(tool.execution().responseNormalization()));
        }
    }

    public record Output(String mode, String schemaChecksum) {
        public Output {
            if (!("TYPED".equals(mode) || "GENERIC_JSON".equals(mode))
                    || ("TYPED".equals(mode) && !isLowercaseSha256(schemaChecksum))
                    || ("GENERIC_JSON".equals(mode) && schemaChecksum != null)) {
                throw new IllegalArgumentException("Generation preview output is invalid");
            }
        }

        public static Output from(ToolDefinition tool) {
            Objects.requireNonNull(tool, "tool");
            boolean typed = tool.outputKind() == OutputKind.TYPED_DTO;
            return new Output(
                    typed ? "TYPED" : "GENERIC_JSON",
                    typed ? GenerationPreview.schemaChecksum(tool.output().resultSchema()) : null);
        }
    }

    public record Retry(
            List<Integer> statusCodes,
            boolean networkErrors,
            int maxRetries,
            long initialBackoffMillis,
            long maxBackoffMillis,
            boolean respectRetryAfter) {
        public Retry {
            statusCodes = statusCodes == null
                    ? List.of()
                    : statusCodes.stream().distinct().sorted().toList();
        }

        public static Retry from(ToolDefinition tool) {
            Objects.requireNonNull(tool, "tool");
            var policy = tool.execution().retryPolicy();
            return policy == null ? null : new Retry(
                    policy.statusCodes(),
                    policy.networkErrors(),
                    policy.maxRetries(),
                    policy.initialBackoffMillis(),
                    policy.maxBackoffMillis(),
                    policy.respectRetryAfter());
        }
    }

    public record Pagination(
            String requestParameter,
            String itemsPath,
            String nextValuePath,
            int maxPages,
            int maxItems) {
        public Pagination {
            if (requestParameter == null || requestParameter.isBlank()
                    || itemsPath == null || itemsPath.isBlank()
                    || nextValuePath == null || nextValuePath.isBlank()) {
                throw new IllegalArgumentException("Generation preview pagination is invalid");
            }
        }

        public static Pagination from(ToolDefinition tool) {
            Objects.requireNonNull(tool, "tool");
            var policy = tool.execution().paginationPolicy();
            return policy == null ? null : new Pagination(
                    policy.requestParameter(),
                    policy.itemsPointer(),
                    policy.nextValuePointer(),
                    policy.maxPages(),
                    policy.maxItems());
        }
    }

    public record ResponseNormalization(
            String dataPointer,
            String successCodePointer,
            List<Object> successValues,
            String errorMessagePointer,
            String totalCountPointer) {
        public ResponseNormalization {
            successValues = List.copyOf(successValues);
        }

        public static ResponseNormalization from(ResponseNormalizationPolicy policy) {
            if (policy == null) {
                return null;
            }
            return new ResponseNormalization(
                    policy.dataPointer(),
                    policy.successCodePointer(),
                    policy.successValues(),
                    policy.errorMessagePointer(),
                    policy.totalCountPointer());
        }
    }

    private static String schemaChecksum(ApiSchema schema) {
        try {
            byte[] canonical = OBJECT_MAPPER.writeValueAsBytes(canonicalSchema(schema));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Generation preview schema checksum could not be created", exception);
        }
    }

    private static Map<String, Object> canonicalSchema(ApiSchema schema) {
        if (schema == null || schema.type() == null || !schema.supported()) {
            throw new IllegalArgumentException("Generation preview output schema is invalid");
        }
        Map<String, Object> result = new TreeMap<>();
        result.put("type", switch (schema.type()) {
            case STRING -> "string";
            case INTEGER -> "integer";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case ARRAY -> "array";
            case OBJECT -> "object";
            case COMPOSED -> "composed";
        });
        if (schema.nullable()) {
            result.put("nullable", true);
        }
        if (schema.format() != null && !schema.format().isBlank()) {
            result.put("format", schema.format());
        }
        if (schema.type() == io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.STRING
                && schema.enumValues() != null && !schema.enumValues().isEmpty()) {
            result.put("enum", List.copyOf(schema.enumValues()));
        }
        if (schema.minimum() != null) {
            result.put("minimum", schema.minimum());
        }
        if (schema.maximum() != null) {
            result.put("maximum", schema.maximum());
        }
        if (schema.minLength() != null) {
            result.put("minLength", schema.minLength());
        }
        if (schema.maxLength() != null) {
            result.put("maxLength", schema.maxLength());
        }
        if (schema.minItems() != null) {
            result.put("minItems", schema.minItems());
        }
        if (schema.maxItems() != null) {
            result.put("maxItems", schema.maxItems());
        }
        if (schema.uniqueItems()) {
            result.put("uniqueItems", true);
        }
        if (schema.pattern() != null) {
            result.put("pattern", schema.pattern());
        }
        if (schema.type() == io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.ARRAY) {
            result.put("items", canonicalSchema(schema.items()));
        }
        if (schema.type() == io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.OBJECT) {
            Map<String, Object> properties = new TreeMap<>();
            Map<String, ApiSchema> source = schema.properties() == null ? Map.of() : schema.properties();
            source.forEach((name, property) -> {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("Generation preview output schema is invalid");
                }
                properties.put(name, canonicalSchema(property));
            });
            List<String> required = schema.requiredProperties() == null
                    ? List.of()
                    : schema.requiredProperties().stream().distinct().sorted().toList();
            if (required.stream().anyMatch(name -> !properties.containsKey(name))) {
                throw new IllegalArgumentException("Generation preview output schema is invalid");
            }
            result.put("properties", properties);
            result.put("required", required);
        }
        if (schema.type() == io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.COMPOSED) {
            if (schema.composition() == null) {
                throw new IllegalArgumentException("Generation preview output schema is invalid");
            }
            result.put("composition", Map.of(
                    "kind", schema.composition().kind().name(),
                    "branches", schema.composition().branches().stream()
                            .map(GenerationPreview::canonicalSchema).toList()));
        }
        return new LinkedHashMap<>(result);
    }

    private static boolean isLowercaseSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
