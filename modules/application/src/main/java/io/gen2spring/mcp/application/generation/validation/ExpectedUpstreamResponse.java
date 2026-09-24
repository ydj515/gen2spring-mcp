package io.gen2spring.mcp.application.generation.validation;

public record ExpectedUpstreamResponse(int status, String contentType, Object body) {
    public ExpectedUpstreamResponse {
        body = ValidationJsonValue.immutableJsonValue(body, true, true);
    }
}
