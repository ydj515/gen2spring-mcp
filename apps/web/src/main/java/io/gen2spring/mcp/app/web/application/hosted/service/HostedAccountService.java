package io.gen2spring.mcp.app.web.application.hosted.service;

import io.gen2spring.mcp.application.hosted.account.port.out.AccountStore;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Clock;
import java.util.Objects;

public final class HostedAccountService {
    private final AccountStore accounts;
    private final Clock clock;

    public HostedAccountService(AccountStore accounts, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AccountId findOrCreate(String issuer, String subject) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Hosted account identity is invalid");
        }
        return accounts.findOrCreate(issuer, subject, clock.instant());
    }
}
