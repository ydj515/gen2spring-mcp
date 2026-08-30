package io.gen2spring.mcp.app.provideregress.egress;

import io.gen2spring.mcp.adapter.provideregress.ProviderEgressCodec;
import java.util.Objects;

public final class ProviderEgressService {
    private final ProviderTransport transport;
    private final ProviderEgressCodec codec;

    ProviderEgressService(ProviderTransport transport, ProviderEgressCodec codec) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public byte[] execute(byte[] wire) {
        ProviderEgressCodec.DecodedProviderCall decoded = codec.decodeRequest(wire);
        return codec.encodeResponse(transport.execute(
                ProviderRequestPolicy.requireAllowed(decoded.request()), decoded.timeout()));
    }
}
