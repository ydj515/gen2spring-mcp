package io.gen2spring.mcp.app.web.infrastructure.local.job;

import io.gen2spring.mcp.app.web.application.local.exception.LocalJobFailure;

import io.gen2spring.mcp.application.generation.usecase.GenerationOutcome;
import io.gen2spring.mcp.application.generation.validation.ValidationStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JobWorkspace implements AutoCloseable {
    private static final long MAX_JSON_BYTES = 1024L * 1024L;
    private static final long MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L;

    private final Path root;

    public JobWorkspace(Path temporaryParent) {
        try {
            Path parent = Objects.requireNonNull(temporaryParent, "temporaryParent")
                    .toAbsolutePath().normalize();
            Files.createDirectories(parent);
            root = Files.createTempDirectory(parent, "gen2spring-jobs-").toAbsolutePath().normalize();
            setPermissions(root, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (IOException exception) {
            throw new LocalJobFailure(LocalJobFailure.Kind.WORKSPACE_CREATE, exception);
        }
    }

    public Path root() {
        return root;
    }

    public Map<String, Artifact> capture(
            String jobId,
            GenerationOutcome outcome) {
        Map<String, Artifact> result = baseArtifacts(jobId, outcome.projectRoot());
        if (outcome.validationStatus() == ValidationStatus.VALIDATED && outcome.archive() != null) {
            capture(result, "archive", outcome.archive(), "application/zip", jobId + ".zip", MAX_ARCHIVE_BYTES);
        }
        return Map.copyOf(result);
    }

    public Map<String, Artifact> captureFailure(String jobId, Path outputRoot) {
        return Map.copyOf(baseArtifacts(jobId, outputRoot));
    }

    private Map<String, Artifact> baseArtifacts(String jobId, Path outputRoot) {
        Map<String, Artifact> result = new LinkedHashMap<>();
        capture(result, "manifest", outputRoot.resolve("GENERATION_MANIFEST.json"),
                "application/json; charset=utf-8", jobId + "-manifest.json", MAX_JSON_BYTES);
        capture(result, "report", outputRoot.resolve("VALIDATION_REPORT.json"),
                "application/json; charset=utf-8", jobId + "-report.json", MAX_JSON_BYTES);
        return result;
    }

    public void deleteJob(Path outputRoot) {
        deleteTree(outputRoot);
        if (outputRoot != null && outputRoot.getFileName() != null) {
            deletePath(outputRoot.resolveSibling(outputRoot.getFileName() + ".zip"));
        }
    }

    @Override
    public void close() {
        deleteTree(root);
    }

    public static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void capture(
            Map<String, Artifact> result,
            String name,
            Path path,
            String contentType,
            String downloadName,
            long maxBytes) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!owned(normalized) || Files.isSymbolicLink(normalized)) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile() && attributes.size() <= maxBytes) {
                result.put(name, new Artifact(
                        normalized, attributes.fileKey(), attributes.size(), contentType, downloadName,
                        digestBounded(normalized, maxBytes)));
            }
        } catch (IOException ignored) {
            // An unavailable artifact is omitted from the terminal snapshot.
        }
    }

    private byte[] digestBounded(Path path, long maxBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8_192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Artifact exceeds its size limit");
                }
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        }
    }

    private void deleteTree(Path tree) {
        if (tree == null) {
            return;
        }
        Path normalized = tree.toAbsolutePath().normalize();
        if (!normalized.equals(root) && !owned(normalized)) {
            return;
        }
        try (var paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deletePath);
        } catch (IOException ignored) {
            deletePath(normalized);
        }
    }

    private void deletePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.equals(root) && !owned(normalized)) {
            return;
        }
        try {
            Files.deleteIfExists(normalized);
        } catch (IOException ignored) {
            // Cleanup remains confined to the private job root.
        }
    }

    private boolean owned(Path path) {
        return path.startsWith(root) && !path.equals(root);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are unavailable on this filesystem.
        }
    }

    public record Artifact(
            Path path,
            Object fileKey,
            long size,
            String contentType,
            String downloadName,
            byte[] digest) {
        public Artifact {
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        public boolean stable() {
            try {
                if (Files.isSymbolicLink(path)) {
                    return false;
                }
                BasicFileAttributes current = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return current.isRegularFile() && current.size() == size
                        && Objects.equals(current.fileKey(), fileKey);
            } catch (IOException exception) {
                return false;
            }
        }
    }

}
