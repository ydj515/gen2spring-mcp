package io.gen2spring.mcp.app.web.infrastructure.hosted.artifact;

import io.gen2spring.mcp.app.web.application.hosted.port.out.VerifiedArtifactReader;
import io.gen2spring.mcp.app.web.application.hosted.port.out.VerifiedArtifactReader.ArtifactSource;
import io.gen2spring.mcp.application.hosted.storage.port.out.ObjectStorage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

public final class TempFileVerifiedArtifactReader implements VerifiedArtifactReader {
    private final ObjectStorage storage;
    private final long maxBytes;

    public TempFileVerifiedArtifactReader(ObjectStorage storage, long maxBytes) {
        this.storage = Objects.requireNonNull(storage, "storage");
        if (maxBytes < 1) throw new IllegalArgumentException("Artifact size limit is invalid");
        this.maxBytes = maxBytes;
    }

    @Override
    public VerifiedArtifact open(ArtifactSource artifact) {
        Objects.requireNonNull(artifact, "artifact");
        Path staged = null;
        try (var content = storage.get(artifact.objectKey()); var input = content.body()) {
            if (artifact.byteSize() < 0 || artifact.byteSize() > maxBytes
                    || content.size() != artifact.byteSize()
                    || !content.sha256().equals(artifact.sha256())
                    || !content.contentType().equals(artifact.contentType())) {
                throw new IllegalStateException("Artifact metadata differs from the stored object");
            }
            staged = Files.createTempFile("gen2spring-artifact-", ".download");
            restrict(staged);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            try (var output = Files.newOutputStream(staged)) {
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > artifact.byteSize()) throw new IllegalStateException("Artifact exceeds its recorded size");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (total != artifact.byteSize()
                    || !HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256())) {
                throw new IllegalStateException("Artifact content differs from its recorded digest");
            }
            return new StagedArtifact(staged);
        } catch (Error fatal) {
            discard(staged);
            throw fatal;
        } catch (Exception failure) {
            discard(staged);
            throw new ArtifactReadFailure(failure);
        }
    }

    private static void restrict(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows uses the temporary directory ACL.
        }
    }

    private static void discard(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); }
        catch (IOException ignored) { /* The temporary directory reclaims an unavailable file. */ }
    }

    private record StagedArtifact(Path path) implements VerifiedArtifact {
        @Override
        public void writeTo(OutputStream output) throws IOException {
            Files.copy(path, output);
        }

        @Override
        public void close() {
            discard(path);
        }
    }

    public static final class ArtifactReadFailure extends RuntimeException {
        public ArtifactReadFailure(Throwable cause) {
            super("Artifact verification failed", cause);
        }
    }
}
