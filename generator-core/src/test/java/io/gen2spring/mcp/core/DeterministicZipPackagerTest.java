package io.gen2spring.mcp.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.gen2spring.mcp.domain.error.GeneratorException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeterministicZipPackagerTest {
    @TempDir
    Path tempDir;

    private final DeterministicZipPackager packager = new DeterministicZipPackager();
    private Path projectRoot;

    @BeforeEach
    void createProject() throws IOException {
        projectRoot = Files.createDirectory(tempDir.resolve("project"));
        Files.createDirectories(projectRoot.resolve("nested"));
        Files.writeString(projectRoot.resolve("z.txt"), "last", UTF_8);
        Files.writeString(projectRoot.resolve("nested/a.txt"), "first", UTF_8);
        Files.writeString(projectRoot.resolve("gradlew"), "#!/bin/sh\n", UTF_8);
        if (Files.getFileStore(projectRoot).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(projectRoot.resolve("gradlew"), Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE));
        }
    }

    @Test
    void packagesEntriesInOrderWithFixedTimestamps() throws IOException {
        byte[] first = packager.packageProject(projectRoot, tempDir.resolve("first.zip"));
        byte[] second = packager.packageProject(projectRoot, tempDir.resolve("second.zip"));

        assertArrayEquals(first, second);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(first))) {
            List<String> entries = new ArrayList<>();
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
                assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), entry.getTimeLocal());
            }
            assertEquals(List.of("gradlew", "nested/a.txt", "z.txt"), entries);
        }
    }

    @Test
    void recordsGradlewExecutableModeInTheCentralDirectory() {
        byte[] archive = packager.packageProject(projectRoot, tempDir.resolve("mode.zip"));

        assertEquals(0755, centralDirectoryMode(archive, "gradlew"));
        assertEquals(0644, centralDirectoryMode(archive, "z.txt"));
    }

    @Test
    void preservesGradlewExecutableModeWhenSystemUnzipIsAvailable() throws Exception {
        Path unzip = findExecutableOnPath("unzip");
        assumeTrue(unzip != null, "System unzip is unavailable");
        assumeTrue(Files.getFileStore(projectRoot).supportsFileAttributeView("posix"));
        Path archive = tempDir.resolve("project.zip");
        packager.packageProject(projectRoot, archive);
        Path extracted = Files.createDirectory(tempDir.resolve("extracted"));

        Process process = new ProcessBuilder(
                unzip.toString(), "-qq", archive.toString(), "-d", extracted.toString()).start();
        assertEquals(0, process.waitFor());

        assertTrue(Files.getPosixFilePermissions(extracted.resolve("gradlew"))
                .contains(PosixFilePermission.OWNER_EXECUTE));
    }

    @Test
    void archivePublicationNeverReplacesAConcurrentFile() throws IOException {
        Path archive = tempDir.resolve("project.zip");
        DeterministicZipPackager racingPackager = new DeterministicZipPackager((staging, target) ->
                Files.writeString(target, "keep", UTF_8));

        assertThrows(GeneratorException.class, () -> racingPackager.packageProject(projectRoot, archive));

        assertEquals("keep", Files.readString(archive, UTF_8));
    }

    @Test
    void archiveParentReplacementBeforePublicationFailsClosedWithoutWritingTheReplacement() throws IOException {
        Path archiveParent = Files.createDirectory(tempDir.resolve("archive-parent"));
        Path movedParent = tempDir.resolve("archive-parent-original");
        Path archive = archiveParent.resolve("project.zip");
        DeterministicZipPackager racingPackager = new DeterministicZipPackager((staging, target) -> {
            Files.move(archiveParent, movedParent);
            Files.createDirectory(archiveParent);
            Files.writeString(archiveParent.resolve("competitor.txt"), "keep", UTF_8);
        });

        assertThrows(GeneratorException.class, () -> racingPackager.packageProject(projectRoot, archive));

        assertEquals("keep", Files.readString(archiveParent.resolve("competitor.txt"), UTF_8));
        assertFalse(Files.exists(archive));
        deleteTree(movedParent);
    }

    @Test
    void archiveCleanupNeverDeletesAReplacementStagingFile() throws IOException {
        AtomicReference<Path> replacement = new AtomicReference<>();
        AtomicReference<Path> original = new AtomicReference<>();
        DeterministicZipPackager racingPackager = new DeterministicZipPackager((staging, target) -> {
            Path moved = staging.resolveSibling(staging.getFileName() + ".original");
            Files.move(staging, moved);
            original.set(moved);
            Files.writeString(staging, "keep", UTF_8);
            replacement.set(staging);
            throw new IOException("simulated publication failure");
        });
        Path archive = tempDir.resolve("replacement.zip");

        assertThrows(GeneratorException.class, () -> racingPackager.packageProject(projectRoot, archive));

        assertEquals("keep", Files.readString(replacement.get(), UTF_8));
        assertFalse(Files.exists(archive));
        Files.delete(replacement.get());
        Files.delete(original.get());
    }

    @Test
    void rejectsAnOversizedUnreadableEntryFromMetadataBeforeOpeningIt() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("metadata-project"));
        Path entry = Files.write(root.resolve("oversized.bin"), new byte[] {1, 2, 3, 4});
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(entry, Set.of());

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> new DeterministicZipPackager(10, 3, 10, 4_096)
                        .packageProject(root, tempDir.resolve("metadata-project.zip")));

        assertEquals("Generated project entry exceeds the P0 classic ZIP contract", failure.safeMessage());
        assertFalse(Files.exists(tempDir.resolve("metadata-project.zip")));
    }

    @Test
    void rejectsConfiguredClassicZipBoundsBeforePublication() {
        Path entryCountArchive = tempDir.resolve("entries.zip");
        Path entrySizeArchive = tempDir.resolve("entry-size.zip");
        Path archiveSizeArchive = tempDir.resolve("archive-size.zip");

        assertThrows(GeneratorException.class, () -> new DeterministicZipPackager(2, 1_024, 4_096, 4_096)
                .packageProject(projectRoot, entryCountArchive));
        assertThrows(GeneratorException.class, () -> new DeterministicZipPackager(10, 3, 4_096, 4_096)
                .packageProject(projectRoot, entrySizeArchive));
        assertThrows(GeneratorException.class, () -> new DeterministicZipPackager(10, 1_024, 4_096, 32)
                .packageProject(projectRoot, archiveSizeArchive));
        assertFalse(Files.exists(entryCountArchive));
        assertFalse(Files.exists(entrySizeArchive));
        assertFalse(Files.exists(archiveSizeArchive));
    }

    @Test
    void rejectsZip64SentinelsAndLocators() {
        byte[] archive = packager.packageProject(projectRoot, tempDir.resolve("classic.zip"));
        int end = findSignatureFromEnd(archive, 0x06054b50);
        byte[] sentinel = archive.clone();
        sentinel[end + 10] = (byte) 0xff;
        sentinel[end + 11] = (byte) 0xff;
        byte[] locator = archive.clone();
        writeLittleEndianInt(locator, end - 20, 0x07064b50);

        assertThrows(GeneratorException.class, () -> packager.verifyClassicZip(sentinel));
        assertThrows(GeneratorException.class, () -> packager.verifyClassicZip(locator));
    }

    @Test
    void rejectsAStructurallyValidZip64LocatorAndEndRecord() {
        byte[] archive = packager.packageProject(projectRoot, tempDir.resolve("zip64-structure.zip"));
        int classicEnd = archive.length - 22;
        byte[] zip64 = new byte[archive.length + 76];
        System.arraycopy(archive, 0, zip64, 0, classicEnd);
        int zip64End = classicEnd;
        int locator = zip64End + 56;
        writeLittleEndianInt(zip64, zip64End, 0x06064b50);
        writeLittleEndianLong(zip64, zip64End + 4, 44);
        writeLittleEndianInt(zip64, locator, 0x07064b50);
        writeLittleEndianLong(zip64, locator + 8, zip64End);
        writeLittleEndianInt(zip64, locator + 16, 1);
        System.arraycopy(archive, classicEnd, zip64, locator + 20, 22);

        assertThrows(GeneratorException.class, () -> packager.verifyClassicZip(zip64));
    }

    @Test
    void rejectsZip64SentinelHiddenByAFakeEndRecordInTheComment() {
        byte[] archive = packager.packageProject(projectRoot, tempDir.resolve("comment-bypass.zip"));
        int realEnd = archive.length - 22;
        byte[] fakeEnd = java.util.Arrays.copyOfRange(archive, realEnd, archive.length);
        byte[] comment = new byte[22];
        System.arraycopy(fakeEnd, 0, comment, 0, fakeEnd.length);
        byte[] withComment = appendComment(archive, comment);
        withComment[realEnd + 10] = (byte) 0xff;
        withComment[realEnd + 11] = (byte) 0xff;

        assertThrows(GeneratorException.class, () -> packager.verifyClassicZip(withComment));
    }

    @Test
    void acceptsAValidCommentContainingZipStructureSignatures() {
        byte[] archive = packager.packageProject(projectRoot, tempDir.resolve("valid-comment.zip"));
        byte[] comment = new byte[40];
        writeLittleEndianInt(comment, 3, 0x06064b50);
        writeLittleEndianInt(comment, 12, 0x06054b50);

        assertDoesNotThrow(() -> packager.verifyClassicZip(appendComment(archive, comment)));
    }

    @Test
    void rejectsAFakeCentralDirectoryAndEndRecordInTheComment() {
        byte[] archive = packager.packageProject(projectRoot, tempDir.resolve("fake-directory-comment.zip"));
        byte[] fakeCentral = new byte[46];
        writeLittleEndianInt(fakeCentral, 0, 0x02014b50);

        assertThrows(GeneratorException.class,
                () -> packager.verifyClassicZip(withFakeDirectoryComment(archive, new byte[0], fakeCentral)));
    }

    @Test
    void rejectsACommentDirectoryPointingToAnExistingLocalHeader() {
        byte[] archive = packager.packageProject(projectRoot, tempDir.resolve("existing-local-comment.zip"));
        int end = archive.length - 22;
        int centralOffset = littleEndianInt(archive, end + 16);
        int entryLength = centralEntryLength(archive, centralOffset);
        byte[] copiedCentral = java.util.Arrays.copyOfRange(
                archive, centralOffset, centralOffset + entryLength);

        assertThrows(GeneratorException.class,
                () -> packager.verifyClassicZip(withFakeDirectoryComment(archive, new byte[0], copiedCentral)));
    }

    @Test
    void rejectsACommentDirectoryPointingToAFakeLocalHeaderInTheComment() {
        byte[] archive = packager.packageProject(projectRoot, tempDir.resolve("fake-local-comment.zip"));
        byte[] fakeLocal = new byte[31];
        writeLittleEndianInt(fakeLocal, 0, 0x04034b50);
        writeLittleEndianShort(fakeLocal, 6, 0x0808);
        writeLittleEndianShort(fakeLocal, 8, 8);
        writeLittleEndianShort(fakeLocal, 26, 1);
        fakeLocal[30] = 'x';
        byte[] fakeCentral = new byte[47];
        writeLittleEndianInt(fakeCentral, 0, 0x02014b50);
        writeLittleEndianShort(fakeCentral, 8, 0x0808);
        writeLittleEndianShort(fakeCentral, 10, 8);
        writeLittleEndianShort(fakeCentral, 28, 1);
        writeLittleEndianInt(fakeCentral, 42, archive.length);
        fakeCentral[46] = 'x';

        assertThrows(GeneratorException.class,
                () -> packager.verifyClassicZip(withFakeDirectoryComment(archive, fakeLocal, fakeCentral)));
    }

    @Test
    void rejectsDuplicateAndMisalignedLocalHeaderOffsets() {
        byte[] archive = packager.packageProject(projectRoot, tempDir.resolve("local-offsets.zip"));
        int end = archive.length - 22;
        int firstCentral = littleEndianInt(archive, end + 16);
        int secondCentral = firstCentral + centralEntryLength(archive, firstCentral);
        int firstLocal = littleEndianInt(archive, firstCentral + 42);
        byte[] duplicate = archive.clone();
        writeLittleEndianInt(duplicate, secondCentral + 42, firstLocal);
        byte[] misaligned = archive.clone();
        writeLittleEndianInt(misaligned, firstCentral + 42, firstLocal + 1);

        assertThrows(GeneratorException.class, () -> packager.verifyClassicZip(duplicate));
        assertThrows(GeneratorException.class, () -> packager.verifyClassicZip(misaligned));
    }

    @Test
    void rejectsLocalMetadataAndDataDescriptorMismatches() {
        byte[] archive = packager.packageProject(projectRoot, tempDir.resolve("local-metadata.zip"));
        int end = archive.length - 22;
        int central = littleEndianInt(archive, end + 16);
        int local = littleEndianInt(archive, central + 42);
        byte[] flags = archive.clone();
        writeLittleEndianShort(flags, local + 6, unsignedShort(archive, local + 6) ^ 0x0800);
        byte[] method = archive.clone();
        writeLittleEndianShort(method, local + 8, 0);
        byte[] name = archive.clone();
        name[local + 30] ^= 1;
        int payload = local + 30 + unsignedShort(archive, local + 26) + unsignedShort(archive, local + 28);
        int descriptor = payload + littleEndianInt(archive, central + 20);
        assertEquals(0x08074b50, littleEndianInt(archive, descriptor));
        byte[] descriptorCrc = archive.clone();
        descriptorCrc[descriptor + 4] ^= 1;

        assertThrows(GeneratorException.class, () -> packager.verifyClassicZip(flags));
        assertThrows(GeneratorException.class, () -> packager.verifyClassicZip(method));
        assertThrows(GeneratorException.class, () -> packager.verifyClassicZip(name));
        assertThrows(GeneratorException.class, () -> packager.verifyClassicZip(descriptorCrc));
    }

    @Test
    void rejectsPortablePathTraversalCreatedFromAPosixBackslashFilename() throws IOException {
        assumeTrue(java.io.File.separatorChar == '/');
        Files.writeString(projectRoot.resolve("..\\escape.txt"), "escape", UTF_8);
        Path archive = tempDir.resolve("unsafe.zip");

        assertThrows(GeneratorException.class, () -> packager.packageProject(projectRoot, archive));
        assertFalse(Files.exists(archive));
    }

    @Test
    void publicPackagingRejectsCaseFoldedEntriesIndependentOfDefaultLocale() throws IOException {
        Files.writeString(projectRoot.resolve("FILE.txt"), "upper", UTF_8);
        Files.writeString(projectRoot.resolve("file.txt"), "lower", UTF_8);
        try (var paths = Files.list(projectRoot)) {
            assumeTrue(paths
                            .map(path -> path.getFileName().toString())
                            .filter(name -> name.equals("FILE.txt") || name.equals("file.txt"))
                            .count() == 2,
                    "Case-distinct paths are unavailable on this filesystem");
        }
        Path archive = tempDir.resolve("case-fold.zip");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            assertThrows(GeneratorException.class, () -> packager.packageProject(projectRoot, archive));
        } finally {
            Locale.setDefault(original);
        }

        assertFalse(Files.exists(archive));
    }

    @Test
    void rejectsAnArchiveInsideTheProjectAndDoesNotSelfInclude() {
        Path archive = projectRoot.resolve("project.zip");

        assertThrows(GeneratorException.class, () -> packager.packageProject(projectRoot, archive));
        assertFalse(Files.exists(archive));
    }

    @Test
    void rejectsSymbolicLinksWithoutFollowingThem() throws IOException {
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside", UTF_8);
        Path link = projectRoot.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        Path archive = tempDir.resolve("linked.zip");

        assertThrows(GeneratorException.class, () -> packager.packageProject(projectRoot, archive));
        assertFalse(Files.exists(archive));
    }

    @Test
    void refusesToOverwriteAnExistingArchive() throws IOException {
        Path archive = Files.writeString(tempDir.resolve("existing.zip"), "keep", UTF_8);

        assertThrows(GeneratorException.class, () -> packager.packageProject(projectRoot, archive));
        assertEquals("keep", Files.readString(archive, UTF_8));
    }

    private int centralDirectoryMode(byte[] archive, String expectedName) {
        int end = findSignatureFromEnd(archive, 0x06054b50);
        int count = unsignedShort(archive, end + 10);
        int offset = littleEndianInt(archive, end + 16);
        for (int index = 0; index < count; index++) {
            assertEquals(0x02014b50, littleEndianInt(archive, offset));
            int nameLength = unsignedShort(archive, offset + 28);
            int extraLength = unsignedShort(archive, offset + 30);
            int commentLength = unsignedShort(archive, offset + 32);
            String name = new String(archive, offset + 46, nameLength, UTF_8);
            if (name.equals(expectedName)) {
                return littleEndianInt(archive, offset + 38) >>> 16 & 0777;
            }
            offset += 46 + nameLength + extraLength + commentLength;
        }
        throw new AssertionError("Missing central directory entry: " + expectedName);
    }

    private Path findExecutableOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String directory : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(directory).resolve(executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private int findSignatureFromEnd(byte[] bytes, int signature) {
        for (int index = bytes.length - 4; index >= 0; index--) {
            if (littleEndianInt(bytes, index) == signature) {
                return index;
            }
        }
        throw new AssertionError("ZIP signature is missing");
    }

    private byte[] appendComment(byte[] archive, byte[] comment) {
        int end = archive.length - 22;
        assertEquals(0x06054b50, littleEndianInt(archive, end));
        byte[] withComment = java.util.Arrays.copyOf(archive, archive.length + comment.length);
        writeLittleEndianShort(withComment, end + 20, comment.length);
        System.arraycopy(comment, 0, withComment, archive.length, comment.length);
        return withComment;
    }

    private byte[] withFakeDirectoryComment(byte[] archive, byte[] prefix, byte[] fakeCentral) {
        byte[] comment = new byte[prefix.length + fakeCentral.length + 22];
        System.arraycopy(prefix, 0, comment, 0, prefix.length);
        System.arraycopy(fakeCentral, 0, comment, prefix.length, fakeCentral.length);
        int fakeEnd = prefix.length + fakeCentral.length;
        writeLittleEndianInt(comment, fakeEnd, 0x06054b50);
        writeLittleEndianShort(comment, fakeEnd + 8, 1);
        writeLittleEndianShort(comment, fakeEnd + 10, 1);
        writeLittleEndianInt(comment, fakeEnd + 12, fakeCentral.length);
        writeLittleEndianInt(comment, fakeEnd + 16, archive.length + prefix.length);
        byte[] bypass = appendComment(archive, comment);
        int realEnd = archive.length - 22;
        writeLittleEndianShort(bypass, realEnd + 10, 0xffff);
        return bypass;
    }

    private int centralEntryLength(byte[] archive, int offset) {
        assertEquals(0x02014b50, littleEndianInt(archive, offset));
        return 46 + unsignedShort(archive, offset + 28)
                + unsignedShort(archive, offset + 30)
                + unsignedShort(archive, offset + 32);
    }

    private int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        return unsignedShort(bytes, offset) | unsignedShort(bytes, offset + 2) << 16;
    }

    private void writeLittleEndianInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private void writeLittleEndianShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private void writeLittleEndianLong(byte[] bytes, int offset, long value) {
        writeLittleEndianInt(bytes, offset, (int) value);
        writeLittleEndianInt(bytes, offset + 4, (int) (value >>> 32));
    }

    private void deleteTree(Path root) throws IOException {
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
