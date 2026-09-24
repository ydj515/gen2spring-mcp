package io.gen2spring.mcp.app.web.presentation.security;

import io.gen2spring.mcp.application.hosted.account.port.out.AccountStore;
import java.time.Clock;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class HostedAccountResolver {
    private final AccountStore accounts;
    private final Clock clock;

    public HostedAccountResolver(AccountStore accounts, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.clock = Objects.requireNonNull(clock, "clock");
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
                    user.getIdToken().getIssuer().toString(), user.getSubject(), clock.instant()));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new HostedAuthenticationFailure();
        }
    }

    public static final class HostedAuthenticationFailure extends RuntimeException {
        public HostedAuthenticationFailure() {
            super("Hosted account authentication failed", null, false, false);
        }
    }
}
