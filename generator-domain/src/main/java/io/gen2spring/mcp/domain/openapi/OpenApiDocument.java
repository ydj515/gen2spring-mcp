package io.gen2spring.mcp.domain.openapi;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

public record OpenApiDocument(
        String openApiVersion,
        String checksum,
        String sourceExtension,
        URI baseUrl,
        List<ApiOperation> operations,
        Map<String, ApiSecurityScheme> securitySchemes,
        List<AnalysisWarning> warnings) {
    public enum HttpMethod { GET, POST, PUT, PATCH, DELETE }
    public enum ParameterLocation { PATH, QUERY, HEADER, BODY }
    public enum SchemaType { STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT }

    public record ApiOperation(
            String operationId,
            HttpMethod method,
            String path,
            String summary,
            String description,
            List<ApiParameter> parameters,
            ApiSchema requestBody,
            boolean requestBodyRequired,
            List<String> securityRequirements,
            boolean supported,
            List<String> warnings) {}

    public record ApiParameter(
            String name,
            ParameterLocation location,
            boolean required,
            String description,
            ApiSchema schema) {}

    public record ApiSchema(
            SchemaType type,
            String format,
            boolean nullable,
            List<String> enumValues,
            BigDecimal minimum,
            BigDecimal maximum,
            Integer minLength,
            Integer maxLength,
            String pattern,
            Object defaultValue,
            Map<String, ApiSchema> properties,
            List<String> requiredProperties,
            ApiSchema items,
            boolean supported,
            List<String> warnings) {}

    public record ApiSecurityScheme(
            String name,
            String type,
            ParameterLocation location,
            String parameterName) {}

    public record AnalysisWarning(String code, String message, String operationId) {}
}
