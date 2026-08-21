package io.gen2spring.mcp.app.web.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gen2spring.hosted")
public record HostedWebProperties(
        Path workRoot,
        Duration workerStaleAfter,
        Database database,
        Storage storage,
        Encryption encryption,
        Runtime runtime) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public HostedWebProperties {
        if (!absolute(workRoot) || workerStaleAfter == null
                || workerStaleAfter.compareTo(Duration.ofSeconds(10)) < 0
                || workerStaleAfter.compareTo(Duration.ofMinutes(10)) > 0
                || database == null || storage == null || encryption == null || runtime == null) throw invalid();
    }

    public record Database(String url, String username, Path passwordFile) {
        public Database {
            if (url == null || !url.matches("jdbc:postgresql://[^/\\s]+/[^?\\s]+(?:\\?[^\\s]*)?")
                    || username == null || !ID.matcher(username).matches() || !absolute(passwordFile)) throw invalid();
        }
    }

    public record Storage(
            URI endpoint,
            String region,
            String bucket,
            Path accessKeyFile,
            Path secretKeyFile,
            long maxObjectBytes) {
        public Storage {
            if (endpoint == null || !java.util.Set.of("http", "https").contains(endpoint.getScheme())
                    || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                    || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || !ID.matcher(region == null ? "" : region).matches()
                    || !ID.matcher(bucket == null ? "" : bucket).matches()
                    || !absolute(accessKeyFile) || !absolute(secretKeyFile)
                    || maxObjectBytes < 1 || maxObjectBytes >= Integer.MAX_VALUE) throw invalid();
        }
    }

    public record Encryption(String activeKeyId, Map<String, Path> keyFiles) {
        public Encryption {
            keyFiles = keyFiles == null ? Map.of() : Map.copyOf(keyFiles);
            if (!ID.matcher(activeKeyId == null ? "" : activeKeyId).matches()
                    || !keyFiles.containsKey(activeKeyId)
                    || keyFiles.entrySet().stream().anyMatch(entry ->
                            !ID.matcher(entry.getKey()).matches() || !absolute(entry.getValue()))) throw invalid();
        }
    }

    public record Runtime(URI baseUri, Path tokenPepperFile) {
        public Runtime {
            if (baseUri == null || !"https".equals(baseUri.getScheme()) || baseUri.getHost() == null
                    || baseUri.getRawUserInfo() != null || baseUri.getRawQuery() != null
                    || baseUri.getRawFragment() != null
                    || !(baseUri.getRawPath() == null || baseUri.getRawPath().isEmpty()
                            || "/".equals(baseUri.getRawPath()))
                    || !absolute(tokenPepperFile)) throw invalid();
        }
    }

    private static boolean absolute(Path path) {
        return path != null && path.isAbsolute() && path.normalize().equals(path);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Hosted Web configuration is invalid");
    }
}
