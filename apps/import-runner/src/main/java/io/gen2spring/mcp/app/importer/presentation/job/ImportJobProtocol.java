package io.gen2spring.mcp.app.importer.presentation.job;

import io.gen2spring.mcp.app.importer.application.imports.ImportRunner;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ImportJobProtocol {
    private static final int MAX_TARGET_BYTES = 4096;
    private static final Set<PosixFilePermission> WRITE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<PosixFilePermission> PRIVATE_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final ImportRunner runner;

    public ImportJobProtocol(ImportRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public int run(Path target, Path output) {
        Path source = null;
        Path stagingSource = null;
        Path stagingResult = null;
        try {
            requireOutput(output);
            ImportRunner.ImportResult imported = runner.run(readTarget(target));
            String extension = json(imported.mediaType()) ? "json" : "yaml";
            source = output.resolve("source." + extension);
            stagingSource = output.resolve(".staging-source");
            stagingResult = output.resolve(".staging-result");
            byte[] bytes = imported.source();
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            Files.write(stagingSource, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.write(
                    stagingResult,
                    JSON.writeValueAsBytes(Map.of(
                            "outcome", "SUCCESS",
                            "mediaType", imported.mediaType(),
                            "size", bytes.length,
                            "sha256", sha256)),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            permissions(stagingSource);
            permissions(stagingResult);
            Files.move(stagingSource, source, StandardCopyOption.ATOMIC_MOVE);
            stagingSource = null;
            Files.move(stagingResult, output.resolve("result.json"), StandardCopyOption.ATOMIC_MOVE);
            stagingResult = null;
            return 0;
        } catch (Error fatal) {
            cleanup(source, stagingSource, stagingResult);
            throw fatal;
        } catch (Exception failure) {
            cleanup(source, stagingSource, stagingResult);
            return 5;
        }
    }

    private void requireOutput(Path output) throws Exception {
        if (output == null
                || !output.isAbsolute()
                || Files.isSymbolicLink(output)
                || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException();
        }
        try (var entries = Files.list(output)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException();
            }
        }
    }

    private ImportTarget readTarget(Path targetFile) throws Exception {
        BasicFileAttributes before = requireTarget(targetFile);
        byte[] targetBytes = null;
        try {
            targetBytes = Files.readAllBytes(targetFile);
            if (targetBytes.length < 1 || targetBytes.length > MAX_TARGET_BYTES) {
                throw new IllegalArgumentException();
            }
            BasicFileAttributes after = requireTarget(targetFile);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey())) {
                throw new IllegalArgumentException();
            }
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(targetBytes));
            return ImportTarget.parse(decoded.toString());
        } finally {
            if (targetBytes != null) {
                Arrays.fill(targetBytes, (byte) 0);
            }
        }
    }

    private BasicFileAttributes requireTarget(Path targetFile) throws Exception {
        if (targetFile == null || !targetFile.isAbsolute() || Files.isSymbolicLink(targetFile)) {
            throw new IllegalArgumentException();
        }
        BasicFileAttributes attributes = Files.readAttributes(
                targetFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > MAX_TARGET_BYTES) {
            throw new IllegalArgumentException();
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    targetFile, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(WRITE_PERMISSIONS::contains)) {
                throw new IllegalArgumentException();
            }
        } catch (UnsupportedOperationException ignored) {
            // The container mounts this file read-only; Windows has no POSIX permission view.
        }
        return attributes;
    }

    private boolean json(String mediaType) {
        return "application/json".equals(mediaType) || mediaType.endsWith("+json");
    }

    private void permissions(Path file) throws Exception {
        try {
            Files.setPosixFilePermissions(file, PRIVATE_FILE);
        } catch (UnsupportedOperationException ignored) {
            // Windows container deployments provision equivalent ACLs.
        }
    }

    private void cleanup(Path source, Path stagingSource, Path stagingResult) {
        delete(source);
        delete(stagingSource);
        delete(stagingResult);
    }

    private void delete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Container removal remains the final cleanup boundary.
        }
    }
}
