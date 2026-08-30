package io.gen2spring.mcp.app.provideregress.egress;

public final class ProviderEgressFailure extends RuntimeException {
    public ProviderEgressFailure() {
        super("Provider egress request failed", null, false, false);
    }
}
