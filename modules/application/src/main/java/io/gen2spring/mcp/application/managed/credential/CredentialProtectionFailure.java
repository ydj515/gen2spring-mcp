package io.gen2spring.mcp.application.managed.credential;

public final class CredentialProtectionFailure extends RuntimeException {
    public CredentialProtectionFailure() {
        super("Managed credential protection failed", null, false, false);
    }
}
