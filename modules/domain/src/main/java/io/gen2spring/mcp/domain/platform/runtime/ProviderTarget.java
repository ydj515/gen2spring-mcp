package io.gen2spring.mcp.domain.platform.runtime;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public record ProviderTarget(URI uri) {
    private static final String INVALID = "Provider target is invalid";
    private static final int MAX_LENGTH = 4096;

    public ProviderTarget {
        uri = canonical(uri);
    }

    public static ProviderTarget parse(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > MAX_LENGTH
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw invalid();
            }
            return new ProviderTarget(URI.create(value));
        } catch (IllegalArgumentException failure) {
            if (INVALID.equals(failure.getMessage())) {
                throw failure;
            }
            throw invalid();
        }
    }

    public String host() {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    public int port() {
        return uri.getPort() >= 0 ? uri.getPort() : "http".equals(uri.getScheme()) ? 80 : 443;
    }

    @Override
    public String toString() {
        return "ProviderTarget[redacted]";
    }

    private static URI canonical(URI value) {
        try {
            if (value == null || !value.isAbsolute() || value.getHost() == null
                    || value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null) {
                throw invalid();
            }
            String scheme = value.getScheme().toLowerCase(Locale.ROOT);
            int explicitPort = value.getPort();
            int requiredPort = "http".equals(scheme) ? 80 : "https".equals(scheme) ? 443 : -1;
            if (requiredPort < 0 || explicitPort != -1 && explicitPort != requiredPort) {
                throw invalid();
            }
            String path = value.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            URI result = new URI(
                    scheme,
                    null,
                    value.getHost().toLowerCase(Locale.ROOT),
                    -1,
                    path,
                    null,
                    null).normalize();
            if (result.toASCIIString().length() > MAX_LENGTH) {
                throw invalid();
            }
            return result;
        } catch (URISyntaxException | IllegalArgumentException failure) {
            if (failure instanceof IllegalArgumentException argument
                    && INVALID.equals(argument.getMessage())) {
                throw argument;
            }
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}
