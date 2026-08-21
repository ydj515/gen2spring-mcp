package io.gen2spring.mcp.app.runtime;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gen2spring.runtime")
public record RuntimeProperties(
        int cacheSize,
        Path tokenPepperFile,
        URI providerEgressEndpoint,
        Tls tls,
        Database database) {
    public RuntimeProperties {
        if (cacheSize < 1 || cacheSize > 10_000 || tokenPepperFile == null
                || providerEgressEndpoint == null || tls == null || database == null) {
            throw new IllegalArgumentException("Managed runtime configuration is invalid");
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
