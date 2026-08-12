package io.gen2spring.mcp.adapter.openapi.swagger;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.OPERATION_ID_DUPLICATED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_PARSE_FAILED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_VERSION_UNSUPPORTED;

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

public final class SwaggerOpenApiAnalyzer implements SpecificationAnalyzer {
    private static final String SPEC_ANALYSIS = "SPEC_ANALYSIS";
    private static final List<HttpMethod> METHOD_ORDER = List.of(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

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
        io.swagger.v3.oas.models.OpenAPI openApi = parse(loaded);
        validateVersion(openApi.getOpenapi());

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

    private void validateVersion(String version) {
        if (version == null || !version.startsWith("3.0.")) {
            throw GeneratorException.user(SPEC_VERSION_UNSUPPORTED, SPEC_ANALYSIS,
                    "Only OpenAPI 3.0 specifications are supported");
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
        Set<String> operationIds = new java.util.HashSet<>();
        openApi.getPaths().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(pathEntry -> {
            Map<HttpMethod, Operation> methods = methods(pathEntry.getValue());
            METHOD_ORDER.forEach(method -> {
                Operation operation = methods.get(method);
                if (operation != null) {
                    ApiOperation normalized = normalizeOperation(openApi, pathEntry.getKey(), method,
                            pathEntry.getValue().getParameters(), operation,
                            preflight.hasRecursiveSchema(pathEntry.getKey(), method.name()), securitySchemes);
                    if (normalized.operationId() != null && !normalized.operationId().isBlank()
                            && !operationIds.add(normalized.operationId())) {
                        throw GeneratorException.user(OPERATION_ID_DUPLICATED, SPEC_ANALYSIS,
                                "Duplicate operationId: " + normalized.operationId());
                    }
                    operations.add(normalized);
                    normalized.warnings().forEach(warning -> documentWarnings.add(
                            new AnalysisWarning("OPERATION_UNSUPPORTED", warning, normalized.operationId())));
                }
            });
        });
        return operations;
    }

    private Map<HttpMethod, Operation> methods(PathItem pathItem) {
        Map<HttpMethod, Operation> operations = new EnumMap<>(HttpMethod.class);
        operations.put(HttpMethod.GET, pathItem.getGet());
        operations.put(HttpMethod.POST, pathItem.getPost());
        operations.put(HttpMethod.PUT, pathItem.getPut());
        operations.put(HttpMethod.PATCH, pathItem.getPatch());
        operations.put(HttpMethod.DELETE, pathItem.getDelete());
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
        List<String> warnings = new ArrayList<>();
        String operationId = operation.getOperationId();
        if (operationId == null || operationId.isBlank()) {
            warnings.add("Operations must declare operationId");
        }
        if (hasRecursiveSchema) {
            warnings.add("Recursive schemas are not supported");
        }

        Map<String, io.swagger.v3.oas.models.media.Schema> componentSchemas = openApi.getComponents() == null
                ? Map.of() : openApi.getComponents().getSchemas();
        List<ApiParameter> parameters = normalizeParameters(
                pathParameters, operation.getParameters(), componentSchemas, warnings);
        ApiSchema requestBody = normalizeRequestBody(operation.getRequestBody(), componentSchemas, warnings);
        if (method == HttpMethod.GET && operation.getRequestBody() != null) {
            warnings.add("GET operations with request bodies are not supported");
        }
        validateSuccessResponseMediaTypes(operation, warnings);
        ApiSchema successResponse = normalizeSuccessResponse(operation, componentSchemas, warnings);
        parameters.forEach(parameter -> addSchemaWarnings(warnings, parameter.schema()));
        addSchemaWarnings(warnings, requestBody);
        if (parameters.stream().anyMatch(parameter -> !parameter.schema().supported())
                || requestBody != null && !requestBody.supported()) {
            warnings.add("Operation contains an unsupported schema");
        }

        return new ApiOperation(operationId, method, path, operation.getSummary(), operation.getDescription(),
                parameters, requestBody, operation.getRequestBody() != null
                        && Boolean.TRUE.equals(operation.getRequestBody().getRequired()),
                securityRequirements(operation.getSecurity() == null ? openApi.getSecurity() : operation.getSecurity(),
                        securitySchemes, warnings),
                warnings.isEmpty(), List.copyOf(warnings), successResponse);
    }

    private ApiSchema normalizeSuccessResponse(
            Operation operation,
            Map<String, io.swagger.v3.oas.models.media.Schema> componentSchemas,
            List<String> warnings) {
        if (operation.getResponses() == null) {
            return null;
        }
        List<ApiSchema> schemas = new ArrayList<>();
        boolean missingSchema = false;
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
            MediaType mediaType = response.getContent().size() == 1
                    ? response.getContent().get("application/json") : null;
            if (mediaType == null) {
                continue;
            }
            if (mediaType.getSchema() == null) {
                missingSchema = true;
                continue;
            }
            schemas.add(schemaNormalizer.normalizeResponse(mediaType.getSchema(), componentSchemas));
        }
        if (missingSchema) {
            warnings.add("Success response schemas must be supported and structurally identical");
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
            warnings.add("Success response schemas must be supported and structurally identical");
            return null;
        }
        return first;
    }

    private void validateSuccessResponseMediaTypes(Operation operation, List<String> warnings) {
        if (operation.getResponses() == null) {
            return;
        }
        operation.getResponses().forEach((statusCode, response) -> {
            if (!isSuccessStatus(statusCode) || response == null || response.getContent() == null
                    || response.getContent().isEmpty()) {
                return;
            }
            if (response.getContent().size() != 1 || response.getContent().get("application/json") == null) {
                warnings.add("Success responses with bodies must declare an application/json media type");
            }
        });
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

    private void addSchemaWarnings(List<String> operationWarnings, ApiSchema schema) {
        if (schema != null && !schema.supported()) {
            operationWarnings.addAll(schema.warnings());
        }
    }

    private List<ApiParameter> normalizeParameters(
            List<Parameter> pathParameters,
            List<Parameter> operationParameters,
            Map<String, io.swagger.v3.oas.models.media.Schema> componentSchemas,
            List<String> warnings) {
        Map<String, Parameter> byLocationAndName = new LinkedHashMap<>();
        addParameters(byLocationAndName, pathParameters);
        addParameters(byLocationAndName, operationParameters);
        List<ApiParameter> normalized = new ArrayList<>();
        byLocationAndName.values().forEach(parameter -> {
            ParameterLocation location = location(parameter.getIn());
            if (location == null) {
                warnings.add("Parameter " + parameter.getName() + " uses unsupported location " + parameter.getIn());
                return;
            }
            ApiSchema schema = schemaNormalizer.normalize(parameter.getSchema(), componentSchemas);
            if (!supportsParameterSerialization(parameter, location, schema)) {
                warnings.add("Parameter " + parameter.getName()
                        + " uses an unsupported P0 parameter serialization style or schema shape");
            }
            normalized.add(new ApiParameter(parameter.getName(), location, Boolean.TRUE.equals(parameter.getRequired()),
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
            case OBJECT -> false;
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
            case ARRAY, OBJECT -> false;
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
            List<String> warnings) {
        if (requestBody == null) {
            return null;
        }
        MediaType mediaType = preferredApplicationJsonMediaType(requestBody.getContent());
        if (mediaType == null || mediaType.getSchema() == null) {
            warnings.add("Request body must declare an application/json schema");
            return schemaNormalizer.normalize(null, componentSchemas);
        }
        return schemaNormalizer.normalize(mediaType.getSchema(), componentSchemas);
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
            List<String> warnings) {
        if (requirements == null || requirements.isEmpty()) {
            return List.of();
        }
        if (requirements.size() != 1 || requirements.getFirst() == null || requirements.getFirst().isEmpty()) {
            warnings.add("Security must use exactly one non-empty API key requirement alternative");
            return List.of();
        }
        List<String> names = requirements.getFirst().keySet().stream().sorted().toList();
        for (String name : names) {
            ApiSecurityScheme scheme = securitySchemes.get(name);
            if (scheme == null || !"apiKey".equalsIgnoreCase(scheme.type())
                    || scheme.location() != ParameterLocation.HEADER && scheme.location() != ParameterLocation.QUERY
                    || scheme.parameterName() == null || scheme.parameterName().isBlank()) {
                warnings.add("Security scheme " + name + " is not a supported API key header or query scheme");
                return List.of();
            }
        }
        return names;
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
