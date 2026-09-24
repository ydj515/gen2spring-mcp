package io.gen2spring.mcp.app.web.infrastructure.hosted.submission;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.app.web.application.hosted.port.in.HostedSubmissionUseCase;
import io.gen2spring.mcp.application.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.application.hosted.imports.port.out.ImportTargetProtector;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.job.HostedJobFailure;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.query.port.out.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.specification.port.out.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer.AnalysisResult;
import io.gen2spring.mcp.application.usecase.GenerationPreview;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class HostedSubmissionService implements HostedSubmissionUseCase {
    private static final int MAX_SPECIFICATION_BYTES = 10 * 1024 * 1024;
    private static final Pattern SAFE_UPLOAD_NAME = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?:yaml|yml|json)");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final GeneratorRuntime generator;
    private final ObjectStorage storage;
    private final SpecificationCatalog catalog;
    private final HostedResourceStore resources;
    private final HostedJobService jobs;
    private final ImportTargetProtector protector;
    private final Path workRoot;
    private final Clock clock;

    public HostedSubmissionService(
            GeneratorRuntime generator,
            ObjectStorage storage,
            SpecificationCatalog catalog,
            HostedResourceStore resources,
            HostedJobService jobs,
            ImportTargetProtector protector,
            Path workRoot,
            Clock clock) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.protector = Objects.requireNonNull(protector, "protector");
        this.workRoot = requireDirectory(workRoot);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public HostedSpecificationAnalysis upload(
            AccountId owner,
            InputStream input,
            String mediaType,
            String specificationName) {
        byte[] source = null;
        Path temporary = null;
        ObjectKey key = null;
        try {
            Objects.requireNonNull(owner, "owner");
            String canonicalType = mediaType(mediaType);
            String displayLabel = displayLabel(specificationName);
            source = read(input, MAX_SPECIFICATION_BYTES);
            temporary = privateTemporary(canonicalType);
            Files.write(temporary, source);
            AnalysisResult analysis = generator.analyzer().analyze(temporary, MAX_SPECIFICATION_BYTES);
            if (!Arrays.equals(source, analysis.originalSpecification())) throw failure();
            SpecificationId id = new SpecificationId(UUID.randomUUID());
            String sha = sha256(source);
            key = ObjectKey.parse("specifications/" + id.value() + "/" + sha);
            StoredObject stored = storage.put(key, new ByteArrayInputStream(source), source.length, sha, canonicalType);
            if (!stored.key().equals(key) || stored.size() != source.length
                    || !stored.sha256().equals(sha) || !stored.contentType().equals(canonicalType)) throw failure();
            HostedSpecificationAnalysis result = new HostedSpecificationAnalysis(
                    id, displayLabel, source.length, SpecificationAnalysisView.from(analysis.document()));
            try {
                catalog.register(new SpecificationCatalog.Registration(
                        id, owner, key, sha, source.length, "UPLOAD", displayLabel, "READY", clock.instant()));
            } catch (RuntimeException failure) {
                if (!retainedByCatalog(owner, id)) delete(key);
                key = null;
                throw failure;
            }
            key = null;
            return result;
        } catch (Error fatal) {
            throw fatal;
        } catch (Exception failure) {
            if (key != null) delete(key);
            throw failure(failure);
        } finally {
            if (source != null) Arrays.fill(source, (byte) 0);
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
        }
    }

    public HostedSpecificationAnalysis analysis(AccountId owner, SpecificationId specificationId) {
        try {
            HostedResourceStore.SpecificationView specification = owned(owner, specificationId);
            return withPinnedSpecification(specification, (temporary, source) -> {
                AnalysisResult analysis = generator.analyzer().analyze(temporary, MAX_SPECIFICATION_BYTES);
                if (!Arrays.equals(source, analysis.originalSpecification())) throw failure();
                return new HostedSpecificationAnalysis(
                        specification.id(), specification.label(), specification.byteSize(),
                        SpecificationAnalysisView.from(analysis.document()));
            });
        } catch (Error fatal) {
            throw fatal;
        } catch (HostedSpecificationNotFound missing) {
            throw missing;
        } catch (RuntimeException failure) {
            throw failure(failure);
        }
    }

    public GenerationPreview preview(
            AccountId owner,
            SpecificationId specificationId,
            byte[] configurationBytes) {
        try {
            HostedResourceStore.SpecificationView specification = owned(owner, specificationId);
            var configuration = generator.configurationParser().parseJson(configurationBytes);
            return withPinnedSpecification(
                    specification,
                    (temporary, source) -> generator.pipeline().preview(temporary, configuration));
        } catch (Error fatal) {
            throw fatal;
        } catch (HostedSpecificationNotFound missing) {
            throw missing;
        } catch (RuntimeException failure) {
            throw failure(failure);
        }
    }

    public CreateJobResult importUrl(AccountId owner, String idempotencyKey, String url) {
        try {
            var target = ImportTarget.parse(url);
            var encrypted = protector.protect(target);
            String snapshot = JSON.writeValueAsString(encrypted);
            String requestHash = sha256(
                    target.uri().toASCIIString().getBytes(StandardCharsets.UTF_8));
            return jobs.submitImport(owner, idempotencyKey, requestHash, snapshot);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            if (failure instanceof HostedJobFailure) {
                throw failure;
            }
            throw failure(failure);
        } catch (Exception failure) {
            throw failure(failure);
        }
    }

    public CreateJobResult generate(
            AccountId owner,
            SpecificationId specificationId,
            Optional<UUID> predecessorCatalogId,
            String idempotencyKey,
            byte[] configurationBytes) {
        try {
            Objects.requireNonNull(predecessorCatalogId, "predecessorCatalogId");
            var specification = resources.specification(owner, specificationId).orElseThrow(HostedSubmissionFailure::new);
            generator.configurationParser().parseJson(configurationBytes);
            JsonNode configuration = JSON.readTree(configurationBytes);
            if (configuration == null || !configuration.isObject()) throw failure();
            var root = JSON.createObjectNode();
            root.put("specificationObjectKey", specification.objectKey().value());
            predecessorCatalogId.ifPresent(value -> root.put("predecessorCatalogId", value.toString()));
            root.set("configuration", configuration);
            String snapshot = JSON.writeValueAsString(root);
            return jobs.submitGeneration(
                    owner, specificationId, predecessorCatalogId, idempotencyKey,
                    sha256(snapshot.getBytes(StandardCharsets.UTF_8)), snapshot);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure(failure);
        }
    }

    public CreateJobResult generate(
            AccountId owner,
            SpecificationId specificationId,
            String idempotencyKey,
            byte[] configurationBytes) {
        return generate(owner, specificationId, Optional.empty(), idempotencyKey, configurationBytes);
    }

    private byte[] read(InputStream input, int maximum) throws Exception {
        if (input == null) throw failure();
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > maximum - total) throw failure();
            output.write(buffer, 0, count);
            total += count;
        }
        if (total < 1) throw failure();
        return output.toByteArray();
    }

    private String mediaType(String value) {
        if (value == null) throw failure();
        String canonical = value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (canonical.equals("application/json") || canonical.endsWith("+json")
                || Set.of("application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml").contains(canonical)
                || canonical.endsWith("+yaml")) return canonical;
        throw failure();
    }

    private String displayLabel(String value) {
        if (value == null
                || !SAFE_UPLOAD_NAME.matcher(value).matches()
                || value.contains("..")) {
            throw failure();
        }
        return value;
    }

    private HostedResourceStore.SpecificationView owned(AccountId owner, SpecificationId specificationId) {
        if (owner == null || specificationId == null) throw new HostedSpecificationNotFound();
        return resources.specification(owner, specificationId).orElseThrow(HostedSpecificationNotFound::new);
    }

    private <T> T withPinnedSpecification(
            HostedResourceStore.SpecificationView specification,
            PinnedSpecificationAction<T> action) {
        byte[] source = null;
        Path temporary = null;
        try (StoredObjectContent content = storage.get(specification.objectKey())) {
            if (content == null
                    || content.size() != specification.byteSize()
                    || !Objects.equals(content.sha256(), specification.sha256())
                    || !Objects.equals(mediaType(content.contentType()), content.contentType())) {
                throw failure();
            }
            source = read(content.body(), MAX_SPECIFICATION_BYTES);
            if (source.length != specification.byteSize()
                    || !sha256(source).equals(specification.sha256())) {
                throw failure();
            }
            temporary = privateTemporary(content.contentType());
            Files.write(temporary, source);
            return action.run(temporary, source);
        } catch (Error fatal) {
            throw fatal;
        } catch (HostedSpecificationNotFound missing) {
            throw missing;
        } catch (Exception failure) {
            throw failure(failure);
        } finally {
            if (source != null) Arrays.fill(source, (byte) 0);
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
        }
    }

    private Path privateTemporary(String contentType) throws Exception {
        Path temporary = Files.createTempFile(
                workRoot,
                "hosted-specification-",
                contentType.contains("json") ? ".json" : ".yaml");
        try {
            Files.setPosixFilePermissions(
                    temporary,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // The temp file API already creates an owner-private file on non-POSIX platforms.
        }
        return temporary;
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void delete(ObjectKey key) {
        try { storage.delete(key); } catch (RuntimeException ignored) {}
    }

    private boolean retainedByCatalog(AccountId owner, SpecificationId specificationId) {
        try {
            return catalog.belongsTo(owner, specificationId);
        } catch (RuntimeException failure) {
            return true;
        }
    }

    private Path requireDirectory(Path path) {
        if (path == null || !path.isAbsolute() || Files.isSymbolicLink(path) || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("Hosted Web configuration is invalid");
        }
        return path.toAbsolutePath().normalize();
    }

    private HostedSubmissionFailure failure() {
        return new HostedSubmissionFailure();
    }

    private HostedSubmissionFailure failure(Throwable cause) {
        return new HostedSubmissionFailure(cause);
    }

    @FunctionalInterface
    private interface PinnedSpecificationAction<T> {
        T run(Path temporary, byte[] source) throws Exception;
    }
}
