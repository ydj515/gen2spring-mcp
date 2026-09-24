package io.gen2spring.mcp.app.web.presentation.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.hosted.account.AccountStore;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class HostedOidcSecurityTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void mapsOnlyIssuerAndSubjectToAStableInternalAccount() {
        StubAccounts accounts = new StubAccounts();
        HostedAccountResolver resolver = new HostedAccountResolver(
                accounts, Clock.fixed(NOW, ZoneOffset.UTC));

        AccountId first = resolver.resolve(authentication("https://issuer-a.example", "subject-1", "first@example.com"))
                .accountId();
        AccountId replay = resolver.resolve(authentication("https://issuer-a.example", "subject-1", "changed@example.com"))
                .accountId();
        AccountId otherIssuer = resolver.resolve(authentication("https://issuer-b.example", "subject-1", "first@example.com"))
                .accountId();

        assertEquals(first, replay);
        assertNotEquals(first, otherIssuer);
        assertEquals(2, accounts.values.size());
    }

    @Test
    void rejectsNonOidcPrincipalsWithOneFixedFailure() {
        HostedAccountResolver resolver = new HostedAccountResolver(
                new StubAccounts(), Clock.fixed(NOW, ZoneOffset.UTC));

        var failure = assertThrows(
                HostedAccountResolver.HostedAuthenticationFailure.class,
                () -> resolver.resolve(new TestingAuthenticationToken("private-marker", "secret")));

        assertEquals("Hosted account authentication failed", failure.getMessage());
    }

    private TestingAuthenticationToken authentication(String issuer, String subject, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", issuer);
        claims.put("sub", subject);
        claims.put("email", email);
        OidcIdToken token = new OidcIdToken("token", NOW.minusSeconds(1), NOW.plusSeconds(60), claims);
        return new TestingAuthenticationToken(new DefaultOidcUser(List.of(), token), null, "ROLE_USER");
    }

    private static final class StubAccounts implements AccountStore {
        private final Map<String, AccountId> values = new HashMap<>();

        @Override
        public AccountId findOrCreate(String issuer, String subject, Instant observedAt) {
            return values.computeIfAbsent(issuer + "\n" + subject, ignored -> new AccountId(UUID.randomUUID()));
        }
    }
}
