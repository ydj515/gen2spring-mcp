package io.gen2spring.mcp.app.provideregress.application.provider;

import io.gen2spring.mcp.app.provideregress.application.provider.port.out.ProviderCallCodec;
import io.gen2spring.mcp.app.provideregress.application.provider.port.out.ProviderTransport;
import java.util.Objects;

public final class ProviderEgressService {
    public static final int MAX_REQUEST_BYTES = 4_000_000;

    private final ProviderTransport transport;
    private final ProviderCallCodec codec;

    public ProviderEgressService(ProviderTransport transport, ProviderCallCodec codec) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public byte[] execute(byte[] wire) {
        ProviderCallCodec.DecodedCall decoded = codec.decodeRequest(wire);
        return codec.encodeResponse(transport.execute(
                ProviderRequestPolicy.requireAllowed(decoded.request()), decoded.timeout()));
    }
}
