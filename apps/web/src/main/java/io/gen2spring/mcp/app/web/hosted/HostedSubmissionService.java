package io.gen2spring.mcp.app.web.hosted;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.imports.ImportTargetProtector;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.query.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.application.hosted.storage.StoredObject;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class HostedSubmissionService {
    private static final int MAX_SPECIFICATION_BYTES = 10 * 1024 * 1024;
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

    public SpecificationId upload(AccountId owner, InputStream input, String mediaType) {
        byte[] source = null;
        Path temporary = null;
        ObjectKey key = null;
        try {
            Objects.requireNonNull(owner, "owner");
            String canonicalType = mediaType(mediaType);
            source = read(input, MAX_SPECIFICATION_BYTES);
            temporary = Files.createTempFile(workRoot, "hosted-upload-", canonicalType.contains("json") ? ".json" : ".yaml");
            Files.write(temporary, source);
            byte[] analyzed = generator.analyzer().analyze(temporary, MAX_SPECIFICATION_BYTES).originalSpecification();
            if (!Arrays.equals(source, analyzed)) throw failure();
            SpecificationId id = new SpecificationId(UUID.randomUUID());
            String sha = sha256(source);
            key = ObjectKey.parse("specifications/" + id.value() + "/" + sha);
            StoredObject stored = storage.put(key, new ByteArrayInputStream(source), source.length, sha, canonicalType);
            if (!stored.key().equals(key) || stored.size() != source.length
                    || !stored.sha256().equals(sha) || !stored.contentType().equals(canonicalType)) throw failure();
            catalog.register(new SpecificationCatalog.Registration(
                    id, owner, key, sha, source.length, "UPLOAD", "READY", clock.instant()));
            return id;
        } catch (Error fatal) {
            throw fatal;
        } catch (Exception failure) {
            if (key != null) delete(key);
            throw failure();
        } finally {
            if (source != null) Arrays.fill(source, (byte) 0);
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {}
        }
    }

    public CreateJobResult importUrl(AccountId owner, String idempotencyKey, String url) {
        try {
            var target = ImportTarget.parse(url);
            var encrypted = protector.protect(target);
            String snapshot = JSON.writeValueAsString(encrypted);
            String requestHash = sha256(
                    target.uri().toASCIIString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return jobs.submitImport(owner, idempotencyKey, requestHash, snapshot);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            if (failure instanceof io.gen2spring.mcp.application.hosted.job.HostedJobFailure) {
                throw failure;
            }
            throw failure();
        } catch (Exception failure) {
            throw failure();
        }
    }

    public CreateJobResult generate(
            AccountId owner,
            SpecificationId specificationId,
            String idempotencyKey,
            byte[] configurationBytes) {
        try {
            var specification = resources.specification(owner, specificationId).orElseThrow(HostedSubmissionFailure::new);
            generator.configurationParser().parseJson(configurationBytes);
            JsonNode configuration = JSON.readTree(configurationBytes);
            if (configuration == null || !configuration.isObject()) throw failure();
            var root = JSON.createObjectNode();
            root.put("specificationObjectKey", specification.objectKey().value());
            root.set("configuration", configuration);
            String snapshot = JSON.writeValueAsString(root);
            return jobs.submitGeneration(
                    owner, specificationId, idempotencyKey,
                    sha256(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)), snapshot);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure();
        }
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
        String canonical = value.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
        if (canonical.equals("application/json") || canonical.endsWith("+json")
                || java.util.Set.of("application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml").contains(canonical)
                || canonical.endsWith("+yaml")) return canonical;
        throw failure();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void delete(ObjectKey key) {
        try { storage.delete(key); } catch (RuntimeException ignored) {}
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

    public static final class HostedSubmissionFailure extends RuntimeException {
        public HostedSubmissionFailure() {
            super("Hosted submission failed", null, false, false);
        }
    }
}
