package io.gen2spring.mcp.adapter.validation;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public interface ServerEndpointDetector {
    int MAX_OUTPUT_BYTES = 64 * 1024;

    Optional<URI> detect(String boundedOutput);

    static Optional<URI> detect(
            String output,
            Pattern startupLine,
            LoopbackPortAllocator portAllocator) {
        Objects.requireNonNull(startupLine, "startupLine");
        Objects.requireNonNull(portAllocator, "portAllocator");
        if (!isSafeBoundedOutput(output)) {
            return Optional.empty();
        }
        var matcher = startupLine.matcher(output);
        URI endpoint = null;
        int matches = 0;
        while (matcher.find()) {
            if (++matches > 1) {
                return Optional.empty();
            }
            try {
                int port = Integer.parseInt(matcher.group(1));
                endpoint = portAllocator.mcpUri(port);
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(endpoint);
    }

    private static boolean isSafeBoundedOutput(String output) {
        if (output == null || output.length() > MAX_OUTPUT_BYTES
                || output.getBytes(UTF_8).length > MAX_OUTPUT_BYTES) {
            return false;
        }
        for (int index = 0; index < output.length(); index++) {
            char character = output.charAt(index);
            if (character == '\n') {
                continue;
            }
            if (character == '\r' && index + 1 < output.length() && output.charAt(index + 1) == '\n') {
                continue;
            }
            if (Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }
}
