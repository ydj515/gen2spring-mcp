package io.gen2spring.mcp.application.managed.credential.port.out;

import io.gen2spring.mcp.application.managed.credential.CredentialSecret;
import io.gen2spring.mcp.application.managed.credential.ProtectedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;

public interface CredentialProtector {
    ProtectedCredential protect(
            AccountId owner,
            ManagedCredentialId id,
            long version,
            CredentialSecret secret);

    CredentialSecret reveal(
            AccountId owner,
            ManagedCredentialId id,
            long version,
            ProtectedCredential protectedCredential);
}
