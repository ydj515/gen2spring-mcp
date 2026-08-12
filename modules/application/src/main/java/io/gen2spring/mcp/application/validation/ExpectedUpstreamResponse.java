package io.gen2spring.mcp.application.validation;

public record ExpectedUpstreamResponse(int status, String contentType, Object body) {
    public ExpectedUpstreamResponse {
        body = ValidationJsonValue.immutableJsonValue(body, true, true);
    }
}
