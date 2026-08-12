package io.gen2spring.mcp.domain.platform.imports;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record ImportTarget(URI uri) {
    private static final String INVALID = "Import target is invalid";
    private static final Pattern DNS_NAME = Pattern.compile(
            "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*"
                    + "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9.]+");
    private static final Set<String> METADATA_ALIASES = Set.of(
            "metadata",
            "instance-data",
            "metadata.google.internal",
            "metadata.azure.internal");

    public ImportTarget {
        if (uri == null) {
            throw invalid();
        }
        String scheme = lower(uri.getScheme());
        String host = canonicalHost(uri.getHost());
        int explicitPort = uri.getPort();
        int requiredPort = "http".equals(scheme) ? 80 : "https".equals(scheme) ? 443 : -1;
        if (requiredPort < 0
                || host == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || (explicitPort != -1 && explicitPort != requiredPort)
                || uri.toASCIIString().length() > 4096
                || METADATA_ALIASES.contains(host)
                || !validHost(host)) {
            throw invalid();
        }
    }

    public static ImportTarget parse(String value) {
        try {
            if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
                throw invalid();
            }
            return new ImportTarget(URI.create(value));
        } catch (IllegalArgumentException failure) {
            if (INVALID.equals(failure.getMessage())) {
                throw failure;
            }
            throw invalid();
        }
    }

    public String host() {
        return canonicalHost(uri.getHost());
    }

    public int port() {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    @Override
    public String toString() {
        return "ImportTarget[redacted]";
    }

    private static boolean validHost(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return IPV4_LITERAL.matcher(host).matches() || DNS_NAME.matcher(host).matches();
    }

    private static String canonicalHost(String host) {
        if (host == null) {
            return null;
        }
        String canonical = host.toLowerCase(Locale.ROOT);
        if (canonical.startsWith("[") && canonical.endsWith("]")) {
            return canonical.substring(1, canonical.length() - 1);
        }
        return canonical;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }
}
