package io.gen2spring.mcp.app.web.infrastructure.hosted.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.app.web.application.hosted.service.HostedSubmissionService;
import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.imports.port.out.ImportTargetProtector;
import io.gen2spring.mcp.application.hosted.job.CreateJob;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.JobView;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.job.port.out.JobQueue;
import io.gen2spring.mcp.application.hosted.query.port.out.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.specification.port.out.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import io.gen2spring.mcp.application.generation.usecase.GenerationPipeline;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class HostedSubmissionServiceTest {
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));

    @Test
    void uploadReturnsCanonicalAnalysisAndPersistsTheBoundedBasename(@TempDir Path workRoot) throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        SpecificationCatalog catalog = mock(SpecificationCatalog.class);
        HostedSubmissionService service = service(
                GeneratorRuntime.defaults(), storage, catalog, mock(HostedResourceStore.class), workRoot);

        var uploaded = service.upload(
                OWNER,
                new ByteArrayInputStream(specification()),
                "application/yaml",
                "swagger-3.1.yml");

        assertEquals("swagger-3.1.yml", uploaded.displayLabel());
        assertEquals(1, uploaded.analysis().counts().total());
        assertEquals("getForecast", uploaded.analysis().operations().getFirst().operationId());
        var registration = ArgumentCaptor.forClass(SpecificationCatalog.Registration.class);
        verify(catalog).register(registration.capture());
        assertEquals(uploaded.id(), registration.getValue().id());
        assertEquals("swagger-3.1.yml", registration.getValue().displayLabel());
        assertDirectoryEmpty(workRoot);
    }

    @Test
    void rejectsUploadNamesOutsideTheLocalBasenameContractBeforeStorageAccess(@TempDir Path workRoot) {
        ObjectStorage storage = mock(ObjectStorage.class);
        HostedSubmissionService service = service(
                GeneratorRuntime.defaults(), storage, mock(SpecificationCatalog.class),
                mock(HostedResourceStore.class), workRoot);

        var failure = assertThrows(HostedSubmissionService.HostedSubmissionFailure.class,
                () -> service.upload(
                        OWNER,
                        new ByteArrayInputStream(specification()),
                        "application/yaml",
                        "weather.txt"));

        assertEquals("Hosted submission failed", failure.getMessage());
        verifyNoInteractions(storage);
    }

    @Test
    void retainsThePinnedObjectWhenCatalogRegistrationMayHaveCommitted(@TempDir Path workRoot) throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        SpecificationCatalog catalog = mock(SpecificationCatalog.class);
        when(catalog.register(any())).thenThrow(new IllegalStateException("database-marker"));
        when(catalog.belongsTo(eq(OWNER), any())).thenReturn(true);
        HostedSubmissionService service = service(
                GeneratorRuntime.defaults(), storage, catalog, mock(HostedResourceStore.class), workRoot);

        var failure = assertThrows(HostedSubmissionService.HostedSubmissionFailure.class,
                () -> service.upload(
                        OWNER,
                        new ByteArrayInputStream(specification()),
                        "application/yaml",
                        "weather.yml"));

        assertEquals("Hosted submission failed", failure.getMessage());
        assertTrue(storage.hasContent());
        assertDirectoryEmpty(workRoot);
    }

    @Test
    void analyzesAnOwnedPinnedObjectAndRejectsCrossOwnerBeforeStorageAccess(@TempDir Path workRoot) throws Exception {
        byte[] source = specification();
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        ObjectKey key = objectKey(id, source);
        HostedResourceStore resources = mock(HostedResourceStore.class);
        InMemoryStorage storage = new InMemoryStorage(key, source, "application/yaml");
        when(resources.specification(OWNER, id)).thenReturn(Optional.of(new HostedResourceStore.SpecificationView(
                id, "URL", key, sha256(source), source.length, "Imported OpenAPI", Instant.EPOCH)));
        HostedSubmissionService service = service(
                GeneratorRuntime.defaults(), storage, mock(SpecificationCatalog.class), resources, workRoot);

        var analysis = service.analysis(OWNER, id);

        assertEquals("Imported OpenAPI", analysis.displayLabel());
        assertEquals("3.1.1", analysis.analysis().openApiVersion());
        assertEquals(1, storage.getCount);
        assertDirectoryEmpty(workRoot);

        AccountId other = new AccountId(UUID.randomUUID());
        assertThrows(HostedSubmissionService.HostedSpecificationNotFound.class,
                () -> service.analysis(other, id));
        assertEquals(1, storage.getCount);
    }

    @Test
    void rejectsPinnedObjectMetadataMismatchWithoutLeavingTemporaryFiles(@TempDir Path workRoot) throws Exception {
        byte[] source = specification();
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        ObjectKey key = objectKey(id, source);
        HostedResourceStore resources = mock(HostedResourceStore.class);
        InMemoryStorage storage = new InMemoryStorage(key, source, "application/yaml");
        when(resources.specification(OWNER, id)).thenReturn(Optional.of(new HostedResourceStore.SpecificationView(
                id, "UPLOAD", key, "f".repeat(64), source.length, "weather.yml", Instant.EPOCH)));
        HostedSubmissionService service = service(
                GeneratorRuntime.defaults(), storage, mock(SpecificationCatalog.class), resources, workRoot);

        var failure = assertThrows(HostedSubmissionService.HostedSubmissionFailure.class,
                () -> service.analysis(OWNER, id));

        assertEquals("Hosted submission failed", failure.getMessage());
        assertDirectoryEmpty(workRoot);
    }

    @Test
    void previewsThroughTheExistingPlanningPipelineAndCleansThePrivateCopy(@TempDir Path workRoot) throws Exception {
        byte[] source = specification();
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        ObjectKey key = objectKey(id, source);
        HostedResourceStore resources = mock(HostedResourceStore.class);
        when(resources.specification(OWNER, id)).thenReturn(Optional.of(
                specificationView(id, key, source, "weather.yml")));
        HostedSubmissionService service = service(
                GeneratorRuntime.defaults(), new InMemoryStorage(key, source, "application/yaml"),
                mock(SpecificationCatalog.class), resources, workRoot);

        var preview = service.preview(OWNER, id, configuration());

        assertEquals("spring-ai-2.0-java21-mvc-streamable", preview.profile().id());
        assertEquals("weather_get_forecast", preview.tools().getFirst().name());
        assertDirectoryEmpty(workRoot);
    }

    @Test
    void cleansThePrivatePreviewCopyWhenPlanningThrowsAFatalError(@TempDir Path workRoot) throws Exception {
        byte[] source = specification();
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        ObjectKey key = objectKey(id, source);
        HostedResourceStore resources = mock(HostedResourceStore.class);
        when(resources.specification(OWNER, id)).thenReturn(Optional.of(
                specificationView(id, key, source, "weather.yml")));
        GeneratorRuntime generator = mock(GeneratorRuntime.class);
        when(generator.configurationParser()).thenReturn(GeneratorRuntime.defaults().configurationParser());
        var pipeline = mock(GenerationPipeline.class);
        when(generator.pipeline()).thenReturn(pipeline);
        AssertionError fatal = new AssertionError("private-marker");
        doThrow(fatal).when(pipeline).preview(any(Path.class), any());
        HostedSubmissionService service = service(
                generator, new InMemoryStorage(key, source, "application/yaml"),
                mock(SpecificationCatalog.class), resources, workRoot);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> service.preview(OWNER, id, configuration()));

        assertSame(fatal, thrown);
        assertDirectoryEmpty(workRoot);
    }

    @Test
    void rejectsInvalidImportTargetsWithOneFixedNonLeakingFailure(@TempDir Path workRoot) {
        ImportTargetProtector protector = mock(ImportTargetProtector.class);
        HostedSubmissionService service = new HostedSubmissionService(
                new GeneratorHostedSpecificationProcessor(mock(GeneratorRuntime.class), workRoot),
                new JacksonHostedSubmissionSnapshotCodec(), mock(ObjectStorage.class), mock(SpecificationCatalog.class),
                mock(HostedResourceStore.class), mock(HostedJobService.class), protector, Clock.systemUTC());

        var failure = assertThrows(
                HostedSubmissionService.HostedSubmissionFailure.class,
                () -> service.importUrl(
                        new AccountId(UUID.randomUUID()), "request-1", "https://private-marker.example:8443/openapi.yaml"));

        assertEquals("Hosted submission failed", failure.getMessage());
        verifyNoInteractions(protector);
    }

    @Test
    void hashesTheCanonicalUrlInsteadOfRandomizedCiphertext(@TempDir Path workRoot) {
        ImportTargetProtector protector = mock(ImportTargetProtector.class);
        when(protector.protect(any())).thenReturn(encrypted((byte) 1), encrypted((byte) 2));
        CapturingQueue queue = new CapturingQueue();
        HostedJobService jobs = new HostedJobService(queue, (owner, specification) -> false);
        HostedSubmissionService service = new HostedSubmissionService(
                new GeneratorHostedSpecificationProcessor(mock(GeneratorRuntime.class), workRoot),
                new JacksonHostedSubmissionSnapshotCodec(), mock(ObjectStorage.class), mock(SpecificationCatalog.class),
                mock(HostedResourceStore.class), jobs, protector, Clock.systemUTC());
        AccountId owner = new AccountId(UUID.randomUUID());

        service.importUrl(owner, "request-1", "https://public.example/openapi.yaml");
        service.importUrl(owner, "request-2", "https://public.example/openapi.yaml");

        assertEquals(queue.commands.get(0).requestHash(), queue.commands.get(1).requestHash());
        assertNotEquals(
                queue.commands.get(0).requestSnapshot(), queue.commands.get(1).requestSnapshot());
    }

    @Test
    void includesTheExplicitPredecessorInTheCanonicalGenerationRequest(@TempDir Path workRoot) throws Exception {
        SpecificationId specificationId = new SpecificationId(UUID.randomUUID());
        UUID predecessorCatalogId = UUID.fromString("2c7fab42-1acd-4f90-bd3f-f7de5ec81edb");
        HostedResourceStore resources = mock(HostedResourceStore.class);
        byte[] source = specification();
        var view = specificationView(
                specificationId, objectKey(specificationId, source), source, "weather.yml");
        when(resources.specification(OWNER, specificationId)).thenReturn(Optional.of(view));
        CapturingQueue queue = new CapturingQueue();
        HostedJobService jobs = new HostedJobService(queue, (owner, specification) -> true);
        HostedSubmissionService service = new HostedSubmissionService(
                new GeneratorHostedSpecificationProcessor(GeneratorRuntime.defaults(), workRoot),
                new JacksonHostedSubmissionSnapshotCodec(), mock(ObjectStorage.class), mock(SpecificationCatalog.class),
                resources, jobs, mock(ImportTargetProtector.class), Clock.systemUTC());

        service.generate(
                OWNER, specificationId, Optional.empty(), "root-key", configuration());
        service.generate(
                OWNER, specificationId, Optional.of(predecessorCatalogId), "child-key", configuration());

        assertEquals(Optional.empty(), queue.commands.get(0).predecessorCatalogId());
        assertEquals(Optional.of(predecessorCatalogId), queue.commands.get(1).predecessorCatalogId());
        assertNotEquals(
                queue.commands.get(0).requestHash(), queue.commands.get(1).requestHash());
        assertFalse(queue.commands.get(0).requestSnapshot().contains("predecessorCatalogId"));
        assertTrue(queue.commands.get(1).requestSnapshot().contains(predecessorCatalogId.toString()));
    }

    private HostedSubmissionService service(
            GeneratorRuntime generator,
            ObjectStorage storage,
            SpecificationCatalog catalog,
            HostedResourceStore resources,
            Path workRoot) {
        return new HostedSubmissionService(
                new GeneratorHostedSpecificationProcessor(generator, workRoot),
                new JacksonHostedSubmissionSnapshotCodec(), storage, catalog, resources, mock(HostedJobService.class),
                mock(ImportTargetProtector.class), Clock.systemUTC());
    }

    private HostedResourceStore.SpecificationView specificationView(
            SpecificationId id, ObjectKey key, byte[] source, String label) throws Exception {
        return new HostedResourceStore.SpecificationView(
                id, "UPLOAD", key, sha256(source), source.length, label, Instant.EPOCH);
    }

    private ObjectKey objectKey(SpecificationId id, byte[] source) throws Exception {
        return ObjectKey.parse("specifications/" + id.value() + "/" + sha256(source));
    }

    private String sha256(byte[] source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
    }

    private byte[] specification() {
        return """
                openapi: 3.1.1
                info: {title: Weather, version: 1.0.0}
                servers: [{url: https://weather.example.test}]
                paths:
                  /forecast:
                    get:
                      operationId: getForecast
                      summary: Get forecast
                      parameters:
                        - name: city
                          in: query
                          required: true
                          schema: {type: string}
                      responses:
                        '200': {description: Success}
                """.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] configuration() {
        return """
                {
                  "project":{"groupId":"com.example","artifactId":"weather-mcp-server",
                    "packageName":"com.example.weather"},
                  "provider":"weather","domain":"forecast",
                  "targetProfileId":"spring-ai-2.0-java21-mvc-streamable",
                  "validationLevel":"MCP_PROTOCOL",
                  "validation":{"toolCall":{"operationId":"getForecast","arguments":{"city":"Seoul"}}},
                  "operations":[{"operationId":"getForecast","enabled":true,
                    "toolName":"weather_get_forecast","toolDescription":"Get forecast","parameters":{}}]
                }
                """.getBytes(StandardCharsets.UTF_8);
    }

    private void assertDirectoryEmpty(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    private static final class InMemoryStorage implements ObjectStorage {
        private final AtomicReference<ObjectKey> key = new AtomicReference<>();
        private final AtomicReference<byte[]> content = new AtomicReference<>();
        private final AtomicReference<String> contentType = new AtomicReference<>();
        private int getCount;

        private InMemoryStorage() {}

        private InMemoryStorage(ObjectKey key, byte[] content, String contentType) {
            this.key.set(key);
            this.content.set(Arrays.copyOf(content, content.length));
            this.contentType.set(contentType);
        }

        @Override
        public StoredObject put(
                ObjectKey key, InputStream body, long size, String sha256, String contentType) {
            try {
                byte[] bytes = body.readAllBytes();
                this.key.set(key);
                this.content.set(bytes);
                this.contentType.set(contentType);
                return new StoredObject(key, size, sha256, contentType);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public StoredObjectContent get(ObjectKey requested) {
            getCount++;
            if (!requested.equals(key.get())) throw new AssertionError("unexpected object key");
            byte[] bytes = Arrays.copyOf(content.get(), content.get().length);
            String hash;
            try {
                hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return new StoredObjectContent() {
                @Override public InputStream body() { return new ByteArrayInputStream(bytes); }
                @Override public long size() { return bytes.length; }
                @Override public String sha256() { return hash; }
                @Override public String contentType() { return contentType.get(); }
                @Override public void close() {}
            };
        }

        @Override
        public void delete(ObjectKey deleted) {
            if (deleted.equals(key.get())) {
                key.set(null);
                content.set(null);
                contentType.set(null);
            }
        }

        private boolean hasContent() {
            return content.get() != null;
        }
    }

    private EncryptedImportTarget encrypted(byte value) {
        return new EncryptedImportTarget(
                1, "key-1", encoded(12, value), encoded(48, value), encoded(12, (byte) (value + 1)),
                encoded(32, (byte) (value + 2)));
    }

    private String encoded(int length, byte value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class CapturingQueue implements JobQueue {
        private final ArrayList<CreateJob> commands = new ArrayList<>();

        @Override
        public CreateJobResult create(CreateJob command) {
            commands.add(command);
            return new CreateJobResult(new JobView(
                    new JobId(UUID.randomUUID()), command.owner(), command.kind(),
                    JobStatus.QUEUED, command.specificationId(),
                    command.predecessorCatalogId(), 0, false), false);
        }

        @Override public Optional<JobView> find(AccountId owner, JobId jobId) { return Optional.empty(); }
        @Override public boolean requestCancellation(AccountId owner, JobId jobId) { return false; }
        @Override public Optional<JobLease> claim(WorkerId worker, Instant now, Duration duration) { return Optional.empty(); }
        @Override public boolean heartbeat(JobLease lease, Instant leaseUntil) { return false; }
        @Override public boolean complete(JobLease lease, JobCompletion completion) { return false; }
        @Override public int recoverExpired(Instant now, int maxAttempts) { return 0; }
    }
}
