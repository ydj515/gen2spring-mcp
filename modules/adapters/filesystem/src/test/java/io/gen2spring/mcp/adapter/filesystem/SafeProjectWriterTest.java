package io.gen2spring.mcp.adapter.filesystem;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.application.port.outbound.GeneratedProjectFiles;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeProjectWriterTest {
    @TempDir
    Path tempDir;

    private final SafeProjectWriter writer = new SafeProjectWriter();

    @Test
    void rejectsAProjectEntryOutsideTheOutputRoot() throws IOException {
        Path safeTemp = tempDir.toRealPath();
        var files = new GeneratedProjectFiles(Map.of("../../escape.txt", "x".getBytes(UTF_8)));

        assertThrows(GeneratorException.class, () -> writer.write(safeTemp.resolve("project"), files));
        assertFalse(Files.exists(safeTemp.resolve("project")));
        assertFalse(Files.exists(safeTemp.getParent().resolve("escape.txt")));
    }

    @Test
    void rejectsAbsoluteAndPlatformAmbiguousProjectEntries() throws IOException {
        Path safeTemp = tempDir.toRealPath();
        assertThrows(GeneratorException.class, () -> writer.write(
                safeTemp.resolve("absolute"),
                new GeneratedProjectFiles(Map.of(safeTemp.resolve("escape.txt").toString(), new byte[0]))));
        assertThrows(GeneratorException.class, () -> writer.write(
                safeTemp.resolve("windows"),
                new GeneratedProjectFiles(Map.of("C:\\escape.txt", new byte[0]))));
    }

    @Test
    void rejectsCaseFoldedProjectPathCollisionsIndependentOfDefaultLocale() throws IOException {
        Path root = tempDir.toRealPath().resolve("project");
        var files = new LinkedHashMap<String, byte[]>();
        files.put("FILE.txt", "upper".getBytes(UTF_8));
        files.put("file.txt", "lower".getBytes(UTF_8));
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            assertThrows(GeneratorException.class,
                    () -> writer.write(root, new GeneratedProjectFiles(files)));
        } finally {
            Locale.setDefault(original);
        }

        assertFalse(Files.exists(root));
    }

    @Test
    void refusesAnExistingRootWithoutDeletingItsContents() throws IOException {
        Path root = Files.createDirectory(tempDir.toRealPath().resolve("project"));
        Path marker = Files.writeString(root.resolve("keep.txt"), "keep");

        assertThrows(GeneratorException.class, () -> writer.write(
                root, new GeneratedProjectFiles(Map.of("new.txt", "new".getBytes(UTF_8)))));

        assertTrue(Files.exists(marker));
        assertFalse(Files.exists(root.resolve("new.txt")));
    }

    @Test
    void rejectsASymbolicLinkAtTheOutputRoot() throws IOException {
        Path safeTemp = tempDir.toRealPath();
        Path realRoot = Files.createDirectory(safeTemp.resolve("real"));
        Path linkedRoot = safeTemp.resolve("project");
        try {
            Files.createSymbolicLink(linkedRoot, realRoot);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(GeneratorException.class, () -> writer.write(
                linkedRoot,
                new GeneratedProjectFiles(Map.of("README.md", "safe".getBytes(UTF_8)))));
        assertFalse(Files.exists(realRoot.resolve("README.md")));
    }

    @Test
    void rejectsAnyExistingSymbolicLinkAncestor() throws IOException {
        Path safeTemp = tempDir.toRealPath();
        Path realParent = Files.createDirectory(safeTemp.resolve("real-parent"));
        Files.createDirectory(realParent.resolve("nested"));
        Path linkedParent = safeTemp.resolve("linked-parent");
        try {
            Files.createSymbolicLink(linkedParent, realParent);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThrows(GeneratorException.class, () -> writer.write(
                linkedParent.resolve("nested/project"),
                new GeneratedProjectFiles(Map.of("README.md", "safe".getBytes(UTF_8)))));
        assertFalse(Files.exists(realParent.resolve("nested/project")));
    }

    @Test
    void writeFailureNeverPublishesAPartialRootAndRemovesOwnedStaging() throws IOException {
        Path safeTemp = tempDir.toRealPath();
        Path root = safeTemp.resolve("project");
        String invalidFileName = "z".repeat(512);
        var files = new GeneratedProjectFiles(Map.of(
                "a.txt", "published first without staging".getBytes(UTF_8),
                invalidFileName, "fails on common filesystems".getBytes(UTF_8)));

        assertThrows(GeneratorException.class, () -> writer.write(root, files));

        assertFalse(Files.exists(root));
        try (var children = Files.list(safeTemp)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().startsWith(".project.staging-")));
        }
    }

    @Test
    void targetReservationNeverReplacesAConcurrentDirectory() throws IOException {
        Path safeTemp = tempDir.toRealPath();
        Path root = safeTemp.resolve("project");
        SafeProjectWriter racingWriter = new SafeProjectWriter((staging, target) -> {
            Files.createDirectory(target);
            Files.writeString(target.resolve("competitor.txt"), "keep", UTF_8);
        });

        assertThrows(GeneratorException.class, () -> racingWriter.write(
                root, new GeneratedProjectFiles(Map.of("README.md", "generated".getBytes(UTF_8)))));

        assertEquals("keep", Files.readString(root.resolve("competitor.txt"), UTF_8));
        assertFalse(Files.exists(root.resolve("README.md")));
    }

    @Test
    void parentReplacementBeforePublicationFailsClosedWithoutWritingTheReplacement() throws IOException {
        Path safeTemp = tempDir.toRealPath();
        Path outputParent = Files.createDirectory(safeTemp.resolve("output-parent"));
        Path movedParent = safeTemp.resolve("output-parent-original");
        SafeProjectWriter racingWriter = new SafeProjectWriter((staging, target) -> {
            Files.move(outputParent, movedParent);
            Files.createDirectory(outputParent);
            Files.writeString(outputParent.resolve("competitor.txt"), "keep", UTF_8);
        });

        assertThrows(GeneratorException.class, () -> racingWriter.write(
                outputParent.resolve("project"),
                new GeneratedProjectFiles(Map.of("README.md", "generated".getBytes(UTF_8)))));

        assertEquals("keep", Files.readString(outputParent.resolve("competitor.txt"), UTF_8));
        assertFalse(Files.exists(outputParent.resolve("project")));
        deleteTestTree(movedParent);
    }

    @Test
    void cleanupNeverDeletesAReplacementStagingDirectory() throws IOException {
        Path safeTemp = tempDir.toRealPath();
        AtomicReference<Path> stagingPath = new AtomicReference<>();
        AtomicReference<Path> originalPath = new AtomicReference<>();
        SafeProjectWriter racingWriter = new SafeProjectWriter((staging, target) -> {
            stagingPath.set(staging);
            Path original = staging.resolveSibling(staging.getFileName() + ".original");
            originalPath.set(original);
            Files.move(staging, original);
            Files.createDirectory(staging);
            Files.writeString(staging.resolve("competitor.txt"), "keep", UTF_8);
            throw new IOException("simulated publication failure");
        });

        assertThrows(GeneratorException.class, () -> racingWriter.write(
                safeTemp.resolve("project"),
                new GeneratedProjectFiles(Map.of("README.md", "generated".getBytes(UTF_8)))));

        assertEquals("keep", Files.readString(stagingPath.get().resolve("competitor.txt"), UTF_8));
        deleteTestTree(stagingPath.get());
        deleteTestTree(originalPath.get());
    }

    @Test
    void writesExactBytesAndMakesGradlewExecutableOnPosix() throws IOException {
        Path safeTemp = tempDir.toRealPath();
        byte[] binary = new byte[] {0, 1, 13, 10, (byte) 0xff};
        Path root = safeTemp.resolve("project");

        writer.write(root, new GeneratedProjectFiles(Map.of(
                "assets/data.bin", binary,
                "gradlew", "#!/bin/sh\n".getBytes(UTF_8))));

        assertArrayEquals(binary, Files.readAllBytes(root.resolve("assets/data.bin")));
        FileStore store = Files.getFileStore(root);
        assumeTrue(store.supportsFileAttributeView("posix"));
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(root.resolve("gradlew"));
        assertTrue(permissions.contains(PosixFilePermission.OWNER_EXECUTE));
        assertTrue(permissions.contains(PosixFilePermission.GROUP_EXECUTE));
        assertTrue(permissions.contains(PosixFilePermission.OTHERS_EXECUTE));
    }

    private void deleteTestTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
