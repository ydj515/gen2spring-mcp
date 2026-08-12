package io.gen2spring.mcp.domain.tool;

import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import java.net.URI;
import java.util.List;

public record HttpExecution(
        HttpMethod method,
        URI baseUrl,
        String path,
        List<ParameterBinding> bindings,
        boolean objectRequestBody,
        boolean requestBodyRequired,
        ResponseNormalizationPolicy responseNormalization,
        RetryPolicy retryPolicy,
        PaginationPolicy paginationPolicy) {
    public HttpExecution(
            HttpMethod method,
            URI baseUrl,
            String path,
            List<ParameterBinding> bindings,
            boolean objectRequestBody,
            boolean requestBodyRequired,
            ResponseNormalizationPolicy responseNormalization,
            RetryPolicy retryPolicy) {
        this(method, baseUrl, path, bindings, objectRequestBody, requestBodyRequired,
                responseNormalization, retryPolicy, null);
    }

    public HttpExecution(
            HttpMethod method,
            URI baseUrl,
            String path,
            List<ParameterBinding> bindings,
            boolean objectRequestBody,
            boolean requestBodyRequired,
            ResponseNormalizationPolicy responseNormalization) {
        this(method, baseUrl, path, bindings, objectRequestBody, requestBodyRequired,
                responseNormalization, null, null);
    }

    public HttpExecution(HttpMethod method, URI baseUrl, String path, List<ParameterBinding> bindings) {
        this(method, baseUrl, path, bindings, false, false, null, null, null);
    }

    public HttpExecution(
            HttpMethod method,
            URI baseUrl,
            String path,
            List<ParameterBinding> bindings,
            boolean objectRequestBody) {
        this(method, baseUrl, path, bindings, objectRequestBody, objectRequestBody, null, null, null);
    }

    public HttpExecution(
            HttpMethod method,
            URI baseUrl,
            String path,
            List<ParameterBinding> bindings,
            boolean objectRequestBody,
            boolean requestBodyRequired) {
        this(method, baseUrl, path, bindings, objectRequestBody, requestBodyRequired, null, null, null);
    }
}
