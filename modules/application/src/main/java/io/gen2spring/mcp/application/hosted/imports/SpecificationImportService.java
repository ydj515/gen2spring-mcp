package io.gen2spring.mcp.application.hosted.imports;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public final class SpecificationImportService {
    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final UrlFetchClient fetchClient;
    private final SpecificationAnalyzer analyzer;
    private final ObjectStorage storage;
    private final SpecificationCatalog catalog;
    private final Path workRoot;
    private final int maxBytes;
    private final Clock clock;

    public SpecificationImportService(
            UrlFetchClient fetchClient,
            SpecificationAnalyzer analyzer,
            ObjectStorage storage,
            SpecificationCatalog catalog,
            Path workRoot,
            int maxBytes,
            Clock clock) {
        this.fetchClient = Objects.requireNonNull(fetchClient, "fetchClient");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.workRoot = requireWorkRoot(workRoot);
        if (maxBytes < 1 || maxBytes > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("Hosted import configuration is invalid");
        }
        this.maxBytes = maxBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SpecificationId importSpecification(
            AccountId owner,
            SpecificationId specificationId,
            ImportTarget target) {
        if (owner == null || specificationId == null || target == null) {
            throw failed();
        }
        byte[] source = null;
        Path temporary = null;
        ObjectKey key = null;
        try {
            if (catalog.belongsTo(owner, specificationId)) {
                return specificationId;
            }
            Fetched fetched = fetch(target);
            source = fetched.source();
            requireUtf8(source);
            requireMediaSyntax(source, fetched.extension());
            temporary = Files.createTempFile(workRoot, "specification-import-", "." + fetched.extension());
            Files.write(temporary, source, StandardOpenOption.TRUNCATE_EXISTING);
            byte[] analyzed = analyzer.analyze(temporary, maxBytes).originalSpecification();
            if (!Arrays.equals(source, analyzed)) {
                throw failed();
            }
            String sha256 = sha256(source);
            key = sourceKey(specificationId, sha256);
            StoredObject stored;
            try {
                stored = storage.put(
                        key,
                        new java.io.ByteArrayInputStream(source),
                        source.length,
                        sha256,
                        fetched.mediaType());
            } catch (RuntimeException failure) {
                deleteQuietly(key);
                throw failure;
            }
            if (!stored.key().equals(key)
                    || stored.size() != source.length
                    || !stored.sha256().equals(sha256)
                    || !stored.contentType().equals(fetched.mediaType())) {
                deleteQuietly(key);
                throw failed();
            }
            try {
                catalog.register(new SpecificationCatalog.Registration(
                        specificationId,
                        owner,
                        key,
                        sha256,
                        source.length,
                        "URL",
                        "READY",
                        clock.instant()));
            } catch (RuntimeException failure) {
                if (!retainedByCatalog(owner, specificationId)) {
                    deleteQuietly(key);
                }
                throw failure;
            }
            return specificationId;
        } catch (Error fatal) {
            throw fatal;
        } catch (Exception failure) {
            throw failed();
        } finally {
            if (source != null) {
                Arrays.fill(source, (byte) 0);
            }
            deleteQuietly(temporary);
        }
    }

    private Fetched fetch(ImportTarget target) throws Exception {
        try (UrlFetchClient.FetchedSpecification fetched = fetchClient.fetch(target)) {
            if (fetched == null
                    || fetched.size() < 1
                    || fetched.size() > maxBytes) {
                throw failed();
            }
            String mediaType = canonicalMediaType(fetched.mediaType());
            byte[] source = readBounded(fetched.body());
            if (source.length != fetched.size()) {
                Arrays.fill(source, (byte) 0);
                throw failed();
            }
            return new Fetched(source, mediaType, extension(mediaType));
        }
    }

    private byte[] readBounded(InputStream body) throws Exception {
        if (body == null) {
            throw failed();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int remaining = maxBytes - total;
            int read = body.read(buffer, 0, Math.min(buffer.length, remaining + 1));
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
        if (value == null) {
            throw failed();
        }
        String mediaType = value.toLowerCase(Locale.ROOT);
        if (mediaType.equals("application/json") || mediaType.endsWith("+json")) {
            return mediaType;
        }
        if (mediaType.equals("application/yaml")
                || mediaType.equals("application/x-yaml")
                || mediaType.equals("text/yaml")
                || mediaType.equals("text/x-yaml")
                || mediaType.endsWith("+yaml")) {
            return mediaType;
        }
        throw failed();
    }

    private String extension(String mediaType) {
        return mediaType.endsWith("json") ? "json" : "yaml";
    }

    private void requireUtf8(byte[] source) throws Exception {
        StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source));
    }

    private void requireMediaSyntax(byte[] source, String extension) throws Exception {
        if ("json".equals(extension) && STRICT_JSON.readTree(source) == null) {
            throw failed();
        }
    }

    private ObjectKey sourceKey(SpecificationId specificationId, String sha256) {
        return ObjectKey.parse("specifications/" + specificationId.value() + "/" + sha256);
    }

    private String sha256(byte[] source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
    }

    private void deleteQuietly(ObjectKey key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // Private retention cleanup can retry; the fixed import failure remains authoritative.
        }
    }

    private boolean retainedByCatalog(AccountId owner, SpecificationId specificationId) {
        try {
            return catalog.belongsTo(owner, specificationId);
        } catch (RuntimeException failure) {
            return true;
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // The sandbox removes the bounded work directory after the runner exits.
        }
    }

    private Path requireWorkRoot(Path root) {
        if (root == null || !root.isAbsolute() || !Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Hosted import configuration is invalid");
        }
        return root;
    }

    private SpecificationImportFailure failed() {
        return new SpecificationImportFailure();
    }

    private record Fetched(byte[] source, String mediaType, String extension) {}
}
