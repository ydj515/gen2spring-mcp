package io.gen2spring.mcp.app.web.application.hosted.exception;

public final class HostedResourceNotFound extends RuntimeException {
    public HostedResourceNotFound() {
        super("Hosted resource was not found", null, false, false);
    }
}
