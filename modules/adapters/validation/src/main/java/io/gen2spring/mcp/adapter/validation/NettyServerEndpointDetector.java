package io.gen2spring.mcp.adapter.validation;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

public final class NettyServerEndpointDetector implements ServerEndpointDetector {
    private static final Pattern STARTUP_LINE = Pattern.compile(
            "(?m)^[^\\r\\n]*\\bNetty started on port ([0-9]+) \\(http\\)\\r?$");
    private final LoopbackPortAllocator portAllocator;

    public NettyServerEndpointDetector() {
        this(new LoopbackPortAllocator());
    }

    NettyServerEndpointDetector(LoopbackPortAllocator portAllocator) {
        this.portAllocator = java.util.Objects.requireNonNull(portAllocator, "portAllocator");
    }

    @Override
    public Optional<URI> detect(String boundedOutput) {
        return ServerEndpointDetector.detect(boundedOutput, STARTUP_LINE, portAllocator);
    }
}
