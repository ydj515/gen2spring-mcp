package io.gen2spring.mcp.app.provideregress.application.provider.port.out;

import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import java.time.Duration;

public interface ProviderCallCodec {
    DecodedCall decodeRequest(byte[] wire);

    byte[] encodeResponse(ProviderCallResponse response);

    record DecodedCall(ProviderCallRequest request, Duration timeout) {}
}
