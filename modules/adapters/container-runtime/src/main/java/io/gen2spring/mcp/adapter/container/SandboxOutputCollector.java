package io.gen2spring.mcp.adapter.container;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.worker.SandboxArtifact;
import io.gen2spring.mcp.application.hosted.worker.SandboxResult;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class SandboxOutputCollector {
    private static final long MAX_ARCHIVE_BYTES = 100L * 1024 * 1024;
    private static final long MAX_JSON_BYTES = 1024L * 1024;
    private static final Set<String> OUTPUTS = Set.of(
            "archive.zip", "manifest.json", "runtime-metadata.json", "validation-report.json", "result.json");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String, ArtifactDefinition> ARTIFACTS;
    private static final CanonicalRuntimeMetadataCodec RUNTIME_METADATA = new CanonicalRuntimeMetadataCodec();

    static {
        Map<String, ArtifactDefinition> values = new LinkedHashMap<>();
        values.put("archive.zip", new ArtifactDefinition("archive", "application/zip", MAX_ARCHIVE_BYTES));
        values.put("manifest.json", new ArtifactDefinition("manifest", "application/json", MAX_JSON_BYTES));
        values.put("validation-report.json", new ArtifactDefinition(
                "validation-report", "application/json", MAX_JSON_BYTES));
        ARTIFACTS = Collections.unmodifiableMap(values);
    }

    private final Path workspaceRoot;

    SandboxOutputCollector(Path workspaceRoot) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    }

    SandboxResult collect(Path workspace, Path output, int processExitCode, boolean outOfMemory) {
        requireOwnedWorkspace(workspace);
        try {
            if (Files.isSymbolicLink(output) || !Files.isDirectory(output, NOFOLLOW_LINKS)) {
                throw failed();
            }
            List<Path> entries;
            try (var paths = Files.list(output)) {
                entries = paths
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(OUTPUTS.size() + 1L)
                        .toList();
            }
            if (entries.isEmpty()) {
                throw failed();
            }
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!OUTPUTS.contains(name)
                        || Files.isSymbolicLink(entry)
                        || !Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
                    throw failed();
                }
            }
            Path metadata = output.resolve("result.json");
            ResultMetadata result = parseResult(metadata);
            if (result.exitCode() != processExitCode || ("SUCCESS".equals(result.outcome()) && outOfMemory)) {
                throw failed();
            }
            if (!"SUCCESS".equals(result.outcome())) {
                deleteWorkspace(workspace);
                return new SandboxResult(List.of(), result.outcome());
            }
            if (processExitCode != 0 || entries.size() != OUTPUTS.size()) {
                throw failed();
            }
            return success(workspace, output);
        } catch (Error fatal) {
            throw fatal;
        } catch (SandboxRuntimeFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failed();
        }
    }

    void deleteWorkspace(Path workspace) {
        if (workspace == null) {
            return;
        }
        Path normalized = workspace.toAbsolutePath().normalize();
        if (normalized.equals(workspaceRoot) || !normalized.startsWith(workspaceRoot)) {
            return;
        }
        try {
            if (!Files.exists(normalized, NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalized)) {
                return;
            }
            try (var paths = Files.walk(normalized)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ignored) {
            // A later retention sweep can retry workspace cleanup.
        }
    }

    private SandboxResult success(Path workspace, Path output) throws Exception {
        WorkspaceRelease release = new WorkspaceRelease(workspace, ARTIFACTS.size());
        List<SandboxArtifact> artifacts = new ArrayList<>();
        try {
            RuntimeMetadataArtifact runtimeMetadata = readRuntimeMetadata(output.resolve("runtime-metadata.json"));
            for (Map.Entry<String, ArtifactDefinition> entry : ARTIFACTS.entrySet()) {
                Path path = output.resolve(entry.getKey());
                ArtifactDefinition definition = entry.getValue();
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()
                        || Files.isSymbolicLink(path)
                        || attributes.size() < 1
                        || attributes.size() > definition.maxBytes()) {
                    throw failed();
                }
                String sha256 = sha256(path, definition.maxBytes());
                InputStream content = new ReleaseInputStream(Files.newInputStream(path, READ, NOFOLLOW_LINKS), release);
                artifacts.add(SandboxArtifact.of(
                        definition.name(), content, attributes.size(), sha256, definition.contentType()));
            }
            return new SandboxResult(artifacts, Optional.of(runtimeMetadata), "SUCCESS");
        } catch (Error fatal) {
            artifacts.forEach(SandboxArtifact::close);
            release.force();
            throw fatal;
        } catch (Exception failure) {
            artifacts.forEach(SandboxArtifact::close);
            release.force();
            throw failure;
        }
    }

    private RuntimeMetadataArtifact readRuntimeMetadata(Path path) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || Files.isSymbolicLink(path)
                || attributes.size() < 1
                || attributes.size() > CanonicalRuntimeMetadataCodec.MAX_BYTES) {
            throw failed();
        }
        byte[] content;
        try (InputStream input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
            content = input.readNBytes(CanonicalRuntimeMetadataCodec.MAX_BYTES + 1);
        }
        if (content.length != attributes.size() || content.length > CanonicalRuntimeMetadataCodec.MAX_BYTES) {
            throw failed();
        }
        return RUNTIME_METADATA.decode(content);
    }

    private ResultMetadata parseResult(Path path) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > 65_536) {
            throw failed();
        }
        JsonNode root;
        try (InputStream input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
            root = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
        }
        if (root == null
                || !root.isObject()
                || root.size() != 2
                || !root.path("outcome").isTextual()
                || !root.path("exitCode").isIntegralNumber()
                || !root.path("exitCode").canConvertToInt()) {
            throw failed();
        }
        String outcome = root.path("outcome").textValue();
        int exitCode = root.path("exitCode").intValue();
        if (!Set.of("SUCCESS", "FAILED", "TIMED_OUT").contains(outcome) || exitCode < 0 || exitCode > 255) {
            throw failed();
        }
        return new ResultMetadata(outcome, exitCode);
    }

    private String sha256(Path path, long maximum) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    return HexFormat.of().formatHex(digest.digest());
                }
                total += read;
                if (total > maximum) {
                    throw failed();
                }
                digest.update(buffer, 0, read);
            }
        }
    }

    private void requireOwnedWorkspace(Path workspace) {
        Path normalized = workspace == null ? null : workspace.toAbsolutePath().normalize();
        if (workspace == null
                || !workspace.isAbsolute()
                || !normalized.startsWith(workspaceRoot)
                || normalized.equals(workspaceRoot)
                || Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, NOFOLLOW_LINKS)) {
            throw failed();
        }
    }

    private static SandboxRuntimeFailure failed() {
        return new SandboxRuntimeFailure();
    }

    private record ArtifactDefinition(String name, String contentType, long maxBytes) {}

    private record ResultMetadata(String outcome, int exitCode) {}

    private final class WorkspaceRelease {
        private final Path workspace;
        private final AtomicInteger remaining;
        private final AtomicBoolean released = new AtomicBoolean();

        private WorkspaceRelease(Path workspace, int count) {
            this.workspace = workspace;
            this.remaining = new AtomicInteger(count);
        }

        private void release() {
            if (remaining.decrementAndGet() == 0) {
                force();
            }
        }

        private void force() {
            if (released.compareAndSet(false, true)) {
                deleteWorkspace(workspace);
            }
        }
    }

    private static final class ReleaseInputStream extends FilterInputStream {
        private final WorkspaceRelease release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ReleaseInputStream(InputStream input, WorkspaceRelease release) {
            super(input);
            this.release = release;
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                try {
                    super.close();
                } finally {
                    release.release();
                }
            }
        }
    }
}
