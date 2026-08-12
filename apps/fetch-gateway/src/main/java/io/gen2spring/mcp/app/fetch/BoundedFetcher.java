package io.gen2spring.mcp.app.fetch;

import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

public final class BoundedFetcher {
    private static final int MAX_HEADER_BYTES = 16_384;
    private static final int MAX_HEADER_COUNT = 64;

    private final FetchTransport transport;
    private final int maxWireBytes;
    private final int maxDecodedBytes;
    private final int maxRedirects;
    private final Duration totalTimeout;

    BoundedFetcher(
            FetchTransport transport,
            int maxWireBytes,
            int maxDecodedBytes,
            int maxRedirects,
            Duration totalTimeout) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.totalTimeout = Objects.requireNonNull(totalTimeout, "totalTimeout");
        if (maxWireBytes < 1
                || maxDecodedBytes < 1
                || maxRedirects < 0
                || maxRedirects > 3
                || totalTimeout.isZero()
                || totalTimeout.isNegative()
                || totalTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Fetch limits are invalid");
        }
        this.maxWireBytes = maxWireBytes;
        this.maxDecodedBytes = maxDecodedBytes;
        this.maxRedirects = maxRedirects;
    }

    public FetchResult fetch(ImportTarget initialTarget) {
        if (initialTarget == null) {
            throw failed();
        }
        long deadline = System.nanoTime() + totalTimeout.toNanos();
        ImportTarget target = initialTarget;
        int redirects = 0;
        try {
            while (true) {
                Duration remaining = remaining(deadline);
                try (FetchTransport.Response response = transport.execute(target, remaining)) {
                    requireBoundedHeaders(response.headers());
                    if (redirectStatus(response.status())) {
                        if (redirects >= maxRedirects) {
                            throw failed();
                        }
                        String location = singleHeader(response.headers(), "location");
                        if (location == null || location.length() > 4096) {
                            throw failed();
                        }
                        URI redirected = target.uri().resolve(location);
                        target = ImportTarget.parse(redirected.toString());
                        redirects++;
                        continue;
                    }
                    if (response.status() < 200 || response.status() >= 300) {
                        throw failed();
                    }
                    byte[] wire = readBounded(response.body(), maxWireBytes);
                    byte[] decoded = decode(wire, singleHeader(response.headers(), "content-encoding"));
                    String contentType = singleHeader(response.headers(), "content-type");
                    if (contentType == null || contentType.isBlank() || contentType.length() > 128) {
                        throw failed();
                    }
                    return new FetchResult(response.status(), mediaType(contentType), decoded);
                }
            }
        } catch (FetchFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failed();
        }
    }

    private byte[] decode(byte[] wire, String contentEncoding) throws IOException {
        if (contentEncoding == null || contentEncoding.isBlank() || "identity".equalsIgnoreCase(contentEncoding)) {
            if (wire.length > maxDecodedBytes) {
                throw failed();
            }
            return wire;
        }
        if (!"gzip".equalsIgnoreCase(contentEncoding.trim())) {
            throw failed();
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(wire))) {
            return readBounded(gzip, maxDecodedBytes);
        }
    }

    private byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read == -1) {
                return output.toByteArray();
            }
            total += read;
            if (total > limit) {
                throw failed();
            }
            output.write(buffer, 0, read);
        }
    }

    private void requireBoundedHeaders(Map<String, List<String>> headers) {
        if (headers.size() > MAX_HEADER_COUNT) {
            throw failed();
        }
        int total = 0;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            total += entry.getKey().length();
            if (entry.getValue().size() > 8) {
                throw failed();
            }
            for (String value : entry.getValue()) {
                total += value.length();
                if (value.chars().anyMatch(character -> character == '\r' || character == '\n')) {
                    throw failed();
                }
            }
            if (total > MAX_HEADER_BYTES) {
                throw failed();
            }
        }
    }

    private String singleHeader(Map<String, List<String>> headers, String name) {
        List<String> matches = headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private String mediaType(String contentType) {
        String value = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")) {
            throw failed();
        }
        return value;
    }

    private Duration remaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw failed();
        }
        return Duration.ofNanos(remaining);
    }

    private boolean redirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private FetchFailure failed() {
        return new FetchFailure();
    }

    public record FetchResult(int status, String mediaType, byte[] body) {
        public FetchResult {
            Objects.requireNonNull(mediaType, "mediaType");
            body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
        }

        @Override
        public byte[] body() {
            return Arrays.copyOf(body, body.length);
        }
    }
}
