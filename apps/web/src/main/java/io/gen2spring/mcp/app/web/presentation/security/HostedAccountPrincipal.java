package io.gen2spring.mcp.app.web.presentation.security;

import io.gen2spring.mcp.domain.platform.identity.AccountId;

public record HostedAccountPrincipal(AccountId accountId) {
    public HostedAccountPrincipal {
        if (accountId == null) throw new IllegalArgumentException("Hosted account is unavailable");
    }
}
