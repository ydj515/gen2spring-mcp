package io.gen2spring.mcp.application.toolmodel;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.OPERATION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SECRET_EXPOSURE_DETECTED;
import static io.gen2spring.mcp.domain.tool.ParameterSource.SERVER_SECRET;
import static io.gen2spring.mcp.domain.tool.ParameterSource.USER_INPUT;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.command.GenerationCommand.OperationSelection;
import io.gen2spring.mcp.application.command.GenerationCommand.ParameterOverride;
import io.gen2spring.mcp.application.toolmodel.description.ToolDescriptionPolicy;
import io.gen2spring.mcp.application.toolmodel.naming.ToolNamingPolicy;
import io.gen2spring.mcp.application.toolmodel.output.OutputSchemaResolver;
import io.gen2spring.mcp.application.toolmodel.security.SecretParameterPolicy;
import io.gen2spring.mcp.application.toolmodel.schema.SchemaPatternMatcher;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiOperation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiParameter;
import io.gen2spring.mcp.domain.observability.RuntimeObservabilityContract;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicyValidator;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.ToolInput;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import io.gen2spring.mcp.domain.tool.ParameterSource;
import io.gen2spring.mcp.domain.tool.SecretBinding;
import io.gen2spring.mcp.domain.tool.ToolOutput;
import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ToolModelFactory {
    private static final String RESTRICTED_HEADER_MESSAGE =
            "Operation uses a runtime-owned or restricted HTTP header";
    private static final Set<String> RUNTIME_OWNED_OR_RESTRICTED_HEADERS = Set.of(
            "accept", "content-type", "connection", "content-length", "expect", "host", "upgrade");
    private final ToolNamingPolicy namingPolicy;
    private final SecretParameterPolicy secretPolicy;
    private final ToolDescriptionPolicy descriptionPolicy;
    private final OutputSchemaResolver outputSchemaResolver;

    public ToolModelFactory() {
        this(new ToolNamingPolicy(), new SecretParameterPolicy(), new ToolDescriptionPolicy(),
                new OutputSchemaResolver());
    }

    public ToolModelFactory(
            ToolNamingPolicy namingPolicy,
            SecretParameterPolicy secretPolicy,
            ToolDescriptionPolicy descriptionPolicy) {
        this(namingPolicy, secretPolicy, descriptionPolicy, new OutputSchemaResolver());
    }

    public ToolModelFactory(
            ToolNamingPolicy namingPolicy,
            SecretParameterPolicy secretPolicy,
            ToolDescriptionPolicy descriptionPolicy,
            OutputSchemaResolver outputSchemaResolver) {
        this.namingPolicy = namingPolicy;
        this.secretPolicy = secretPolicy;
        this.descriptionPolicy = descriptionPolicy;
        this.outputSchemaResolver = outputSchemaResolver;
    }

    public List<ToolDefinition> create(OpenApiDocument document, GenerationCommand request) {
        Map<String, ApiOperation> operationsById = operationsById(document);
        Set<String> finalNames = new HashSet<>();
        List<ToolDefinition> tools = new ArrayList<>();

        for (OperationSelection selection : enabledSelections(request)) {
            ApiOperation operation = operationsById.get(selection.operationId());
            if (operation == null || !operation.supported()) {
                throw unsupportedOperation(selection.operationId());
            }
            tools.add(createTool(document, request, operation, selection, finalNames));
        }

        tools.sort(Comparator.comparing(ToolDefinition::operationId));
        return List.copyOf(tools);
    }

    private Map<String, ApiOperation> operationsById(OpenApiDocument document) {
        Map<String, ApiOperation> operations = new HashMap<>();
        for (ApiOperation operation : document.operations()) {
            if (operations.put(operation.operationId(), operation) != null) {
                throw unsupportedOperation(operation.operationId());
            }
        }
        return operations;
    }

    private List<OperationSelection> enabledSelections(GenerationCommand request) {
        if (request == null || request.operations() == null) {
            return List.of();
        }
        return request.operations().stream().filter(OperationSelection::enabled).toList();
    }

    private ResponseNormalizationPolicy responsePolicy(OperationSelection selection) {
        if (selection.responseNormalization() == null) {
            return null;
        }
        try {
            return new ResponseNormalizationPolicyValidator().requireValid(selection.responseNormalization());
        } catch (IllegalArgumentException failure) {
            throw GeneratorException.user(OPERATION_UNSUPPORTED, "tool-policy",
                    "Response normalization policy is invalid");
        }
    }

    private ToolDefinition createTool(
            OpenApiDocument document,
            GenerationCommand request,
            ApiOperation operation,
            OperationSelection selection,
            Set<String> finalNames) {
        String name = hasText(selection.toolName())
                ? namingPolicy.generate(selection.toolName())
                : namingPolicy.generate(request.provider(), request.domain(), operation.operationId());
        namingPolicy.requireUnique(name, finalNames);
        String description = descriptionPolicy.describe(
                selection.toolDescription(), operation.summary(), operation.description());
        if (!hasText(description)) {
            throw unsupportedOperation(operation.operationId());
        }

        List<ToolInput> inputs = new ArrayList<>();
        List<ParameterBinding> bindings = new ArrayList<>();
        List<SecretBinding> secretBindings = new ArrayList<>();
        Set<String> inputNames = new HashSet<>();
        Map<String, ParameterOverride> overrides = selection.parameters() == null ? Map.of() : selection.parameters();

        validateSecretOverrideConflicts(operation, document, overrides);
        validateOverrideKeys(operation, document, overrides);
        validateRequestBody(operation);
        ResponseNormalizationPolicy normalization = responsePolicy(selection);
        RetryPolicy retryPolicy = retryPolicy(selection, operation);
        PaginationPolicy paginationPolicy = paginationPolicy(selection, operation, document, overrides);
        ToolOutput output = outputSchemaResolver.resolve(
                selection.output(), operation.successResponse(), normalization);
        Map<String, ResolvedApiKeySecret> apiKeySecrets = resolveApiKeySecrets(operation, document, overrides);
        Set<String> secretTargets = new HashSet<>();
        for (ApiParameter parameter : operation.parameters()) {
            if (paginationPolicy != null
                    && parameter.location() == OpenApiDocument.ParameterLocation.QUERY
                    && parameter.name().equals(paginationPolicy.requestParameter())) {
                continue;
            }
            rejectRuntimeOwnedOrRestrictedHeader(parameter.location(), parameter.name());
            OpenApiDocument.ApiSecurityScheme apiKeyScheme = matchingApiKeyScheme(document, operation, parameter);
            ParameterOverride override = overrideFor(overrides, parameter.name(), apiKeyScheme);
            boolean apiKeyParameter = apiKeyScheme != null;
            ParameterSource source = secretPolicy.classify(parameter.name(), parameter.location(), apiKeyParameter, override);
            boolean confirmedSecret = apiKeyParameter || secretPolicy.isApprovedCandidate(parameter.name()) || source == SERVER_SECRET;

            if (confirmedSecret && source != SERVER_SECRET) {
                throw GeneratorException.user(SECRET_EXPOSURE_DETECTED, "tool-policy",
                        "Confirmed secret parameters cannot be exposed as tool inputs");
            }
            if (source == SERVER_SECRET) {
                ResolvedApiKeySecret apiKeySecret = apiKeyScheme == null ? null
                        : apiKeySecrets.get(secretTarget(parameter.location(), parameter.name()));
                String environmentVariable = apiKeySecret == null
                        ? secretPolicy.requireEnvironmentVariable(override == null ? null : override.environmentVariable())
                        : apiKeySecret.environmentVariable();
                addSecretBinding(secretBindings, secretTargets, environmentVariable, parameter.name(),
                        parameter.location(), parameter.name(), apiKeySecret != null || parameter.required());
            } else if (source == USER_INPUT) {
                String inputName = requireInputName(parameter.name(), inputNames);
                inputs.add(new ToolInput(
                        inputName, parameter.name(), parameter.description(), parameter.required(), parameter.schema()));
                bindings.add(new ParameterBinding(inputName, parameter.location(), parameter.name()));
            } else {
                throw unsupportedOperation(operation.operationId());
            }
        }

        for (ResolvedApiKeySecret apiKeySecret : apiKeySecrets.values()) {
            String target = secretTarget(apiKeySecret.location(), apiKeySecret.targetName());
            if (secretTargets.contains(target)) {
                continue;
            }
            addSecretBinding(secretBindings, secretTargets, apiKeySecret.environmentVariable(),
                    apiKeySecret.targetName(), apiKeySecret.location(), apiKeySecret.targetName(), true);
        }

        boolean flattenedObjectBody = operation.requestBody() != null
                && operation.requestBody().type() == OpenApiDocument.SchemaType.OBJECT
                && !operation.requestBody().nullable();
        if (operation.requestBody() != null) {
            if (flattenedObjectBody) {
                Map<String, OpenApiDocument.ApiSchema> properties = operation.requestBody().properties() == null
                        ? Map.of() : operation.requestBody().properties();
                Set<String> requiredProperties = new HashSet<>(operation.requestBody().requiredProperties() == null
                        ? List.of() : operation.requestBody().requiredProperties());
                properties.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    String inputName = requireInputName(entry.getKey(), inputNames);
                    inputs.add(new ToolInput(
                            inputName, entry.getKey(), null,
                            operation.requestBodyRequired() && requiredProperties.contains(entry.getKey()), entry.getValue()));
                    bindings.add(new ParameterBinding(inputName, OpenApiDocument.ParameterLocation.BODY, entry.getKey()));
                });
            } else {
                String inputName = requireInputName("body", inputNames);
                inputs.add(new ToolInput(inputName, "body", null, operation.requestBodyRequired(), operation.requestBody()));
                bindings.add(new ParameterBinding(inputName, OpenApiDocument.ParameterLocation.BODY, "body"));
            }
        }

        return new ToolDefinition(
                operation.operationId(),
                name,
                description,
                List.copyOf(inputs),
                new HttpExecution(
                        operation.method(), document.baseUrl(), operation.path(), List.copyOf(bindings),
                        flattenedObjectBody,
                        operation.requestBodyRequired(),
                        normalization,
                        retryPolicy,
                        paginationPolicy),
                List.copyOf(secretBindings),
                output);
    }

    private RetryPolicy retryPolicy(OperationSelection selection, ApiOperation operation) {
        if (selection.retry() == null) {
            return null;
        }
        if (operation.method() != OpenApiDocument.HttpMethod.GET) {
            throw GeneratorException.user(
                    OPERATION_UNSUPPORTED,
                    "tool-policy",
                    "Retry policy is unsupported for this operation");
        }
        return selection.retry();
    }

    private PaginationPolicy paginationPolicy(
            OperationSelection selection,
            ApiOperation operation,
            OpenApiDocument document,
            Map<String, ParameterOverride> overrides) {
        PaginationPolicy policy = selection.pagination();
        if (policy == null) {
            return null;
        }
        if (operation.method() != OpenApiDocument.HttpMethod.GET || overrides.containsKey(policy.requestParameter())) {
            throw paginationUnsupported();
        }
        ApiParameter parameter = operation.parameters().stream()
                .filter(candidate -> candidate.location() == OpenApiDocument.ParameterLocation.QUERY)
                .filter(candidate -> candidate.name().equals(policy.requestParameter()))
                .findFirst()
                .orElseThrow(this::paginationUnsupported);
        if (parameter.schema() == null || !parameter.schema().supported()
                || parameter.schema().type() != OpenApiDocument.SchemaType.STRING
                && parameter.schema().type() != OpenApiDocument.SchemaType.INTEGER
                || parameter.required() && policy.initialValue() == null
                || matchingApiKeyScheme(document, operation, parameter) != null
                || !validPaginationValue(parameter.schema(), policy.initialValue())) {
            throw paginationUnsupported();
        }
        try {
            outputSchemaResolver.requirePaginationSchema(operation.successResponse(), policy);
        } catch (IllegalArgumentException failure) {
            throw paginationUnsupported();
        }
        return policy;
    }

    private boolean validPaginationValue(OpenApiDocument.ApiSchema schema, Object value) {
        if (value == null) {
            return true;
        }
        if (schema.type() == OpenApiDocument.SchemaType.STRING && value instanceof String string) {
            if ((schema.enumValues() != null && !schema.enumValues().isEmpty()
                    && !schema.enumValues().contains(string))
                    || schema.minLength() != null && string.length() < schema.minLength()
                    || schema.maxLength() != null && string.length() > schema.maxLength()) {
                return false;
            }
            return schema.pattern() == null || SchemaPatternMatcher.matches(schema.pattern(), string);
        }
        if (schema.type() == OpenApiDocument.SchemaType.INTEGER && value instanceof java.math.BigInteger integer) {
            java.math.BigDecimal decimal = new java.math.BigDecimal(integer);
            if (schema.minimum() != null && decimal.compareTo(schema.minimum()) < 0
                    || schema.maximum() != null && decimal.compareTo(schema.maximum()) > 0) {
                return false;
            }
            if ("int32".equals(schema.format())
                    && (integer.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                    || integer.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) > 0)) {
                return false;
            }
            if ("int64".equals(schema.format())
                    && (integer.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0
                    || integer.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0)) {
                return false;
            }
            return schema.enumValues() == null || schema.enumValues().isEmpty()
                    || schema.enumValues().contains(integer.toString());
        }
        return false;
    }

    private GeneratorException paginationUnsupported() {
        return GeneratorException.user(
                OPERATION_UNSUPPORTED, "tool-policy", "Pagination policy is unsupported for this operation");
    }

    private OpenApiDocument.ApiSecurityScheme matchingApiKeyScheme(
            OpenApiDocument document, ApiOperation operation, ApiParameter parameter) {
        return applicableApiKeySchemes(document, operation).stream()
                .filter(scheme -> sameTarget(
                        scheme.location(), scheme.parameterName(), parameter.location(), parameter.name()))
                .findFirst()
                .orElse(null);
    }

    private List<OpenApiDocument.ApiSecurityScheme> applicableApiKeySchemes(
            OpenApiDocument document, ApiOperation operation) {
        return operation.securityRequirements().stream()
                .map(document.securitySchemes()::get)
                .filter(scheme -> scheme != null && "apiKey".equalsIgnoreCase(scheme.type())
                        && scheme.location() != null && scheme.parameterName() != null && !scheme.parameterName().isBlank())
                .sorted(Comparator.comparing(OpenApiDocument.ApiSecurityScheme::name))
                .toList();
    }

    private ParameterOverride overrideFor(
            Map<String, ParameterOverride> overrides,
            String parameterName,
            OpenApiDocument.ApiSecurityScheme scheme) {
        ParameterOverride parameterOverride = overrides.get(parameterName);
        ParameterOverride schemeOverride = scheme == null ? null : overrides.get(scheme.name());
        if (parameterOverride != null && schemeOverride != null && !parameterOverride.equals(schemeOverride)) {
            throw GeneratorException.user(SECRET_EXPOSURE_DETECTED, "tool-policy",
                    "A secret parameter and its security scheme cannot use conflicting overrides");
        }
        return parameterOverride != null ? parameterOverride : schemeOverride;
    }

    private void validateSecretOverrideConflicts(
            ApiOperation operation,
            OpenApiDocument document,
            Map<String, ParameterOverride> overrides) {
        for (OpenApiDocument.ApiSecurityScheme scheme : applicableApiKeySchemes(document, operation)) {
            ParameterOverride schemeOverride = overrides.get(scheme.name());
            ParameterOverride parameterOverride = overrides.get(scheme.parameterName());
            if (schemeOverride != null && parameterOverride != null && !schemeOverride.equals(parameterOverride)) {
                throw GeneratorException.user(SECRET_EXPOSURE_DETECTED, "tool-policy",
                        "A secret parameter and its security scheme cannot use conflicting overrides");
            }
        }
    }

    private void validateOverrideKeys(
            ApiOperation operation,
            OpenApiDocument document,
            Map<String, ParameterOverride> overrides) {
        Map<String, Set<String>> targetsByAlias = new HashMap<>();
        for (ApiParameter parameter : operation.parameters()) {
            if (parameter.name() != null) {
                targetsByAlias.computeIfAbsent(parameter.name(), ignored -> new HashSet<>())
                        .add(secretTarget(parameter.location(), parameter.name()));
            }
        }
        for (OpenApiDocument.ApiSecurityScheme scheme : applicableApiKeySchemes(document, operation)) {
            String target = secretTarget(scheme.location(), scheme.parameterName());
            targetsByAlias.computeIfAbsent(scheme.name(), ignored -> new HashSet<>()).add(target);
            targetsByAlias.computeIfAbsent(scheme.parameterName(), ignored -> new HashSet<>()).add(target);
        }
        for (String overrideName : overrides.keySet()) {
            Set<String> targets = targetsByAlias.get(overrideName);
            if (targets == null) {
                throw GeneratorException.user(OPERATION_UNSUPPORTED, "tool-policy",
                        "Configured parameter overrides must match an operation parameter or applicable security scheme");
            }
            if (targets.size() != 1) {
                throw GeneratorException.user(OPERATION_UNSUPPORTED, "tool-policy",
                        "Configured parameter override is ambiguous across parameter locations");
            }
        }
    }

    private Map<String, ResolvedApiKeySecret> resolveApiKeySecrets(
            ApiOperation operation,
            OpenApiDocument document,
            Map<String, ParameterOverride> overrides) {
        Map<String, ResolvedApiKeySecret> resolved = new java.util.TreeMap<>();
        for (OpenApiDocument.ApiSecurityScheme scheme : applicableApiKeySchemes(document, operation)) {
            rejectRuntimeOwnedOrRestrictedHeader(scheme.location(), scheme.parameterName());
            ParameterOverride override = overrideFor(overrides, scheme.parameterName(), scheme);
            ParameterSource source = secretPolicy.classify(
                    scheme.parameterName(), scheme.location(), true, override);
            if (source != SERVER_SECRET) {
                throw GeneratorException.user(SECRET_EXPOSURE_DETECTED, "tool-policy",
                        "Confirmed secret parameters cannot be exposed as tool inputs");
            }
            ResolvedApiKeySecret candidate = new ResolvedApiKeySecret(
                    scheme.location(), scheme.parameterName(), secretPolicy.requireEnvironmentVariable(
                    override == null ? null : override.environmentVariable()));
            String target = secretTarget(candidate.location(), candidate.targetName());
            ResolvedApiKeySecret previous = resolved.putIfAbsent(target, candidate);
            if (previous != null && !previous.environmentVariable().equals(candidate.environmentVariable())) {
                throw GeneratorException.user(SECRET_EXPOSURE_DETECTED, "tool-policy",
                        "API key schemes sharing a target must use the same secret override");
            }
        }
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(resolved));
    }

    private void addSecretBinding(
            List<SecretBinding> bindings,
            Set<String> targets,
            String environmentVariable,
            String propertyName,
            OpenApiDocument.ParameterLocation location,
            String targetName,
            boolean required) {
        if (targets.add(secretTarget(location, targetName))) {
            bindings.add(new SecretBinding(environmentVariable, propertyName, location, targetName, required));
        }
    }

    private String secretTarget(OpenApiDocument.ParameterLocation location, String targetName) {
        String canonicalName = location == OpenApiDocument.ParameterLocation.HEADER && targetName != null
                ? targetName.toLowerCase(Locale.ROOT) : targetName;
        return location + ":" + canonicalName;
    }

    private boolean sameTarget(
            OpenApiDocument.ParameterLocation firstLocation,
            String firstName,
            OpenApiDocument.ParameterLocation secondLocation,
            String secondName) {
        if (firstLocation != secondLocation || firstName == null || secondName == null) {
            return false;
        }
        return firstLocation == OpenApiDocument.ParameterLocation.HEADER
                ? firstName.equalsIgnoreCase(secondName) : firstName.equals(secondName);
    }

    private void validateRequestBody(ApiOperation operation) {
        OpenApiDocument.ApiSchema requestBody = operation.requestBody();
        if (requestBody == null) {
            return;
        }
        Set<OpenApiDocument.ApiSchema> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (containsApprovedSecretCandidate(requestBody, visited)) {
            throw GeneratorException.user(SECRET_EXPOSURE_DETECTED, "tool-policy",
                    "Request body secret candidates cannot be exposed as P0 Tool inputs");
        }
        if (requestBody.type() == OpenApiDocument.SchemaType.OBJECT
                && !requestBody.nullable()
                && !operation.requestBodyRequired()
                && requestBody.requiredProperties() != null
                && !requestBody.requiredProperties().isEmpty()) {
            throw unsupportedOperation(operation.operationId());
        }
    }

    private boolean containsApprovedSecretCandidate(
            OpenApiDocument.ApiSchema schema,
            Set<OpenApiDocument.ApiSchema> visited) {
        if (schema == null || !visited.add(schema)) {
            return false;
        }
        if (schema.properties() != null) {
            for (Map.Entry<String, OpenApiDocument.ApiSchema> property : schema.properties().entrySet()) {
                if (secretPolicy.isApprovedCandidate(property.getKey())
                        || containsApprovedSecretCandidate(property.getValue(), visited)) {
                    return true;
                }
            }
        }
        if (containsApprovedSecretCandidate(schema.items(), visited)) {
            return true;
        }
        return schema.composition() != null && schema.composition().branches().stream()
                .anyMatch(branch -> containsApprovedSecretCandidate(branch, visited));
    }

    private void rejectRuntimeOwnedOrRestrictedHeader(OpenApiDocument.ParameterLocation location, String name) {
        if (location == OpenApiDocument.ParameterLocation.HEADER
                && name != null
                && (RUNTIME_OWNED_OR_RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                        || RuntimeObservabilityContract.isReservedPropagationHeader(name))) {
            throw GeneratorException.user(OPERATION_UNSUPPORTED, "tool-policy", RESTRICTED_HEADER_MESSAGE);
        }
    }

    private String requireInputName(String upstreamName, Set<String> existingNames) {
        if (upstreamName == null || upstreamName.isBlank()) {
            throw unsupportedOperation("an operation with a blank input name");
        }
        StringBuilder normalized = new StringBuilder();
        boolean capitalize = false;
        for (int index = 0; index < upstreamName.length(); index++) {
            char character = upstreamName.charAt(index);
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
            throw unsupportedOperation("an operation with a non-Java input name");
        }
        String inputName = normalized.toString();
        if (SourceVersion.isKeyword(inputName)) {
            inputName += "Value";
        }
        if (!existingNames.add(inputName)) {
            throw unsupportedOperation("an operation with colliding normalized input names");
        }
        return inputName;
    }

    private boolean isAsciiLetter(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private record ResolvedApiKeySecret(
            OpenApiDocument.ParameterLocation location,
            String targetName,
            String environmentVariable) {}

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private GeneratorException unsupportedOperation(String operationId) {
        return GeneratorException.user(OPERATION_UNSUPPORTED, "tool-policy",
                "Configured operation is missing or unsupported: " + operationId);
    }
}
