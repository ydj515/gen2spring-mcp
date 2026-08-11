package io.gen2spring.mcp.springai1;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.ExpectedToolSchemaFactory;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class JavaSourceRenderer {
    private static final Pattern SOURCE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern SECRET_PROPERTY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,127}");
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

    private final ProjectFileRenderer projectRenderer;
    private final InputRecordRenderer inputRenderer;
    private final OutputRecordRenderer outputRenderer;
    private final ToolClassRenderer toolRenderer;
    private final ToolCallbackConfigurationRenderer toolCallbackConfigurationRenderer;
    private final OperationMetadataRenderer metadataRenderer;
    private final RuntimeSourceRenderer runtimeRenderer;
    private final ResponseRuntimeRenderer responseRuntimeRenderer;
    private final RuntimeTelemetryRenderer runtimeTelemetryRenderer;
    private final ExpectedToolSchemaFactory expectedToolSchemaFactory;
    private final ObjectMapper objectMapper;

    public JavaSourceRenderer() {
        this(defaultProfile());
    }

    public JavaSourceRenderer(CompatibilityProfile profile) {
        this.projectRenderer = new ProjectFileRenderer(profile);
        this.inputRenderer = new InputRecordRenderer();
        this.outputRenderer = new OutputRecordRenderer();
        this.toolRenderer = new ToolClassRenderer();
        this.toolCallbackConfigurationRenderer = new ToolCallbackConfigurationRenderer();
        this.metadataRenderer = new OperationMetadataRenderer();
        this.runtimeRenderer = new RuntimeSourceRenderer();
        this.responseRuntimeRenderer = new ResponseRuntimeRenderer();
        this.runtimeTelemetryRenderer = new RuntimeTelemetryRenderer(profile);
        this.expectedToolSchemaFactory = new ExpectedToolSchemaFactory();
        this.objectMapper = new ObjectMapper();
    }

    private static CompatibilityProfile defaultProfile() {
        return CompatibilityProfileRegistry.defaults()
                .find("spring-ai-1.1-java21-mvc-streamable")
                .orElseThrow();
    }

    public Map<String, byte[]> render(GenerationContext context) {
        var coordinates = projectRenderer.requireContext(context);
        String packageName = coordinates.packageName();
        String packagePath = packageName.replace('.', '/');
        String domainClass = upperCamel(requireSourceName(context.request().domain(), "domain"));
        List<McpToolDefinition> tools = orderedTools(context.tools());
        validateTools(tools);
        boolean hasTypedOutputs = tools.stream()
                .anyMatch(tool -> tool.outputKind() == McpToolDefinition.OutputKind.TYPED_DTO);
        Map<String, String> sources = new LinkedHashMap<>();
        putAll(sources, inputRenderer.render(packageName, packagePath, tools));
        for (McpToolDefinition tool : tools) {
            if (tool.outputKind() == McpToolDefinition.OutputKind.TYPED_DTO) {
                putAll(sources, outputRenderer.render(
                        packageName, packagePath, upperCamel(tool.operationId()), tool.output().resultSchema()));
            }
        }
        put(sources, "src/main/java/" + packagePath + "/generated/tool/" + domainClass + "McpTools.java",
                toolRenderer.render(packageName, domainClass, tools));
        put(sources, "src/main/java/" + packagePath + "/generated/tool/" + domainClass + "McpToolCallbacks.java",
                toolCallbackConfigurationRenderer.render(packageName, domainClass, tools, toolSchemas(tools)));
        put(sources, "src/main/java/" + packagePath + "/generated/metadata/" + domainClass + "Operations.java",
                metadataRenderer.render(packageName, domainClass, tools));
        putAll(sources, runtimeRenderer.render(
                packageName, packagePath, domainClass, tools.getFirst().operationId(), hasTypedOutputs));
        putAll(sources, responseRuntimeRenderer.render(packageName, packagePath));
        put(sources, "src/main/java/" + packagePath + "/runtime/RuntimeTelemetry.java",
                runtimeTelemetryRenderer.render(packageName, tools));
        put(sources, "src/test/java/" + packagePath + "/application/GeneratedJavaRuntimeTest.java",
                runtimeFeatureTest(packageName, context.profile().target().javaVersion()));

        Map<String, byte[]> result = new LinkedHashMap<>();
        sources.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().getBytes(UTF_8)));
        return Collections.unmodifiableMap(result);
    }

    private String runtimeFeatureTest(String packageName, int javaVersion) {
        return """
                package %s.application;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class GeneratedJavaRuntimeTest {
                    @Test
                    void usesTheConfiguredJavaRuntime() {
                        assertEquals(%d, Runtime.version().feature());
                    }
                }
                """.formatted(packageName, javaVersion);
    }

    static String upperCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean capitalize = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '-' || character == '_' || character == '.') {
                capitalize = true;
                continue;
            }
            result.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = false;
        }
        String identifier = result.toString();
        if (identifier.isEmpty() || Character.isDigit(identifier.charAt(0))) {
            identifier = "Generated" + identifier;
        }
        return JavaIdentifier.requireIdentifier(identifier);
    }

    static String lowerCamel(String value) {
        String upper = upperCamel(value);
        String candidate = Character.toLowerCase(upper.charAt(0)) + upper.substring(1);
        try {
            return JavaIdentifier.requireIdentifier(candidate);
        } catch (GeneratorException exception) {
            return JavaIdentifier.requireIdentifier(candidate + "Value");
        }
    }

    static String constantName(String value) {
        StringBuilder result = new StringBuilder();
        char previous = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '-' || character == '_' || character == '.') {
                if (!result.isEmpty() && result.charAt(result.length() - 1) != '_') {
                    result.append('_');
                }
            } else {
                if (Character.isUpperCase(character) && Character.isLowerCase(previous)
                        && !result.isEmpty() && result.charAt(result.length() - 1) != '_') {
                    result.append('_');
                }
                result.append(Character.toUpperCase(character));
            }
            previous = character;
        }
        return JavaIdentifier.requireIdentifier(result.toString());
    }

    static String requireSourceName(String value, String kind) {
        if (value == null || !SOURCE_NAME.matcher(value).matches()) {
            throw invalid("Generated " + kind + " names must contain only letters, digits, dots, hyphens, and underscores");
        }
        return value;
    }

    static String requireJsonPropertyName(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Generated JSON property names must be nonblank, at most 128 characters, and contain no controls");
        }
        return value;
    }

    static String nestedInputName(String value) {
        String jsonName = requireJsonPropertyName(value);
        StringBuilder normalized = new StringBuilder();
        boolean capitalize = false;
        for (int index = 0; index < jsonName.length(); index++) {
            char character = jsonName.charAt(index);
            if (isAsciiLetter(character) || isAsciiDigit(character)) {
                if (normalized.isEmpty()) {
                    if (isAsciiDigit(character)) {
                        normalized.append("value");
                    }
                    normalized.append(Character.toLowerCase(character));
                } else {
                    normalized.append(capitalize ? Character.toUpperCase(character) : character);
                }
                capitalize = false;
            } else {
                capitalize = true;
            }
        }
        if (normalized.isEmpty()) {
            throw invalid("Generated nested JSON property names must contain an ASCII letter or digit");
        }
        return lowerCamel(normalized.toString());
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z';
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    static String javaType(ApiSchema schema, String suggestedName) {
        if (schema == null || schema.type() == null || !schema.supported()) {
            throw invalid("Generated inputs require a supported schema");
        }
        if (schema.enumValues() != null && !schema.enumValues().isEmpty()) {
            return upperCamel(suggestedName) + "Value";
        }
        return switch (schema.type()) {
            case STRING -> "String";
            case INTEGER -> "int64".equals(schema.format()) ? "Long" : "Integer";
            case NUMBER -> "java.math.BigDecimal";
            case BOOLEAN -> "Boolean";
            case ARRAY -> "java.util.List<" + javaType(schema.items(), suggestedName + "Item") + ">";
            case OBJECT -> upperCamel(suggestedName);
        };
    }

    static GeneratorException invalid(String message) {
        return GeneratorException.user(SOURCE_GENERATION_FAILED, "spring-ai-1-render", message);
    }

    private Map<String, String> toolSchemas(List<McpToolDefinition> tools) {
        try {
            Map<String, String> schemas = new LinkedHashMap<>();
            expectedToolSchemaFactory.create(tools).forEach((name, tool) -> {
                try {
                    schemas.put(name, objectMapper.writeValueAsString(tool.inputSchema()));
                } catch (JsonProcessingException exception) {
                    throw new ToolSchemaSerializationException(exception);
                }
            });
            return Collections.unmodifiableMap(schemas);
        } catch (ToolSchemaSerializationException exception) {
            throw GeneratorException.system(SOURCE_GENERATION_FAILED, "spring-ai-1-render",
                    "Failed to render explicit MCP Tool input schema", exception.getCause());
        }
    }

    private static final class ToolSchemaSerializationException extends RuntimeException {
        private ToolSchemaSerializationException(JsonProcessingException cause) {
            super(cause);
        }
    }

    private List<McpToolDefinition> orderedTools(List<McpToolDefinition> values) {
        if (values == null) {
            return List.of();
        }
        List<McpToolDefinition> tools = new ArrayList<>(values);
        if (tools.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("Generated tool definitions cannot contain null entries");
        }
        tools.sort(Comparator.comparing(McpToolDefinition::operationId)
                .thenComparing(McpToolDefinition::name));
        return List.copyOf(tools);
    }

    private void validateTools(List<McpToolDefinition> tools) {
        Set<String> operationClasses = new HashSet<>();
        Set<String> methodNames = new HashSet<>();
        Set<String> constants = new HashSet<>();
        for (McpToolDefinition tool : tools) {
            requireSourceName(tool.operationId(), "operation");
            if (tool.name() == null || !TOOL_NAME.matcher(tool.name()).matches()) {
                throw invalid("Generated Tool names must be lower snake case and at most 64 characters");
            }
            if (tool.description() == null || tool.description().isBlank() || tool.description().length() > 1024) {
                throw invalid("Generated Tool descriptions are required and must be at most 1024 characters");
            }
            if (tool.execution() == null || tool.execution().method() == null || tool.execution().path() == null
                    || !tool.execution().path().startsWith("/") || tool.execution().path().contains("?")
                    || tool.execution().path().contains("#") || tool.execution().path().contains("\\")
                    || tool.execution().path().chars().anyMatch(character -> Character.isISOControl(character))) {
                throw invalid("Generated operation paths must be absolute normalized URI paths");
            }
            requireUnique(operationClasses, upperCamel(tool.operationId()) + "Input", "input class");
            requireUnique(methodNames, lowerCamel(tool.operationId()), "Tool method");
            requireUnique(constants, constantName(tool.operationId()), "operation constant");
            validateInputs(tool);
        }
    }

    private void validateInputs(McpToolDefinition tool) {
        Set<String> names = new HashSet<>();
        Set<String> javaNames = new HashSet<>();
        List<McpToolDefinition.McpInputDefinition> inputs = tool.inputs() == null ? List.of() : tool.inputs();
        for (var input : inputs) {
            if (input == null) {
                throw invalid("Generated inputs cannot contain null entries");
            }
            requireUnique(names, requireSourceName(input.name(), "input"), "input");
            requireJsonPropertyName(input.jsonName());
            String javaName = lowerCamel(input.name());
            requireUnique(javaNames, javaName, "Java input");
            if (!input.name().equals(javaName)) {
                throw invalid("Input names must be valid lower camel Java identifiers because Spring AI Tool schema "
                        + "generation cannot rename flat input names");
            }
            javaType(input.schema(), upperCamel(tool.operationId()) + upperCamel(input.name()));
        }
        if (tool.execution().bindings() != null) {
            for (var binding : tool.execution().bindings()) {
                if (binding == null || binding.targetLocation() == null
                        || !names.contains(binding.sourceName())) {
                    throw invalid("Generated parameter bindings must reference a visible input");
                }
                requireBindingName(binding.targetName());
            }
        }
        if (tool.secretBindings() != null) {
            for (var binding : tool.secretBindings()) {
                if (binding == null || binding.targetLocation() == null
                        || binding.propertyName() == null || !SECRET_PROPERTY.matcher(binding.propertyName()).matches()
                        || binding.environmentVariable() == null
                        || !ENVIRONMENT_VARIABLE.matcher(binding.environmentVariable()).matches()) {
                    throw invalid("Generated secret bindings require safe property and environment variable names");
                }
                requireBindingName(binding.targetName());
            }
        }
    }

    private void requireBindingName(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw invalid("Generated HTTP binding names must be safe non-empty values");
        }
    }

    private void requireUnique(Set<String> values, String value, String kind) {
        if (!values.add(value)) {
            throw invalid("Generated " + kind + " names must be unique");
        }
    }

    private void putAll(Map<String, String> target, Map<String, String> values) {
        values.forEach((path, source) -> put(target, path, source));
    }

    private void put(Map<String, String> target, String path, String source) {
        if (target.putIfAbsent(path, source) != null) {
            throw invalid("Generated source paths must be unique");
        }
    }
}
