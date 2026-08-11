package io.gen2spring.mcp.application;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.config.GenerationRequest.OperationSelection;
import io.gen2spring.mcp.domain.config.GenerationRequest.OutputSelection;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.config.GenerationRequest.ParameterOverride;
import io.gen2spring.mcp.domain.config.GenerationRequest.ProjectCoordinates;
import io.gen2spring.mcp.domain.config.GenerationRequest.ToolCallValidation;
import io.gen2spring.mcp.domain.config.GenerationRequest.ValidationConfiguration;
import io.gen2spring.mcp.domain.config.GenerationRequest.ValidationLevel;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicyValidator;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterSource;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.OutputKind;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingEndEvent;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.events.SequenceEndEvent;
import org.yaml.snakeyaml.events.SequenceStartEvent;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.resolver.Resolver;

public final class GenerationConfigurationParser {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern OPERATION_ID = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern PARAMETER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int MAX_OPERATIONS = 1_000;
    private static final int MAX_DESCRIPTION_CHARACTERS = 1_024;
    private static final int MAX_ARGUMENT_DEPTH = 16;
    private static final int MAX_ARGUMENT_MEMBERS = 256;
    private static final int MAX_ARGUMENT_ITEMS = 256;
    private static final int MAX_ARGUMENT_STRING_CHARACTERS = 2_048;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "project", "provider", "domain", "targetProfileId", "validationLevel", "validation", "operations");
    private static final Set<String> PROJECT_FIELDS = Set.of("groupId", "artifactId", "packageName");
    private static final Set<String> VALIDATION_FIELDS = Set.of("toolCall");
    private static final Set<String> TOOL_CALL_FIELDS = Set.of("operationId", "arguments");
    private static final Set<String> OPERATION_FIELDS = Set.of(
            "operationId", "enabled", "toolName", "toolDescription", "parameters", "responseNormalization", "output",
            "retry");
    private static final Set<String> OUTPUT_FIELDS = Set.of("mode");
    private static final Set<String> RETRY_FIELDS = Set.of(
            "statusCodes", "networkErrors", "maxRetries", "initialBackoffMillis", "maxBackoffMillis",
            "respectRetryAfter");
    private static final Set<String> PARAMETER_FIELDS = Set.of("source", "environmentVariable");
    private static final Set<String> RESPONSE_NORMALIZATION_FIELDS = Set.of(
            "dataPath", "successCodePath", "successValues", "errorMessagePath", "totalCountPath");

    private final ObjectMapper yaml;
    private final ObjectMapper json;
    private final CompatibilityProfileRegistry profiles;

    public GenerationConfigurationParser() {
        this(CompatibilityProfileRegistry.defaults());
    }

    public GenerationConfigurationParser(CompatibilityProfileRegistry profiles) {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        JsonFactory jsonFactory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.yaml = strictMapper(yamlFactory);
        this.json = strictMapper(jsonFactory);
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
    }

    private ObjectMapper strictMapper(JsonFactory factory) {
        return JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .nodeFactory(new JsonNodeFactory(true))
                .enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES)
                .build();
    }

    public GenerationRequest parseYaml(byte[] bytes) {
        return parse(bytes, InputFormat.YAML);
    }

    public GenerationRequest parseJson(byte[] bytes) {
        return parse(bytes, InputFormat.JSON);
    }

    private GenerationRequest parse(byte[] bytes, InputFormat format) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new GenerationConfigurationException("Generation configuration is invalid");
        }
        RawConfiguration raw;
        try {
            ObjectMapper mapper = format == InputFormat.YAML ? yaml : json;
            if (format == InputFormat.YAML) {
                validateYamlEvents(bytes);
            }
            JsonNode tree = mapper.readTree(bytes);
            validateTokenTypes(tree);
            raw = mapper.treeToValue(tree, RawConfiguration.class);
        } catch (IOException | RuntimeException exception) {
            throw new GenerationConfigurationException("Generation configuration is invalid", exception);
        }
        return validate(raw);
    }

    private enum InputFormat { YAML, JSON }

    private void validateYamlEvents(byte[] bytes) {
        Parser parser = new ParserImpl(
                new StreamReader(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)),
                new LoaderOptions());
        Resolver resolver = new Resolver();
        Deque<YamlContainer> containers = new ArrayDeque<>();
        Event event;
        while ((event = parser.getEvent()) != null) {
            if (event instanceof AliasEvent) {
                throw invalid("YAML aliases and explicit type tags are not supported");
            }
            if (event instanceof NodeEvent node && node.getAnchor() != null) {
                throw invalid("YAML anchors and aliases are not supported");
            }
            if (event instanceof MappingStartEvent mapping) {
                rejectExplicitTag(mapping);
                beginContainer(containers, true);
                continue;
            }
            if (event instanceof SequenceStartEvent sequence) {
                rejectExplicitTag(sequence);
                beginContainer(containers, false);
                continue;
            }
            if (event instanceof ScalarEvent scalar) {
                validateScalar(containers, scalar, resolver);
                continue;
            }
            if (event instanceof MappingEndEvent || event instanceof SequenceEndEvent) {
                if (containers.isEmpty()) {
                    throw invalid("Generation configuration has invalid YAML structure");
                }
                YamlContainer completed = containers.pop();
                if (completed.mapping() && !completed.expectsKey()) {
                    throw invalid("Generation configuration has an incomplete YAML mapping");
                }
            }
        }
        if (!containers.isEmpty()) {
            throw invalid("Generation configuration has invalid YAML structure");
        }
    }

    private void rejectExplicitTag(CollectionStartEvent event) {
        if (event.getTag() != null) {
            throw invalid("YAML explicit type tags are not supported for this value");
        }
    }

    private void beginContainer(Deque<YamlContainer> containers, boolean mapping) {
        boolean jsonValueMode = false;
        if (!containers.isEmpty()) {
            YamlContainer parent = containers.peek();
            if (parent.mapping() && parent.expectsKey()) {
                throw invalid("Generation configuration keys must be strings");
            }
            jsonValueMode = parent.jsonValueMode()
                    || (parent.mapping() && ("arguments".equals(parent.pendingKey())
                    || "successValues".equals(parent.pendingKey())
                    || "statusCodes".equals(parent.pendingKey())));
            parent.completeValue();
        }
        containers.push(new YamlContainer(mapping, jsonValueMode));
    }

    private void validateScalar(Deque<YamlContainer> containers, ScalarEvent scalar, Resolver resolver) {
        if (scalar.getTag() != null) {
            throw invalid("YAML explicit type tags are not supported for this value");
        }
        Tag tag = resolver.resolve(NodeId.scalar, scalar.getValue(),
                scalar.isPlain() && scalar.getImplicit().canOmitTagInPlainScalar());
        YamlContainer container = containers.peek();
        if (container != null && container.mapping() && container.expectsKey()) {
            if (!Tag.STR.equals(tag)) {
                throw invalid("Generation configuration keys must be strings");
            }
            container.recordKey(scalar.getValue());
            return;
        }

        String field = container != null && container.mapping() ? container.pendingKey() : null;
        boolean isJsonValue = container != null && container.jsonValueMode();
        boolean supportedJsonScalar = Tag.STR.equals(tag) || Tag.INT.equals(tag)
                || Tag.FLOAT.equals(tag) || Tag.BOOL.equals(tag);
        Tag expected;
        if ("enabled".equals(field) || "networkErrors".equals(field) || "respectRetryAfter".equals(field)) {
            expected = Tag.BOOL;
        } else if ("maxRetries".equals(field)
                || "initialBackoffMillis".equals(field)
                || "maxBackoffMillis".equals(field)) {
            expected = Tag.INT;
        } else {
            expected = Tag.STR;
        }
        if (!(isJsonValue && supportedJsonScalar) && !expected.equals(tag)) {
            throw invalid("Generation configuration scalar types must match the schema");
        }
        if (container != null) {
            container.completeValue();
        }
    }

    private static final class YamlContainer {
        private final boolean mapping;
        private final boolean jsonValueMode;
        private boolean expectsKey;
        private String pendingKey;

        private YamlContainer(boolean mapping, boolean jsonValueMode) {
            this.mapping = mapping;
            this.jsonValueMode = jsonValueMode;
            this.expectsKey = mapping;
        }

        boolean mapping() {
            return mapping;
        }

        boolean expectsKey() {
            return expectsKey;
        }

        boolean jsonValueMode() {
            return jsonValueMode;
        }

        String pendingKey() {
            return pendingKey;
        }

        void recordKey(String key) {
            pendingKey = key;
            expectsKey = false;
        }

        void completeValue() {
            if (mapping) {
                pendingKey = null;
                expectsKey = true;
            }
        }
    }

    private void validateTokenTypes(JsonNode root) {
        requireObject(root, "Generation configuration");
        requireFields(root, ROOT_FIELDS, ROOT_FIELDS, "Generation configuration");
        JsonNode project = root.get("project");
        requireObject(project, "Project");
        requireFields(project, PROJECT_FIELDS, PROJECT_FIELDS, "Project");
        requireString(project, "groupId", "Project groupId");
        requireString(project, "artifactId", "Project artifactId");
        requireString(project, "packageName", "Project packageName");
        requireString(root, "provider", "Provider");
        requireString(root, "domain", "Domain");
        requireString(root, "targetProfileId", "Target profile");
        requireString(root, "validationLevel", "Validation level");

        JsonNode validation = root.get("validation");
        requireObject(validation, "Validation");
        requireFields(validation, VALIDATION_FIELDS, VALIDATION_FIELDS, "Validation");
        JsonNode toolCall = validation.get("toolCall");
        requireObject(toolCall, "Tool call validation");
        requireFields(toolCall, TOOL_CALL_FIELDS, TOOL_CALL_FIELDS, "Tool call validation");
        requireString(toolCall, "operationId", "Tool call operation ID");
        requireObject(toolCall.get("arguments"), "Tool call arguments");

        JsonNode operations = root.get("operations");
        if (operations == null || !operations.isArray()) {
            throw invalid("Operations must be an array");
        }
        for (JsonNode operation : operations) {
            requireObject(operation, "Operation selection");
            requireFields(operation, OPERATION_FIELDS, Set.of("operationId", "enabled"), "Operation selection");
            requireString(operation, "operationId", "Operation ID");
            requireBoolean(operation, "enabled", "Operation enabled state");
            optionalString(operation, "toolName", "Tool name");
            optionalString(operation, "toolDescription", "Tool description");
            if (operation.has("output")) {
                JsonNode output = operation.get("output");
                requireObject(output, "Operation output");
                requireFields(output, OUTPUT_FIELDS, OUTPUT_FIELDS, "Operation output");
                requireString(output, "mode", "Operation output mode");
            }
            if (operation.has("retry")) {
                JsonNode retry = operation.get("retry");
                requireObject(retry, "Operation retry");
                requireFields(retry, RETRY_FIELDS,
                        Set.of("maxRetries", "initialBackoffMillis", "maxBackoffMillis"), "Operation retry");
                if (retry.has("statusCodes")) {
                    JsonNode statuses = retry.get("statusCodes");
                    if (!statuses.isArray()) {
                        throw invalid("Operation retry status codes must be integers");
                    }
                    for (JsonNode status : statuses) {
                        if (!status.isIntegralNumber()) {
                            throw invalid("Operation retry status codes must be integers");
                        }
                    }
                }
                optionalBoolean(retry, "networkErrors", "Operation retry network errors");
                requireInteger(retry, "maxRetries", "Operation retry max retries");
                requireInteger(retry, "initialBackoffMillis", "Operation retry initial backoff");
                requireInteger(retry, "maxBackoffMillis", "Operation retry max backoff");
                optionalBoolean(retry, "respectRetryAfter", "Operation retry Retry-After policy");
            }
            if (operation.has("responseNormalization")) {
                JsonNode normalization = operation.get("responseNormalization");
                requireObject(normalization, "Response normalization");
                requireFields(normalization, RESPONSE_NORMALIZATION_FIELDS, Set.of(), "Response normalization");
                optionalString(normalization, "dataPath", "Response normalization data path");
                optionalString(normalization, "successCodePath", "Response normalization success code path");
                optionalString(normalization, "errorMessagePath", "Response normalization error message path");
                optionalString(normalization, "totalCountPath", "Response normalization total count path");
                if (normalization.has("successValues")) {
                    JsonNode successValues = normalization.get("successValues");
                    if (!successValues.isArray()) {
                        throw invalid("Response normalization success values must be scalar values");
                    }
                    for (JsonNode value : successValues) {
                        if (!value.isTextual() && !value.isNumber() && !value.isBoolean()) {
                            throw invalid("Response normalization success values must be scalar values");
                        }
                    }
                }
            }
            if (operation.has("parameters")) {
                JsonNode parameters = operation.get("parameters");
                requireObject(parameters, "Operation parameters");
                parameters.properties().forEach(entry -> {
                    JsonNode override = entry.getValue();
                    requireObject(override, "Parameter override");
                    requireFields(override, PARAMETER_FIELDS, Set.of("source"), "Parameter override");
                    requireString(override, "source", "Parameter source");
                    optionalString(override, "environmentVariable", "Secret environment variable");
                });
            }
        }
    }

    private void requireFields(JsonNode object, Set<String> allowed, Set<String> required, String label) {
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw invalid(label + " contains an unknown property");
            }
        });
        for (String name : required) {
            if (!object.has(name) || object.get(name) == null || object.get(name).isNull()) {
                throw invalid(label + " is missing a required property");
            }
        }
    }

    private void requireObject(JsonNode value, String label) {
        if (value == null || !value.isObject()) {
            throw invalid(label + " must be an object");
        }
    }

    private void requireString(JsonNode object, String field, String label) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(label + " must be a string");
        }
    }

    private void optionalString(JsonNode object, String field, String label) {
        if (object.has(field)) {
            requireString(object, field, label);
        }
    }

    private void requireBoolean(JsonNode object, String field, String label) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid(label + " must be a boolean");
        }
    }

    private void optionalBoolean(JsonNode object, String field, String label) {
        if (object.has(field)) {
            requireBoolean(object, field, label);
        }
    }

    private void requireInteger(JsonNode object, String field, String label) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid(label + " must be an integer");
        }
    }

    private GenerationRequest validate(RawConfiguration raw) {
        if (raw == null || raw.project() == null) {
            throw invalid("Generation project coordinates are required");
        }
        String groupId = javaPackage(raw.project().groupId(), "Project groupId");
        String artifactId = matches(raw.project().artifactId(), ARTIFACT_ID, "Project artifactId");
        String packageName = javaPackage(raw.project().packageName(), "Project packageName");
        String provider = matches(raw.provider(), COMPONENT, "Provider");
        String domain = matches(raw.domain(), COMPONENT, "Domain");
        if (profiles.find(raw.targetProfileId()).isEmpty()) {
            throw invalid("Target profile is unavailable");
        }
        if (raw.validationLevel() != ValidationLevel.MCP_PROTOCOL) {
            throw invalid("Validation level must be MCP_PROTOCOL");
        }
        ValidationConfiguration validation = validation(raw.validation());
        List<OperationSelection> operations = operations(raw.operations());
        return new GenerationRequest(new ProjectCoordinates(groupId, artifactId, packageName), provider, domain,
                raw.targetProfileId(), raw.validationLevel(), validation, operations);
    }

    private ValidationConfiguration validation(RawValidation rawValidation) {
        if (rawValidation == null || rawValidation.toolCall() == null) {
            throw invalid("Tool call validation is required");
        }
        RawToolCall rawToolCall = rawValidation.toolCall();
        String operationId = matches(rawToolCall.operationId(), OPERATION_ID, "Tool call operation ID");
        return new ValidationConfiguration(new ToolCallValidation(operationId,
                argumentMap(rawToolCall.arguments(), 0)));
    }

    private Map<String, Object> argumentMap(JsonNode node, int depth) {
        if (node == null || !node.isObject()) {
            throw invalid("Tool call arguments must be an object");
        }
        if (depth > MAX_ARGUMENT_DEPTH || node.size() > MAX_ARGUMENT_MEMBERS) {
            throw invalid("Tool call arguments exceed configured bounds");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String key = entry.getKey();
            if (key.isBlank() || key.length() > MAX_ARGUMENT_STRING_CHARACTERS) {
                throw invalid("Tool call arguments contain an invalid key");
            }
            values.put(key, argumentValue(entry.getValue(), depth + 1));
        });
        return values;
    }

    private List<Object> argumentList(JsonNode node, int depth) {
        if (depth > MAX_ARGUMENT_DEPTH || node.size() > MAX_ARGUMENT_ITEMS) {
            throw invalid("Tool call arguments exceed configured bounds");
        }
        List<Object> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(argumentValue(item, depth + 1));
        }
        return values;
    }

    private Object argumentValue(JsonNode node, int depth) {
        if (depth > MAX_ARGUMENT_DEPTH) {
            throw invalid("Tool call arguments exceed configured bounds");
        }
        if (node == null || node.isNull()) {
            throw invalid("Tool call arguments cannot contain null values");
        }
        if (node.isObject()) {
            return argumentMap(node, depth);
        }
        if (node.isArray()) {
            return argumentList(node, depth);
        }
        if (node.isTextual()) {
            String value = node.textValue();
            if (value.length() > MAX_ARGUMENT_STRING_CHARACTERS) {
                throw invalid("Tool call argument string is too long");
            }
            return value;
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        throw invalid("Tool call arguments must contain JSON-compatible values");
    }

    private List<OperationSelection> operations(List<RawOperation> rawOperations) {
        if (rawOperations == null || rawOperations.isEmpty() || rawOperations.size() > MAX_OPERATIONS) {
            throw invalid("At least one bounded operation selection is required");
        }
        List<OperationSelection> operations = new ArrayList<>();
        Set<String> operationIds = new HashSet<>();
        boolean enabled = false;
        for (RawOperation raw : rawOperations) {
            if (raw == null) {
                throw invalid("Operation selection is invalid");
            }
            String operationId = matches(raw.operationId(), OPERATION_ID, "Operation ID");
            if (!operationIds.add(operationId)) {
                throw invalid("Operation IDs must be unique");
            }
            if (raw.enabled() == null) {
                throw invalid("Operation enabled state is required");
            }
            enabled |= raw.enabled();
            String toolName = optionalMatch(raw.toolName(), TOOL_NAME, "Tool name");
            String description = optionalDescription(raw.toolDescription());
            operations.add(new OperationSelection(operationId, raw.enabled(), toolName, description,
                    parameters(raw.parameters()), responseNormalization(raw.responseNormalization()),
                    output(raw.output()), retry(raw.retry())));
        }
        if (!enabled) {
            throw invalid("At least one operation must be enabled");
        }
        return List.copyOf(operations);
    }

    private Map<String, ParameterOverride> parameters(Map<String, RawParameterOverride> rawParameters) {
        if (rawParameters == null) {
            return Map.of();
        }
        Map<String, ParameterOverride> parameters = new LinkedHashMap<>();
        rawParameters.forEach((name, raw) -> {
            String parameterName = matches(name, PARAMETER_NAME, "Parameter name");
            if (raw == null || raw.source() == null) {
                throw invalid("Parameter source is required");
            }
            String environmentVariable = raw.environmentVariable();
            if (raw.source() != ParameterSource.SERVER_SECRET && raw.source() != ParameterSource.USER_INPUT) {
                throw invalid("Only USER_INPUT and SERVER_SECRET parameter sources are supported");
            }
            if (raw.source() == ParameterSource.SERVER_SECRET) {
                environmentVariable = matches(environmentVariable, ENVIRONMENT_VARIABLE,
                        "Secret environment variable");
            } else if (environmentVariable != null) {
                throw invalid("User input parameters cannot declare secret environment variables");
            }
            parameters.put(parameterName, new ParameterOverride(raw.source(), environmentVariable));
        });
        return Collections.unmodifiableMap(parameters);
    }

    private ResponseNormalizationPolicy responseNormalization(RawResponseNormalization raw) {
        if (raw == null) {
            return null;
        }
        List<Object> successValues = raw.successValues() == null ? List.of()
                : raw.successValues().stream().map(this::scalarValue).toList();
        try {
            return new ResponseNormalizationPolicyValidator().requireValid(new ResponseNormalizationPolicy(
                    raw.dataPath(), raw.successCodePath(), successValues,
                    raw.errorMessagePath(), raw.totalCountPath()));
        } catch (IllegalArgumentException failure) {
            throw invalid("Response normalization policy is invalid");
        }
    }

    private OutputSelection output(RawOutput raw) {
        if (raw == null) {
            return new OutputSelection(OutputKind.GENERIC_JSON);
        }
        return new OutputSelection(switch (raw.mode()) {
            case GENERIC_JSON -> OutputKind.GENERIC_JSON;
            case TYPED -> OutputKind.TYPED_DTO;
        });
    }

    private RetryPolicy retry(RawRetry raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new RetryPolicy(
                    raw.statusCodes(), Boolean.TRUE.equals(raw.networkErrors()), raw.maxRetries(),
                    raw.initialBackoffMillis(), raw.maxBackoffMillis(), Boolean.TRUE.equals(raw.respectRetryAfter()));
        } catch (IllegalArgumentException failure) {
            throw invalid("Generation configuration is invalid");
        }
    }

    private Object scalarValue(JsonNode value) {
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isIntegralNumber()) {
            return value.bigIntegerValue();
        }
        if (value.isFloatingPointNumber()) {
            return value.decimalValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        throw invalid("Response normalization success values must be scalar values");
    }

    private String javaPackage(String value, String label) {
        String safe = exact(value, label);
        if (safe.length() > 255) {
            throw invalid(label + " is too long");
        }
        for (String segment : safe.split("\\.", -1)) {
            if (!JAVA_IDENTIFIER.matcher(segment).matches() || !SourceVersion.isIdentifier(segment)
                    || SourceVersion.isKeyword(segment)) {
                throw invalid(label + " must contain valid Java identifiers");
            }
        }
        return safe;
    }

    private String matches(String value, Pattern pattern, String label) {
        String safe = exact(value, label);
        if (!pattern.matcher(safe).matches()) {
            throw invalid(label + " contains unsupported characters");
        }
        return safe;
    }

    private String optionalMatch(String value, Pattern pattern, String label) {
        return value == null ? null : matches(value, pattern, label);
    }

    private String optionalDescription(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > MAX_DESCRIPTION_CHARACTERS || value.chars().anyMatch(character ->
                Character.isISOControl(character) && character != '\n' && character != '\r' && character != '\t')) {
            throw invalid("Tool description is invalid");
        }
        return value;
    }

    private String exact(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw invalid(label + " is required without surrounding whitespace");
        }
        return value;
    }

    private GenerationConfigurationException invalid(String message) {
        return new GenerationConfigurationException(message);
    }

    private record RawConfiguration(
            RawProject project,
            String provider,
            String domain,
            String targetProfileId,
            ValidationLevel validationLevel,
            RawValidation validation,
            List<RawOperation> operations) {}

    private record RawValidation(RawToolCall toolCall) {}

    private record RawToolCall(String operationId, JsonNode arguments) {}

    private record RawProject(String groupId, String artifactId, String packageName) {}

    private record RawOperation(
            String operationId,
            Boolean enabled,
            String toolName,
            String toolDescription,
            Map<String, RawParameterOverride> parameters,
            RawResponseNormalization responseNormalization,
            RawOutput output,
            RawRetry retry) {}

    private record RawOutput(RawOutputMode mode) {}

    private enum RawOutputMode { GENERIC_JSON, TYPED }

    private record RawRetry(
            List<Integer> statusCodes,
            Boolean networkErrors,
            Integer maxRetries,
            Long initialBackoffMillis,
            Long maxBackoffMillis,
            Boolean respectRetryAfter) {}

    private record RawResponseNormalization(
            String dataPath,
            String successCodePath,
            List<JsonNode> successValues,
            String errorMessagePath,
            String totalCountPath) {}

    private record RawParameterOverride(ParameterSource source, String environmentVariable) {}
}
