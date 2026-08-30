package io.gen2spring.mcp.app.worker.config;

import io.gen2spring.mcp.application.hosted.worker.SandboxLimits;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gen2spring.worker")
public record WorkerProperties(
        String workerId,
        Duration pollInterval,
        Duration leaseDuration,
        Duration artifactRetention,
        Docker docker,
        Storage storage,
        Gateway gateway,
        Encryption encryption,
        Limits limits) {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public WorkerProperties {
        if (!identifier(workerId)
                || !duration(pollInterval, Duration.ofSeconds(10))
                || !duration(leaseDuration, Duration.ofMinutes(5))
                || !duration(artifactRetention, Duration.ofDays(365))
                || artifactRetention.compareTo(Duration.ofHours(1)) < 0
                || pollInterval.compareTo(leaseDuration) >= 0
                || docker == null
                || storage == null
                || gateway == null
                || encryption == null
                || limits == null) {
            throw invalid();
        }
    }

    public record Docker(
            Path executable,
            Path socket,
            String generationImage,
            String importImage,
            String importNetwork,
            Path workspaceRoot,
            Path secretRoot) {
        private static final Pattern SOCKET = Pattern.compile("/run/user/[1-9][0-9]*/docker\\.sock");
        private static final Pattern IMAGE = Pattern.compile(
                "[a-z0-9][a-z0-9._/-]{0,255}@sha256:[a-f0-9]{64}");
        private static final Set<String> FORBIDDEN_NETWORKS = Set.of("bridge", "default", "host", "none");

        public Docker {
            if (!absolute(executable)
                    || !absolute(socket)
                    || !SOCKET.matcher(socket.normalize().toString()).matches()
                    || generationImage == null
                    || !IMAGE.matcher(generationImage).matches()
                    || importImage == null
                    || !IMAGE.matcher(importImage).matches()
                    || !identifier(importNetwork)
                    || FORBIDDEN_NETWORKS.contains(importNetwork)
                    || !absolute(workspaceRoot)
                    || !absolute(secretRoot)
                    || workspaceRoot.equals(secretRoot)) {
                throw invalid();
            }
        }
    }

    public record Encryption(String activeKeyId, Map<String, Path> keyFiles) {
        public Encryption {
            if (!identifier(activeKeyId) || keyFiles == null || keyFiles.isEmpty()) {
                throw invalid();
            }
            keyFiles = Map.copyOf(keyFiles);
            if (!keyFiles.containsKey(activeKeyId)
                    || keyFiles.entrySet().stream().anyMatch(entry ->
                            !identifier(entry.getKey()) || !absolute(entry.getValue()))) {
                throw invalid();
            }
        }
    }

    public record Storage(
            URI endpoint,
            String region,
            String bucket,
            Path accessKeyFile,
            Path secretKeyFile,
            long maxObjectBytes) {
        private static final Pattern REGION = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
        private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");

        public Storage {
            if (!validEndpoint(endpoint, Set.of("http", "https"), null)
                    || region == null
                    || !REGION.matcher(region).matches()
                    || bucket == null
                    || !BUCKET.matcher(bucket).matches()
                    || !absolute(accessKeyFile)
                    || !absolute(secretKeyFile)
                    || maxObjectBytes < 1
                    || maxObjectBytes >= Integer.MAX_VALUE) {
                throw invalid();
            }
        }
    }

    public record Gateway(
            URI endpoint,
            Path keyStore,
            Path keyStorePasswordFile,
            Path trustStore,
            Path trustStorePasswordFile) {
        public Gateway {
            if (!validEndpoint(endpoint, Set.of("https"), "/internal/fetch")
                    || !absolute(keyStore)
                    || !absolute(keyStorePasswordFile)
                    || !absolute(trustStore)
                    || !absolute(trustStorePasswordFile)) {
                throw invalid();
            }
        }
    }

    public record Limits(double cpus, long memoryBytes, int pids, Duration timeout) {
        public Limits {
            try {
                new SandboxLimits(cpus, memoryBytes, pids, timeout);
            } catch (RuntimeException failure) {
                throw invalid();
            }
        }

        SandboxLimits sandboxLimits() {
            return new SandboxLimits(cpus, memoryBytes, pids, timeout);
        }
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static boolean duration(Duration value, Duration maximum) {
        return value != null && !value.isZero() && !value.isNegative() && value.compareTo(maximum) <= 0;
    }

    private static boolean absolute(Path value) {
        return value != null && value.isAbsolute() && value.normalize().equals(value);
    }

    private static boolean validEndpoint(URI value, Set<String> schemes, String path) {
        return value != null
                && schemes.contains(value.getScheme())
                && value.getHost() != null
                && value.getRawUserInfo() == null
                && value.getRawQuery() == null
                && value.getRawFragment() == null
                && (path == null || Objects.equals(path, value.getRawPath()));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Hosted worker configuration is invalid");
    }
}
