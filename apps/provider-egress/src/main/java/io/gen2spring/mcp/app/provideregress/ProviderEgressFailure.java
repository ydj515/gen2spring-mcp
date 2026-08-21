package io.gen2spring.mcp.app.provideregress;

final class ProviderEgressFailure extends RuntimeException {
    ProviderEgressFailure() {
        super("Provider egress request failed", null, false, false);
    }
}
