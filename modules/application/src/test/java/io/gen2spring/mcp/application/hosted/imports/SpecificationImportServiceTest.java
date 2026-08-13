package io.gen2spring.mcp.application.hosted.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecificationImportServiceTest {
    private static final AccountId OWNER = AccountId.parse("41dd3b69-589c-4466-a78e-d448407d17b9");
    private static final SpecificationId SPECIFICATION = SpecificationId.parse(
            "80782e7c-337d-4d4d-bd4d-ad478359563c");
    private static final ImportTarget TARGET = ImportTarget.parse(
            "https://private-marker.example.com/openapi.yaml?token=private");
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @TempDir
    private Path temporaryDirectory;

    @Test
    void validatesStoresAndRegistersOneDeterministicPrivateSpecification() throws Exception {
        byte[] source = "openapi: 3.0.3\ninfo: {}\npaths: {}\n".getBytes();
        StubFetchClient fetch = new StubFetchClient(source, "application/yaml");
        StubStorage storage = new StubStorage();
        StubCatalog catalog = new StubCatalog();
        SpecificationImportService service = service(fetch, storage, catalog, analyzer());

        SpecificationId imported = service.importSpecification(OWNER, SPECIFICATION, TARGET);

        assertEquals(SPECIFICATION, imported);
        assertEquals(ObjectKey.parse(
                "specifications/80782e7c-337d-4d4d-bd4d-ad478359563c/" + sha256(source)), storage.key);
        assertEquals(source.length, storage.size);
        assertEquals("application/yaml", storage.contentType);
        assertTrue(catalog.belongsTo(OWNER, SPECIFICATION));
        assertEquals("URL", catalog.registration.sourceType());
        assertEquals("READY", catalog.registration.parseState());
        assertEquals(NOW, catalog.registration.observedAt());
        try (var entries = Files.list(temporaryDirectory)) {
            assertFalse(entries.findAny().isPresent());
        }
    }

    @Test
    void returnsTheExistingSpecificationWithoutRefetching() {
        StubFetchClient fetch = new StubFetchClient(validSource(), "application/yaml");
        StubStorage storage = new StubStorage();
        StubCatalog catalog = new StubCatalog();
        catalog.existing = true;

        SpecificationId imported = service(fetch, storage, catalog, analyzer())
                .importSpecification(OWNER, SPECIFICATION, TARGET);

        assertEquals(SPECIFICATION, imported);
        assertEquals(0, fetch.calls);
        assertNull(storage.key);
    }

    @Test
    void cleansPartialStorageAndReturnsOneFixedFailure() {
        StubFetchClient fetch = new StubFetchClient(validSource(), "application/yaml");
        StubStorage storage = new StubStorage();
        storage.failPut = true;
        StubCatalog catalog = new StubCatalog();

        SpecificationImportFailure failure = assertThrows(
                SpecificationImportFailure.class,
                () -> service(fetch, storage, catalog, analyzer())
                        .importSpecification(OWNER, SPECIFICATION, TARGET));

        assertEquals("Hosted specification import failed", failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(storage.deleted);
        assertNull(catalog.registration);
        assertFalse(failure.toString().contains("private-marker"));
        assertFalse(failure.toString().contains("openapi"));
    }

    @Test
    void removesThePrivateObjectWhenCatalogPublicationFails() {
        StubFetchClient fetch = new StubFetchClient(validSource(), "application/yaml");
        StubStorage storage = new StubStorage();
        StubCatalog catalog = new StubCatalog();
        catalog.failRegistration = true;

        assertThrows(
                SpecificationImportFailure.class,
                () -> service(fetch, storage, catalog, analyzer())
                        .importSpecification(OWNER, SPECIFICATION, TARGET));

        assertTrue(storage.deleted);
    }

    @Test
    void rejectsInvalidOpenApiAndMismatchedFetchMetadataBeforeStorage() {
        StubStorage storage = new StubStorage();
        SpecificationAnalyzer rejecting = (path, maxBytes) -> {
            throw new IllegalArgumentException("private response marker");
        };

        assertThrows(
                SpecificationImportFailure.class,
                () -> service(new StubFetchClient(validSource(), "application/yaml"), storage,
                                new StubCatalog(), rejecting)
                        .importSpecification(OWNER, SPECIFICATION, TARGET));
        assertNull(storage.key);

        StubFetchClient mismatch = new StubFetchClient(validSource(), "application/yaml");
        mismatch.announcedSize = mismatch.source.length + 1;
        assertThrows(
                SpecificationImportFailure.class,
                () -> service(mismatch, storage, new StubCatalog(), analyzer())
                        .importSpecification(OWNER, SPECIFICATION, TARGET));
        assertNull(storage.key);

        assertThrows(
                SpecificationImportFailure.class,
                () -> service(new StubFetchClient(validSource(), "application/json"), storage,
                                new StubCatalog(), analyzer())
                        .importSpecification(OWNER, SPECIFICATION, TARGET));
        assertNull(storage.key);
    }

    private SpecificationImportService service(
            UrlFetchClient fetch,
            ObjectStorage storage,
            SpecificationCatalog catalog,
            SpecificationAnalyzer analyzer) {
        return new SpecificationImportService(
                fetch,
                analyzer,
                storage,
                catalog,
                temporaryDirectory,
                10 * 1024 * 1024,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SpecificationAnalyzer analyzer() {
        return (path, maxBytes) -> {
            try {
                return new SpecificationAnalyzer.AnalysisResult(null, Files.readAllBytes(path));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
    }

    private byte[] validSource() {
        return "openapi: 3.0.3\ninfo: {}\npaths: {}\n".getBytes();
    }

    private static final class StubFetchClient implements UrlFetchClient {
        private final byte[] source;
        private final String mediaType;
        private long announcedSize;
        private int calls;

        private StubFetchClient(byte[] source, String mediaType) {
            this.source = Arrays.copyOf(source, source.length);
            this.mediaType = mediaType;
            this.announcedSize = source.length;
        }

        @Override
        public FetchedSpecification fetch(ImportTarget target) {
            calls++;
            return new FetchedSpecification() {
                @Override
                public InputStream body() {
                    return new ByteArrayInputStream(source);
                }

                @Override
                public long size() {
                    return announcedSize;
                }

                @Override
                public String mediaType() {
                    return mediaType;
                }

                @Override
                public void close() {}
            };
        }
    }

    private static final class StubStorage implements ObjectStorage {
        private ObjectKey key;
        private long size;
        private String contentType;
        private boolean failPut;
        private boolean deleted;

        @Override
        public StoredObject put(
                ObjectKey key,
                InputStream body,
                long size,
                String sha256,
                String contentType) {
            this.key = key;
            this.size = size;
            this.contentType = contentType;
            if (failPut) {
                throw new IllegalStateException("private storage marker");
            }
            return new StoredObject(key, size, sha256, contentType);
        }

        @Override
        public StoredObjectContent get(ObjectKey key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(ObjectKey key) {
            deleted = true;
        }
    }

    private static final class StubCatalog implements SpecificationCatalog {
        private boolean existing;
        private boolean failRegistration;
        private Registration registration;

        @Override
        public boolean belongsTo(AccountId owner, SpecificationId specificationId) {
            return existing || registration != null
                    && registration.owner().equals(owner)
                    && registration.id().equals(specificationId);
        }

        @Override
        public RegistrationResult register(Registration registration) {
            if (failRegistration) {
                throw new IllegalStateException("private catalog marker");
            }
            this.registration = registration;
            return RegistrationResult.CREATED;
        }
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    }
}
