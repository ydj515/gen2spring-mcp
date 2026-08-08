package io.gen2spring.mcp.domain.tool;

import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import java.net.URI;
import java.util.List;

public record McpToolDefinition(
        String operationId,
        String name,
        String description,
        List<McpInputDefinition> inputs,
        HttpExecutionDefinition execution,
        List<SecretBinding> secretBindings,
        OutputKind outputKind) {
    public enum ParameterSource { USER_INPUT, SERVER_SECRET, SERVER_DEFAULT, CONTEXT_DERIVED, INTERNAL, UNSUPPORTED }
    public enum OutputKind { GENERIC_JSON }

    public record McpInputDefinition(
            String name,
            String jsonName,
            String description,
            boolean required,
            OpenApiDocument.ApiSchema schema) {}

    public record HttpExecutionDefinition(
            OpenApiDocument.HttpMethod method,
            URI baseUrl,
            String path,
            List<ParameterBinding> bindings,
            boolean objectRequestBody,
            boolean requestBodyRequired) {
        public HttpExecutionDefinition(
                OpenApiDocument.HttpMethod method,
                URI baseUrl,
                String path,
                List<ParameterBinding> bindings) {
            this(method, baseUrl, path, bindings, false, false);
        }

        public HttpExecutionDefinition(
                OpenApiDocument.HttpMethod method,
                URI baseUrl,
                String path,
                List<ParameterBinding> bindings,
                boolean objectRequestBody) {
            this(method, baseUrl, path, bindings, objectRequestBody, objectRequestBody);
        }
    }

    public record ParameterBinding(
            String sourceName,
            OpenApiDocument.ParameterLocation targetLocation,
            String targetName) {}

    public record SecretBinding(
            String environmentVariable,
            String propertyName,
            OpenApiDocument.ParameterLocation targetLocation,
            String targetName,
            boolean required) {}
}
