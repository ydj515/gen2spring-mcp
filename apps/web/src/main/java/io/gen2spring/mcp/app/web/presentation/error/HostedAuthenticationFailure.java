package io.gen2spring.mcp.app.web.presentation.error;

public final class HostedAuthenticationFailure extends RuntimeException {
    public HostedAuthenticationFailure() {
        super("Hosted account authentication failed", null, false, false);
    }
}
