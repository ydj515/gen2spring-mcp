package io.gen2spring.mcp.app.importer.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ImportJobProtocol {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<PosixFilePermission> PRIVATE_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final ImportRunner runner;

    public ImportJobProtocol(ImportRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public int run(Path target, Path output, Path work) {
        Path source = null;
        Path stagingSource = null;
        Path stagingResult = null;
        try {
            requireOutput(output);
            ImportRunner.ImportResult imported = runner.run(target, work);
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
            cleanup(source, stagingSource, stagingResult, work);
            throw fatal;
        } catch (Exception failure) {
            cleanup(source, stagingSource, stagingResult, work);
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

    private void cleanup(Path source, Path stagingSource, Path stagingResult, Path work) {
        delete(source);
        delete(stagingSource);
        delete(stagingResult);
        if (work == null || Files.isSymbolicLink(work) || !Files.exists(work, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(work)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::delete);
        } catch (Exception ignored) {
            // Container removal remains the final cleanup boundary.
        }
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
