package io.gen2spring.mcp.domain.tool;

import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import java.net.URI;
import java.util.List;
import java.util.Objects;

public record McpToolDefinition(
        String operationId,
        String name,
        String description,
        List<McpInputDefinition> inputs,
        HttpExecutionDefinition execution,
        List<SecretBinding> secretBindings,
        OutputDefinition output) {
    public enum ParameterSource { USER_INPUT, SERVER_SECRET, SERVER_DEFAULT, CONTEXT_DERIVED, INTERNAL, UNSUPPORTED }
    public enum OutputKind { GENERIC_JSON, TYPED_DTO }

    public McpToolDefinition {
        Objects.requireNonNull(output, "output");
    }

    public McpToolDefinition(
            String operationId,
            String name,
            String description,
            List<McpInputDefinition> inputs,
            HttpExecutionDefinition execution,
            List<SecretBinding> secretBindings,
            OutputKind outputKind) {
        this(operationId, name, description, inputs, execution, secretBindings,
                new OutputDefinition(outputKind, null, null));
    }

    public OutputKind outputKind() {
        return output.kind();
    }

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
            boolean requestBodyRequired,
            ResponseNormalizationPolicy responseNormalization,
            RetryPolicy retryPolicy) {
        public HttpExecutionDefinition(
                OpenApiDocument.HttpMethod method,
                URI baseUrl,
                String path,
                List<ParameterBinding> bindings,
                boolean objectRequestBody,
                boolean requestBodyRequired,
                ResponseNormalizationPolicy responseNormalization) {
            this(method, baseUrl, path, bindings, objectRequestBody, requestBodyRequired, responseNormalization, null);
        }

        public HttpExecutionDefinition(
                OpenApiDocument.HttpMethod method,
                URI baseUrl,
                String path,
                List<ParameterBinding> bindings) {
            this(method, baseUrl, path, bindings, false, false, null, null);
        }

        public HttpExecutionDefinition(
                OpenApiDocument.HttpMethod method,
                URI baseUrl,
                String path,
                List<ParameterBinding> bindings,
                boolean objectRequestBody) {
            this(method, baseUrl, path, bindings, objectRequestBody, objectRequestBody, null, null);
        }

        public HttpExecutionDefinition(
                OpenApiDocument.HttpMethod method,
                URI baseUrl,
                String path,
                List<ParameterBinding> bindings,
                boolean objectRequestBody,
                boolean requestBodyRequired) {
            this(method, baseUrl, path, bindings, objectRequestBody, requestBodyRequired, null, null);
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
