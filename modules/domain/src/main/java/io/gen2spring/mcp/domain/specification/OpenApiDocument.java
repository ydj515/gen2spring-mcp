package io.gen2spring.mcp.domain.specification;

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
    public enum HttpMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE }
    public enum ParameterLocation { PATH, QUERY, HEADER, BODY }
    public enum SchemaType { STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT, COMPOSED }
    public enum CompositionKind { ONE_OF, ANY_OF }

    public record SchemaComposition(CompositionKind kind, List<ApiSchema> branches) {
        public SchemaComposition {
            branches = branches == null ? List.of() : List.copyOf(branches);
            if (kind == null || branches.isEmpty() || branches.size() > 8
                    || branches.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Schema composition is invalid");
            }
        }
    }

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
            OperationSupport support,
            ApiSchema successResponse) {
        public ApiOperation(
                String operationId,
                HttpMethod method,
                String path,
                String summary,
                String description,
                List<ApiParameter> parameters,
                ApiSchema requestBody,
                boolean requestBodyRequired,
                List<String> securityRequirements,
                OperationSupport support) {
            this(operationId, method, path, summary, description, parameters, requestBody, requestBodyRequired,
                    securityRequirements, support, null);
        }

        public boolean supported() {
            return support.selectable();
        }

        public List<String> warnings() {
            return support.issues().stream().map(OperationSupport.Issue::message).toList();
        }
    }

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
            Integer minItems,
            Integer maxItems,
            boolean uniqueItems,
            SchemaComposition composition,
            boolean supported,
            List<String> warnings) {
        public ApiSchema(
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
                Integer minItems,
                boolean supported,
                List<String> warnings) {
            this(type, format, nullable, enumValues, minimum, maximum, minLength, maxLength,
                    pattern, defaultValue, properties, requiredProperties, items, minItems,
                    null, false, null, supported, warnings);
        }

        public ApiSchema(
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
                List<String> warnings) {
            this(type, format, nullable, enumValues, minimum, maximum, minLength, maxLength,
                    pattern, defaultValue, properties, requiredProperties, items, null,
                    null, false, null, supported, warnings);
        }
    }

    public record ApiSecurityScheme(
            String name,
            String type,
            ParameterLocation location,
            String parameterName) {}

    public record AnalysisWarning(String code, String message, String operationId) {}
}
