package io.gen2spring.mcp.adapter.validation.runtime;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

public final class TomcatServerEndpointDetector implements ServerEndpointDetector {
    private static final Pattern STARTUP_LINE = Pattern.compile(
            "(?m)^[^\\r\\n]*\\bTomcat started on port ([0-9]+) \\(http\\) with context path '[^'\\r\\n]*'\\r?$");
    private final LoopbackPortAllocator portAllocator;

    public TomcatServerEndpointDetector() {
        this(new LoopbackPortAllocator());
    }

    TomcatServerEndpointDetector(LoopbackPortAllocator portAllocator) {
        this.portAllocator = java.util.Objects.requireNonNull(portAllocator, "portAllocator");
    }

    @Override
    public Optional<URI> detect(String boundedOutput) {
        return ServerEndpointDetector.detect(boundedOutput, STARTUP_LINE, portAllocator);
    }
}
