package io.gen2spring.mcp.adapter.urlfetch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.imports.port.out.UrlFetchClient;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class GatewayUrlFetchClient implements UrlFetchClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final URI endpoint;
    private final HttpClient client;
    private final int maxBytes;
    private final ObjectMapper mapper = new ObjectMapper();

    public GatewayUrlFetchClient(
            URI endpoint,
            Path keyStorePath,
            char[] keyStorePassword,
            Path trustStorePath,
            char[] trustStorePassword,
            int maxBytes) {
        this.endpoint = requireEndpoint(endpoint);
        if (maxBytes < 1 || maxBytes > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("URL fetch configuration is invalid");
        }
        this.maxBytes = maxBytes;
        char[] keyPassword = null;
        char[] trustPassword = null;
        try {
            keyPassword = copyPassword(keyStorePassword);
            trustPassword = copyPassword(trustStorePassword);
            this.client = HttpClient.newBuilder()
                    .sslContext(sslContext(
                            requireStore(keyStorePath),
                            keyPassword,
                            requireStore(trustStorePath),
                            trustPassword))
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        } catch (Exception failure) {
            throw new IllegalArgumentException("URL fetch configuration is invalid");
        } finally {
            if (keyPassword != null) {
                Arrays.fill(keyPassword, '\0');
            }
            if (trustPassword != null) {
                Arrays.fill(trustPassword, '\0');
            }
        }
    }

    @Override
    public FetchedSpecification fetch(ImportTarget target) {
        if (target == null) {
            throw failed();
        }
        byte[] requestBody = null;
        try {
            requestBody = mapper.writeValueAsBytes(new FetchRequest(target.uri().toString()));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw failed();
                }
                String mediaType = response.headers()
                        .firstValue("content-type")
                        .map(this::canonicalMediaType)
                        .orElseThrow(this::failed);
                byte[] source = readBounded(body);
                if (source.length < 1) {
                    throw failed();
                }
                return new BufferedFetchedSpecification(source, mediaType);
            }
        } catch (Error fatal) {
            throw fatal;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failed();
        } catch (Exception failure) {
            throw failed();
        } finally {
            if (requestBody != null) {
                Arrays.fill(requestBody, (byte) 0);
            }
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int remaining = maxBytes - total;
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining + 1));
            if (read < 0) {
                return output.toByteArray();
            }
            total += read;
            if (total > maxBytes) {
                throw failed();
            }
            output.write(buffer, 0, read);
        }
    }

    private String canonicalMediaType(String value) {
        String mediaType = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (mediaType.equals("application/json")
                || mediaType.endsWith("+json")
                || mediaType.equals("application/yaml")
                || mediaType.equals("application/x-yaml")
                || mediaType.equals("text/yaml")
                || mediaType.equals("text/x-yaml")
                || mediaType.endsWith("+yaml")) {
            return mediaType;
        }
        throw failed();
    }

    private SSLContext sslContext(
            Path keyStorePath,
            char[] keyPassword,
            Path trustStorePath,
            char[] trustPassword) throws Exception {
        KeyStore keyStore = loadStore(keyStorePath, keyPassword);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, keyPassword);
        KeyStore trustStore = loadStore(trustStorePath, trustPassword);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return context;
    }

    private KeyStore loadStore(Path path, char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            keyStore.load(input, password);
        }
        return keyStore;
    }

    private Path requireStore(Path path) {
        if (path == null
                || !path.isAbsolute()
                || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("URL fetch configuration is invalid");
        }
        return path;
    }

    private URI requireEndpoint(URI endpoint) {
        if (endpoint == null
                || !"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || !"/internal/fetch".equals(endpoint.getRawPath())) {
            throw new IllegalArgumentException("URL fetch configuration is invalid");
        }
        return endpoint;
    }

    private char[] copyPassword(char[] password) {
        if (password == null || password.length < 1 || password.length > 1024) {
            throw new IllegalArgumentException("URL fetch configuration is invalid");
        }
        return Arrays.copyOf(password, password.length);
    }

    private UrlFetchFailure failed() {
        return new UrlFetchFailure();
    }

    private record FetchRequest(String target) {}

    private static final class BufferedFetchedSpecification implements FetchedSpecification {
        private byte[] source;
        private final String mediaType;

        private BufferedFetchedSpecification(byte[] source, String mediaType) {
            this.source = Arrays.copyOf(Objects.requireNonNull(source, "source"), source.length);
            this.mediaType = Objects.requireNonNull(mediaType, "mediaType");
        }

        @Override
        public InputStream body() {
            if (source == null) {
                throw new UrlFetchFailure();
            }
            return new ByteArrayInputStream(source);
        }

        @Override
        public long size() {
            return source == null ? 0 : source.length;
        }

        @Override
        public String mediaType() {
            return mediaType;
        }

        @Override
        public void close() {
            if (source != null) {
                Arrays.fill(source, (byte) 0);
                source = null;
            }
        }
    }
}
