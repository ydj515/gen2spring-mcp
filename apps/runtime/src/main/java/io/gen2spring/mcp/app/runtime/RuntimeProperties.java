package io.gen2spring.mcp.app.runtime;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gen2spring.runtime")
public record RuntimeProperties(
        int cacheSize,
        Path tokenPepperFile,
        Encryption encryption,
        URI providerEgressEndpoint,
        Tls tls,
        Database database) {
    public RuntimeProperties {
        if (cacheSize < 1 || cacheSize > 10_000 || tokenPepperFile == null || encryption == null
                || providerEgressEndpoint == null || tls == null || database == null) {
            throw new IllegalArgumentException("Managed runtime configuration is invalid");
        }
        if (!"https".equals(providerEgressEndpoint.getScheme()) || providerEgressEndpoint.getHost() == null
                || !"/internal/provider-call".equals(providerEgressEndpoint.getRawPath())
                || providerEgressEndpoint.getRawUserInfo() != null || providerEgressEndpoint.getRawQuery() != null
                || providerEgressEndpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Managed runtime configuration is invalid");
        }
    }

    public record Encryption(String activeKeyId, Map<String, Path> keyFiles) {
        private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

        public Encryption {
            keyFiles = keyFiles == null ? Map.of() : Map.copyOf(keyFiles);
            if (!ID.matcher(activeKeyId == null ? "" : activeKeyId).matches()
                    || !keyFiles.containsKey(activeKeyId)
                    || keyFiles.entrySet().stream().anyMatch(entry ->
                            !ID.matcher(entry.getKey()).matches()
                                    || entry.getValue() == null || !entry.getValue().isAbsolute()
                                    || !entry.getValue().normalize().equals(entry.getValue()))) {
                throw new IllegalArgumentException("Managed runtime encryption configuration is invalid");
            }
        }
    }

    public record Tls(
            Path keyStore,
            Path keyStorePasswordFile,
            Path trustStore,
            Path trustStorePasswordFile) {
        public Tls {
            if (keyStore == null || keyStorePasswordFile == null
                    || trustStore == null || trustStorePasswordFile == null) {
                throw new IllegalArgumentException("Managed runtime TLS configuration is invalid");
            }
        }
    }

    public record Database(String url, String username, Path passwordFile) {
        public Database {
            if (url == null || url.isBlank() || username == null || username.isBlank() || passwordFile == null) {
                throw new IllegalArgumentException("Managed runtime database configuration is invalid");
            }
        }
    }
}
