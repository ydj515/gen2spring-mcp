package io.gen2spring.mcp.app.provideregress;

import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import java.time.Duration;

@FunctionalInterface
interface ProviderTransport {
    ProviderCallResponse execute(ProviderCallRequest request, Duration timeout);
}
