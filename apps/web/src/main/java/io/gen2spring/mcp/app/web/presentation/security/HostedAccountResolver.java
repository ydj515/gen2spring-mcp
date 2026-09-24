package io.gen2spring.mcp.app.web.presentation.security;

import io.gen2spring.mcp.app.web.application.hosted.service.HostedAccountService;
import io.gen2spring.mcp.app.web.presentation.error.HostedAuthenticationFailure;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class HostedAccountResolver {
    private final HostedAccountService accounts;

    public HostedAccountResolver(HostedAccountService accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    public HostedAccountPrincipal resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OidcUser user)
                || user.getIdToken() == null || user.getIdToken().getIssuer() == null
                || user.getSubject() == null || user.getSubject().isBlank()) {
            throw new HostedAuthenticationFailure();
        }
        try {
            return new HostedAccountPrincipal(accounts.findOrCreate(
                    user.getIdToken().getIssuer().toString(), user.getSubject()));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new HostedAuthenticationFailure();
        }
    }

}
