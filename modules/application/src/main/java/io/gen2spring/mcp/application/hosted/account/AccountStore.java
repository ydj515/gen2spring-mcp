package io.gen2spring.mcp.application.hosted.account;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Instant;

public interface AccountStore {
    AccountId findOrCreate(String issuer, String subject, Instant observedAt);
}
