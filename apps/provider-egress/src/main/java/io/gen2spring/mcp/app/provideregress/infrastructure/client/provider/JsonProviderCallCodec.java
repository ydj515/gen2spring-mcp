package io.gen2spring.mcp.app.provideregress.infrastructure.client.provider;

import io.gen2spring.mcp.adapter.provideregress.ProviderEgressCodec;
import io.gen2spring.mcp.app.provideregress.application.provider.port.out.ProviderCallCodec;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import java.util.Objects;

public final class JsonProviderCallCodec implements ProviderCallCodec {
    private final ProviderEgressCodec delegate;

    public JsonProviderCallCodec(ProviderEgressCodec delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public DecodedCall decodeRequest(byte[] wire) {
        ProviderEgressCodec.DecodedProviderCall decoded = delegate.decodeRequest(wire);
        return new DecodedCall(decoded.request(), decoded.timeout());
    }

    @Override
    public byte[] encodeResponse(ProviderCallResponse response) {
        return delegate.encodeResponse(response);
    }
}
