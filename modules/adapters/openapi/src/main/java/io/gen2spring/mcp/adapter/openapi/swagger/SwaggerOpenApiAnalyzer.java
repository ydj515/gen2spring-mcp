package io.gen2spring.mcp.adapter.openapi.swagger;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_PARSE_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_VERSION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.GET_REQUEST_BODY_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.HTTP_METHOD_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.OPERATION_ID_DUPLICATED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.OPERATION_ID_MISSING;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.PARAMETER_LOCATION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.PARAMETER_SERIALIZATION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.REQUEST_BODY_MEDIA_TYPE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.PARAMETER_NULLABLE_PATH_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.PARAMETER_REQUIRED_NULLABLE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SECURITY_REQUIREMENT_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_MEDIA_TYPE_INFERRED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_MEDIA_TYPE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_SCHEMA_UNSUPPORTED;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.AnalysisWarning;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiOperation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiParameter;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSecurityScheme;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.specification.OperationSupport;
import io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class SwaggerOpenApiAnalyzer implements SpecificationAnalyzer {
    private static final String SPEC_ANALYSIS = "SPEC_ANALYSIS";
    private static final List<HttpMethod> METHOD_ORDER = List.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE,
            HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);
    private static final Set<HttpMethod> SUPPORTED_METHODS = Set.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    private static final Pattern SUPPORTED_VERSION = Pattern.compile("3\\.[01]\\.\\d+");
    private static final String OPENAPI_31_BASE_DIALECT = "https://spec.openapis.org/oas/3.1/dialect/base";
    private static final String UNSUPPORTED_VERSION_MESSAGE =
            "The OpenAPI version or JSON Schema dialect is unsupported";

    private final LocalSpecificationLoader loader;
    private final ExternalReferenceGuard referenceGuard;
    private final SwaggerSchemaNormalizer schemaNormalizer;

    public SwaggerOpenApiAnalyzer() {
        this(new LocalSpecificationLoader(), new ExternalReferenceGuard(), new SwaggerSchemaNormalizer());
    }

    SwaggerOpenApiAnalyzer(
            LocalSpecificationLoader loader,
            ExternalReferenceGuard referenceGuard,
            SwaggerSchemaNormalizer schemaNormalizer) {
        this.loader = loader;
        this.referenceGuard = referenceGuard;
        this.schemaNormalizer = schemaNormalizer;
    }

    @Override
    public AnalysisResult analyze(Path specification, long maxBytes) {
        LocalSpecificationLoader.LoadedSpecification loaded = loader.load(specification, maxBytes);
        ExternalReferenceGuard.Preflight preflight = referenceGuard.verify(loaded.bytes(), loaded.extension());
        validateVersion(preflight);
        io.swagger.v3.oas.models.OpenAPI openApi = parse(loaded);

        List<AnalysisWarning> documentWarnings = new ArrayList<>();
        Map<String, ApiSecurityScheme> securitySchemes = normalizeSecuritySchemes(openApi.getComponents());
        List<ApiOperation> operations = normalizeOperations(openApi, preflight, securitySchemes, documentWarnings);
        return new AnalysisResult(new OpenApiDocument(
                openApi.getOpenapi(),
                loaded.checksum(),
                loaded.extension(),
                baseUrl(openApi),
                List.copyOf(operations),
                securitySchemes,
                List.copyOf(documentWarnings)), loaded.bytes());
    }

    private io.swagger.v3.oas.models.OpenAPI parse(LocalSpecificationLoader.LoadedSpecification loaded) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(false);
        options.setResolveCombinators(false);
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(
                new String(loaded.bytes(), StandardCharsets.UTF_8), null, options);
        if (result.getOpenAPI() == null) {
            String detail = result.getMessages() == null ? "" : String.join("; ", result.getMessages());
            throw GeneratorException.user(SPEC_PARSE_FAILED, SPEC_ANALYSIS,
                    "Specification could not be parsed" + (detail.isBlank() ? "" : ": " + detail));
        }
        return result.getOpenAPI();
    }

    private void validateVersion(ExternalReferenceGuard.Preflight preflight) {
        String version = preflight.openApiVersion();
        boolean supportedVersion = version != null && SUPPORTED_VERSION.matcher(version).matches();
        boolean supportedDialect = !preflight.jsonSchemaDialectPresent()
                || version != null && version.startsWith("3.1.")
                && OPENAPI_31_BASE_DIALECT.equals(preflight.jsonSchemaDialect());
        if (!supportedVersion || !supportedDialect || preflight.unsupportedSchemaDialectOverride()) {
            throw GeneratorException.user(
                    SPEC_VERSION_UNSUPPORTED, SPEC_ANALYSIS, UNSUPPORTED_VERSION_MESSAGE);
        }
    }

    private List<ApiOperation> normalizeOperations(
            io.swagger.v3.oas.models.OpenAPI openApi,
            ExternalReferenceGuard.Preflight preflight,
            Map<String, ApiSecurityScheme> securitySchemes,
            List<AnalysisWarning> documentWarnings) {
        if (openApi.getPaths() == null) {
            return List.of();
        }
        List<ApiOperation> operations = new ArrayList<>();
        openApi.getPaths().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(pathEntry -> {
            Map<HttpMethod, Operation> methods = methods(pathEntry.getValue());
            METHOD_ORDER.forEach(method -> {
                Operation operation = methods.get(method);
                if (operation != null) {
                    ApiOperation normalized = normalizeOperation(openApi, pathEntry.getKey(), method,
                            pathEntry.getValue().getParameters(), operation,
                            preflight.hasRecursiveSchema(pathEntry.getKey(), method.name()), securitySchemes);
                    operations.add(normalized);
                }
            });
        });
        Map<String, Long> counts = operations.stream()
                .filter(operation -> operation.operationId() != null && !operation.operationId().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(
                        ApiOperation::operationId, java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        List<ApiOperation> resolved = operations.stream()
                .map(operation -> operation.operationId() != null
                        && counts.getOrDefault(operation.operationId(), 0L) > 1
                        ? withIssue(operation, OPERATION_ID_DUPLICATED) : operation)
                .toList();
        resolved.forEach(operation -> operation.support().issues().forEach(issue -> documentWarnings.add(
                new AnalysisWarning(issue.code().name(), issue.message(), operation.operationId()))));
        return resolved;
    }

    private Map<HttpMethod, Operation> methods(PathItem pathItem) {
        Map<HttpMethod, Operation> operations = new EnumMap<>(HttpMethod.class);
        operations.put(HttpMethod.GET, pathItem.getGet());
        operations.put(HttpMethod.POST, pathItem.getPost());
        operations.put(HttpMethod.PUT, pathItem.getPut());
        operations.put(HttpMethod.PATCH, pathItem.getPatch());
        operations.put(HttpMethod.DELETE, pathItem.getDelete());
        operations.put(HttpMethod.HEAD, pathItem.getHead());
        operations.put(HttpMethod.OPTIONS, pathItem.getOptions());
        operations.put(HttpMethod.TRACE, pathItem.getTrace());
        return operations;
    }

    private ApiOperation normalizeOperation(
            io.swagger.v3.oas.models.OpenAPI openApi,
            String path,
            HttpMethod method,
            List<Parameter> pathParameters,
            Operation operation,
            boolean hasRecursiveSchema,
            Map<String, ApiSecurityScheme> securitySchemes) {
        List<IssueCode> issues = new ArrayList<>();
        String operationId = operation.getOperationId();
        if (operationId == null || operationId.isBlank()) {
            issues.add(OPERATION_ID_MISSING);
        }
        if (!SUPPORTED_METHODS.contains(method)) {
            issues.add(HTTP_METHOD_UNSUPPORTED);
        }
        if (hasRecursiveSchema) {
            issues.add(IssueCode.RECURSIVE_SCHEMA_UNSUPPORTED);
        }

        Map<String, io.swagger.v3.oas.models.media.Schema> componentSchemas = openApi.getComponents() == null
                ? Map.of() : openApi.getComponents().getSchemas();
        List<ApiParameter> parameters = normalizeParameters(
                pathParameters, operation.getParameters(), componentSchemas, openApi.getOpenapi(), issues);
        ApiSchema requestBody = normalizeRequestBody(
                operation.getRequestBody(), componentSchemas, openApi.getOpenapi(), issues);
        if (method == HttpMethod.GET && operation.getRequestBody() != null) {
            issues.add(GET_REQUEST_BODY_UNSUPPORTED);
        }
        ApiSchema successResponse = normalizeSuccessResponse(
                operation, componentSchemas, openApi.getOpenapi(), issues);
        parameters.forEach(parameter -> addSchemaIssues(issues, parameter.schema()));
        addSchemaIssues(issues, requestBody);

        return new ApiOperation(operationId, method, path, operation.getSummary(), operation.getDescription(),
                parameters, requestBody, operation.getRequestBody() != null
                        && Boolean.TRUE.equals(operation.getRequestBody().getRequired()),
                securityRequirements(operation.getSecurity() == null ? openApi.getSecurity() : operation.getSecurity(),
                        securitySchemes, issues),
                OperationSupport.fromIssues(issues), successResponse);
    }

    private ApiSchema normalizeSuccessResponse(
            Operation operation,
            Map<String, io.swagger.v3.oas.models.media.Schema> componentSchemas,
            String openApiVersion,
            List<IssueCode> issues) {
        if (operation.getResponses() == null) {
            return null;
        }
        List<ApiSchema> schemas = new ArrayList<>();
        boolean invalidBodyDeclaration = false;
        boolean bodylessSuccess = false;
        for (Map.Entry<String, io.swagger.v3.oas.models.responses.ApiResponse> entry
                : operation.getResponses().entrySet()) {
            String statusCode = entry.getKey();
            io.swagger.v3.oas.models.responses.ApiResponse response = entry.getValue();
            if (!isSuccessStatus(statusCode) || response == null) {
                continue;
            }
            if (response.getContent() == null || response.getContent().isEmpty()) {
                bodylessSuccess = true;
                continue;
            }
            SuccessMediaSelection selection = selectSuccessMedia(response.getContent());
            if (selection.decision() == SuccessMediaDecision.UNSUPPORTED_MEDIA) {
                issues.add(SUCCESS_MEDIA_TYPE_UNSUPPORTED);
                invalidBodyDeclaration = true;
                continue;
            }
            if (selection.decision() == SuccessMediaDecision.MISSING_SCHEMA) {
                issues.add(SUCCESS_SCHEMA_UNSUPPORTED);
                invalidBodyDeclaration = true;
                continue;
            }
            if (selection.decision() == SuccessMediaDecision.INFERRED_JSON) {
                issues.add(SUCCESS_MEDIA_TYPE_INFERRED);
            }
            ApiSchema schema = schemaNormalizer.normalizeResponse(
                    selection.mediaType().getSchema(), componentSchemas, openApiVersion);
            addSchemaIssues(issues, schema);
            schemas.add(schema);
        }
        if (invalidBodyDeclaration) {
            return null;
        }
        if (schemas.isEmpty()) {
            return null;
        }
        if (bodylessSuccess) {
            return null;
        }
        ApiSchema first = schemas.getFirst();
        if (!first.supported() || schemas.stream().anyMatch(schema -> !first.equals(schema))) {
            issues.add(SUCCESS_SCHEMA_UNSUPPORTED);
            return null;
        }
        return first;
    }

    private SuccessMediaSelection selectSuccessMedia(Content content) {
        if (content.size() != 1) {
            return new SuccessMediaSelection(null, SuccessMediaDecision.UNSUPPORTED_MEDIA);
        }
        Map.Entry<String, MediaType> declaration = content.entrySet().iterator().next();
        String mediaTypeName = declaration.getKey();
        MediaType mediaType = declaration.getValue();
        if (mediaTypeName == null) {
            return new SuccessMediaSelection(null, SuccessMediaDecision.UNSUPPORTED_MEDIA);
        }
        String declaredMediaType = mediaTypeName.toLowerCase(java.util.Locale.ROOT);
        SuccessMediaDecision decision;
        if (declaredMediaType.equals("application/json")
                || declaredMediaType.matches("application/[a-z0-9!#$&^_.+-]+\\+json")) {
            decision = SuccessMediaDecision.EXPLICIT_JSON;
        } else if (declaredMediaType.equals("*/*")) {
            decision = SuccessMediaDecision.INFERRED_JSON;
        } else {
            return new SuccessMediaSelection(null, SuccessMediaDecision.UNSUPPORTED_MEDIA);
        }
        return mediaType == null || mediaType.getSchema() == null
                ? new SuccessMediaSelection(null, SuccessMediaDecision.MISSING_SCHEMA)
                : new SuccessMediaSelection(mediaType, decision);
    }

    private enum SuccessMediaDecision {
        EXPLICIT_JSON,
        INFERRED_JSON,
        MISSING_SCHEMA,
        UNSUPPORTED_MEDIA
    }

    private record SuccessMediaSelection(MediaType mediaType, SuccessMediaDecision decision) {
    }

    private boolean isSuccessStatus(String statusCode) {
        if (statusCode == null) {
            return false;
        }
        if ("2XX".equalsIgnoreCase(statusCode)) {
            return true;
        }
        try {
            int status = Integer.parseInt(statusCode);
            return status >= 200 && status < 300;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void addSchemaIssues(List<IssueCode> operationIssues, ApiSchema schema) {
        if (schema == null) {
            return;
        }
        schema.warnings().stream().map(this::schemaIssue).forEach(operationIssues::add);
        schema.properties().values().forEach(property -> addSchemaIssues(operationIssues, property));
        if (schema.items() != null) {
            addSchemaIssues(operationIssues, schema.items());
        }
        if (schema.composition() != null) {
            schema.composition().branches().forEach(branch -> addSchemaIssues(operationIssues, branch));
        }
    }

    private IssueCode schemaIssue(String warning) {
        return java.util.Arrays.stream(IssueCode.values())
                .filter(issue -> issue.message().equals(warning))
                .findFirst()
                .orElse(IssueCode.SCHEMA_CONSTRAINT_UNSUPPORTED);
    }

    private List<ApiParameter> normalizeParameters(
            List<Parameter> pathParameters,
            List<Parameter> operationParameters,
            Map<String, io.swagger.v3.oas.models.media.Schema> componentSchemas,
            String openApiVersion,
            List<IssueCode> issues) {
        Map<String, Parameter> byLocationAndName = new LinkedHashMap<>();
        addParameters(byLocationAndName, pathParameters);
        addParameters(byLocationAndName, operationParameters);
        List<ApiParameter> normalized = new ArrayList<>();
        byLocationAndName.values().forEach(parameter -> {
            ParameterLocation location = location(parameter.getIn());
            if (location == null) {
                issues.add(PARAMETER_LOCATION_UNSUPPORTED);
                return;
            }
            ApiSchema schema = schemaNormalizer.normalizeParameter(
                    parameter.getSchema(), componentSchemas, openApiVersion);
            boolean required = Boolean.TRUE.equals(parameter.getRequired());
            if (schema.nullable() && location == ParameterLocation.PATH) {
                issues.add(PARAMETER_NULLABLE_PATH_UNSUPPORTED);
            } else if (schema.nullable() && required
                    && (location == ParameterLocation.QUERY || location == ParameterLocation.HEADER)) {
                issues.add(PARAMETER_REQUIRED_NULLABLE_UNSUPPORTED);
            }
            if (!supportsParameterSerialization(parameter, location, schema)) {
                issues.add(PARAMETER_SERIALIZATION_UNSUPPORTED);
            }
            normalized.add(new ApiParameter(parameter.getName(), location, required,
                    parameter.getDescription(), schema));
        });
        return List.copyOf(normalized);
    }

    private boolean supportsParameterSerialization(
            Parameter parameter,
            ParameterLocation location,
            ApiSchema schema) {
        if (schema == null || schema.type() == null) {
            return false;
        }
        return switch (schema.type()) {
            case STRING, INTEGER, NUMBER, BOOLEAN -> hasDefaultScalarStyle(parameter, location);
            case ARRAY -> supportsQueryArray(parameter, location, schema.items());
            case OBJECT, COMPOSED -> false;
        };
    }

    private boolean hasDefaultScalarStyle(Parameter parameter, ParameterLocation location) {
        if (parameter.getStyle() == null) {
            return true;
        }
        return switch (location) {
            case PATH, HEADER -> parameter.getStyle() == Parameter.StyleEnum.SIMPLE;
            case QUERY -> parameter.getStyle() == Parameter.StyleEnum.FORM;
            case BODY -> false;
        };
    }

    private boolean supportsQueryArray(
            Parameter parameter,
            ParameterLocation location,
            ApiSchema items) {
        if (location != ParameterLocation.QUERY
                || parameter.getStyle() != null && parameter.getStyle() != Parameter.StyleEnum.FORM
                || Boolean.FALSE.equals(parameter.getExplode())
                || items == null
                || !items.supported()) {
            return false;
        }
        return switch (items.type()) {
            case STRING, INTEGER, NUMBER, BOOLEAN -> true;
            case ARRAY, OBJECT, COMPOSED -> false;
        };
    }

    private void addParameters(Map<String, Parameter> destination, List<Parameter> parameters) {
        if (parameters != null) {
            parameters.forEach(parameter -> destination.put(parameter.getIn() + ":" + parameter.getName(), parameter));
        }
    }

    private ApiSchema normalizeRequestBody(
            RequestBody requestBody,
            Map<String, io.swagger.v3.oas.models.media.Schema> componentSchemas,
            String openApiVersion,
            List<IssueCode> issues) {
        if (requestBody == null) {
            return null;
        }
        MediaType mediaType = preferredApplicationJsonMediaType(requestBody.getContent());
        if (mediaType == null || mediaType.getSchema() == null) {
            issues.add(REQUEST_BODY_MEDIA_TYPE_UNSUPPORTED);
            return schemaNormalizer.normalize(null, componentSchemas, openApiVersion);
        }
        return schemaNormalizer.normalizeRequestBody(mediaType.getSchema(), componentSchemas, openApiVersion);
    }

    private MediaType preferredApplicationJsonMediaType(Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        if (content.get("application/json") != null) {
            return content.get("application/json");
        }
        return null;
    }

    private List<String> securityRequirements(
            List<SecurityRequirement> requirements,
            Map<String, ApiSecurityScheme> securitySchemes,
            List<IssueCode> issues) {
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        if (requirements.size() != 1 || requirements.getFirst() == null || requirements.getFirst().isEmpty()) {
            issues.add(SECURITY_REQUIREMENT_UNSUPPORTED);
            return List.of();
        }
        List<String> names = requirements.getFirst().keySet().stream().sorted().toList();
        for (String name : names) {
            ApiSecurityScheme scheme = securitySchemes.get(name);
            if (scheme == null || !"apiKey".equalsIgnoreCase(scheme.type())
                    || scheme.location() != ParameterLocation.HEADER && scheme.location() != ParameterLocation.QUERY
                    || scheme.parameterName() == null || scheme.parameterName().isBlank()) {
                issues.add(SECURITY_REQUIREMENT_UNSUPPORTED);
                return List.of();
            }
        }
        return names;
    }

    private ApiOperation withIssue(ApiOperation operation, IssueCode issue) {
        List<IssueCode> issues = new ArrayList<>(operation.support().issueCodes());
        issues.add(issue);
        return new ApiOperation(
                operation.operationId(), operation.method(), operation.path(), operation.summary(),
                operation.description(), operation.parameters(), operation.requestBody(),
                operation.requestBodyRequired(), operation.securityRequirements(),
                OperationSupport.fromIssues(issues), operation.successResponse());
    }

    private Map<String, ApiSecurityScheme> normalizeSecuritySchemes(Components components) {
        if (components == null || components.getSecuritySchemes() == null) {
            return Map.of();
        }
        Map<String, ApiSecurityScheme> normalized = new LinkedHashMap<>();
        components.getSecuritySchemes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            SecurityScheme scheme = entry.getValue();
            normalized.put(entry.getKey(), new ApiSecurityScheme(entry.getKey(),
                    scheme.getType() == null ? null : scheme.getType().toString(),
                    scheme.getIn() == null ? null : location(scheme.getIn().toString()), scheme.getName()));
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    private ParameterLocation location(String value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "path" -> ParameterLocation.PATH;
            case "query" -> ParameterLocation.QUERY;
            case "header" -> ParameterLocation.HEADER;
            default -> null;
        };
    }

    private URI baseUrl(io.swagger.v3.oas.models.OpenAPI openApi) {
        if (openApi.getServers() == null || openApi.getServers().isEmpty()) {
            return null;
        }
        try {
            return new URI(openApi.getServers().getFirst().getUrl());
        } catch (URISyntaxException exception) {
            throw GeneratorException.user(SPEC_PARSE_FAILED, SPEC_ANALYSIS, "Server URL is invalid", exception);
        }
    }
}
