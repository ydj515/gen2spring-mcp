package io.gen2spring.mcp.application.managed.execution;

import java.time.Duration;

public interface ProviderCallClient {
    ProviderCallResponse execute(ProviderCallRequest request, Duration timeout);
}
