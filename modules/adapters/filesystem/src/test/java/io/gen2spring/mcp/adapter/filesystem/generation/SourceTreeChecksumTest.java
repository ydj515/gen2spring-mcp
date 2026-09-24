package io.gen2spring.mcp.adapter.filesystem.generation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.gen2spring.mcp.application.generation.port.out.GeneratedProjectFiles;
import io.gen2spring.mcp.domain.error.GeneratorException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceTreeChecksumTest {
    @TempDir
    Path tempDir;

    private final SourceTreeChecksum checksum = new SourceTreeChecksum();

    @Test
    void checksumIgnoresReportsAndProcessLogsButIncludesZipSourcesAndMapOrder() {
        var firstFiles = new LinkedHashMap<String, byte[]>();
        firstFiles.put("b.txt", "b\r\n".getBytes(UTF_8));
        firstFiles.put("a.txt", "a".getBytes(UTF_8));
        firstFiles.put("GENERATION_MANIFEST.json", "first manifest".getBytes(UTF_8));
        firstFiles.put("VALIDATION_REPORT.json", "first run".getBytes(UTF_8));
        firstFiles.put("process-logs/compile.log", "first process".getBytes(UTF_8));
        var secondFiles = new LinkedHashMap<String, byte[]>();
        secondFiles.put("process-logs/compile.log", "second process".getBytes(UTF_8));
        secondFiles.put("VALIDATION_REPORT.json", "second run".getBytes(UTF_8));
        secondFiles.put("GENERATION_MANIFEST.json", "second manifest".getBytes(UTF_8));
        secondFiles.put("a.txt", "a".getBytes(UTF_8));
        secondFiles.put("b.txt", "b\n".getBytes(UTF_8));

        assertEquals(checksum.calculate(new GeneratedProjectFiles(firstFiles)),
                checksum.calculate(new GeneratedProjectFiles(secondFiles)));

        var zipFirst = new GeneratedProjectFiles(java.util.Map.of(
                "assets/fixture.zip", "first source archive".getBytes(UTF_8)));
        var zipSecond = new GeneratedProjectFiles(java.util.Map.of(
                "assets/fixture.zip", "second source archive".getBytes(UTF_8)));
        assertNotEquals(checksum.calculate(zipFirst), checksum.calculate(zipSecond));
    }

    @Test
    void lengthFramingPreventsNulContentFromCollidingWithAnotherFileBoundary() {
        var oneFile = new GeneratedProjectFiles(java.util.Map.of("a", new byte[] {'x', 0, 'b'}));
        var twoFiles = new GeneratedProjectFiles(java.util.Map.of(
                "a", new byte[] {'x'},
                "b", new byte[0]));

        assertNotEquals(checksum.calculate(oneFile), checksum.calculate(twoFiles));
    }

    @Test
    void inMemoryChecksumRejectsCaseFoldedPathsIndependentOfDefaultLocale() {
        var files = new LinkedHashMap<String, byte[]>();
        files.put("FILE.txt", "upper".getBytes(UTF_8));
        files.put("file.txt", "lower".getBytes(UTF_8));
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            assertThrows(GeneratorException.class,
                    () -> checksum.calculate(new GeneratedProjectFiles(files)));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void diskSnapshotRejectsCaseFoldedPathsIndependentOfDefaultLocale() throws IOException {
        Path root = Files.createDirectory(tempDir.toRealPath().resolve("case-fold-project"));
        Files.writeString(root.resolve("FILE.txt"), "upper", UTF_8);
        Files.writeString(root.resolve("file.txt"), "lower", UTF_8);
        try (var paths = Files.list(root)) {
            assumeTrue(paths.map(Path::getFileName).distinct().count() == 2,
                    "Case-distinct paths are unavailable on this filesystem");
        }
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            assertThrows(GeneratorException.class, () -> checksum.snapshot(root));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void exactCaseRemainsPartOfTheDeterministicChecksum() {
        var upper = new GeneratedProjectFiles(Map.of("README.md", "same".getBytes(UTF_8)));
        var lower = new GeneratedProjectFiles(Map.of("readme.md", "same".getBytes(UTF_8)));

        assertNotEquals(checksum.calculate(upper), checksum.calculate(lower));
    }

    @Test
    void runtimeMetadataParticipatesInTheSourceChecksum() {
        var first = new GeneratedProjectFiles(Map.of(
                "README.md", "same".getBytes(UTF_8),
                "RUNTIME_METADATA.json", "{\"checksum\":\"first\"}\n".getBytes(UTF_8)));
        var second = new GeneratedProjectFiles(Map.of(
                "README.md", "same".getBytes(UTF_8),
                "RUNTIME_METADATA.json", "{\"checksum\":\"second\"}\n".getBytes(UTF_8)));

        assertNotEquals(checksum.calculate(first), checksum.calculate(second));
    }

    @Test
    void categoryMatchingDoesNotIgnoreSimilarSourcePaths() {
        var first = new GeneratedProjectFiles(new LinkedHashMap<>(java.util.Map.of(
                "src/catalog.txt", "one".getBytes(UTF_8),
                "docs/VALIDATION_REPORT.json", "nested".getBytes(UTF_8))));
        var second = new GeneratedProjectFiles(new LinkedHashMap<>(java.util.Map.of(
                "src/catalog.txt", "two".getBytes(UTF_8),
                "docs/VALIDATION_REPORT.json", "nested".getBytes(UTF_8))));

        assertNotEquals(checksum.calculate(first), checksum.calculate(second));
    }

    @Test
    void diskAndInMemoryChecksumsUseTheSameCanonicalForm() throws IOException {
        var files = new GeneratedProjectFiles(java.util.Map.of(
                "z.txt", "last\r\nline\r".getBytes(UTF_8),
                "nested/a.txt", "first".getBytes(UTF_8)));
        Path root = tempDir.toRealPath().resolve("project");
        new SafeProjectWriter().write(root, files);

        assertEquals(checksum.calculate(files), checksum.calculate(root));
    }

    @Test
    void rejectsDiskEntriesAndTotalsBeyondTheChecksumBounds() throws IOException {
        Path root = Files.createDirectory(tempDir.toRealPath().resolve("bounded-project"));
        Files.write(root.resolve("first.bin"), new byte[] {1, 2, 3});
        Files.write(root.resolve("second.bin"), new byte[] {4, 5, 6});

        GeneratorException entryFailure = assertThrows(GeneratorException.class,
                () -> new SourceTreeChecksum(10, 2, 10).calculate(root));
        GeneratorException totalFailure = assertThrows(GeneratorException.class,
                () -> new SourceTreeChecksum(10, 3, 5).calculate(root));

        assertEquals("Generated project checksum entry exceeds the P0 size bound", entryFailure.safeMessage());
        assertEquals("Generated project checksum content exceeds the P0 size bound", totalFailure.safeMessage());
    }

    @Test
    void rejectsInMemoryTotalsWithoutOverflowingTheAccumulator() {
        var files = new GeneratedProjectFiles(Map.of(
                "first.bin", new byte[] {1, 2, 3},
                "second.bin", new byte[] {4, 5, 6}));

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> new SourceTreeChecksum(10, 3, 5).calculate(files));

        assertEquals("Generated project checksum content exceeds the P0 size bound", failure.safeMessage());
    }

    @Test
    void streamingDiskChecksumNormalizesCrLfAcrossBufferBoundaries() throws IOException {
        byte[] content = ("a".repeat(8_191) + "\r\nlast\r").getBytes(UTF_8);
        var files = new GeneratedProjectFiles(Map.of("boundary.txt", content));
        Path root = Files.createDirectory(tempDir.toRealPath().resolve("streaming-project"));
        Files.write(root.resolve("boundary.txt"), content);

        assertEquals(checksum.calculate(files), checksum.calculate(root));
    }

    @Test
    void diskChecksumRejectsAPosixBackslashFilenameLikeTheInMemoryContract() throws IOException {
        assumeTrue(java.io.File.separatorChar == '/');
        Path root = Files.createDirectory(tempDir.toRealPath().resolve("backslash-project"));
        Files.writeString(root.resolve("nested\\entry.txt"), "content", UTF_8);

        GeneratorException memoryFailure = assertThrows(GeneratorException.class,
                () -> checksum.calculate(new GeneratedProjectFiles(
                        Map.of("nested\\entry.txt", "content".getBytes(UTF_8)))));
        GeneratorException diskFailure = assertThrows(GeneratorException.class,
                () -> checksum.calculate(root));

        assertEquals("Generated project contains an invalid checksum path", memoryFailure.safeMessage());
        assertEquals(memoryFailure.safeMessage(), diskFailure.safeMessage());
    }

    @Test
    void diskChecksumRejectsAWindowsDriveLikePosixComponentLikeTheInMemoryContract() throws IOException {
        assumeTrue(java.io.File.separatorChar == '/');
        Path root = Files.createDirectory(tempDir.toRealPath().resolve("drive-project"));
        Files.writeString(root.resolve("C:"), "content", UTF_8);

        GeneratorException memoryFailure = assertThrows(GeneratorException.class,
                () -> checksum.calculate(new GeneratedProjectFiles(
                        Map.of("C:", "content".getBytes(UTF_8)))));
        GeneratorException diskFailure = assertThrows(GeneratorException.class,
                () -> checksum.calculate(root));

        assertEquals("Generated project contains an invalid checksum path", memoryFailure.safeMessage());
        assertEquals(memoryFailure.safeMessage(), diskFailure.safeMessage());
    }
}
