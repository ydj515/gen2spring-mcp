package io.gen2spring.mcp.adapter.filesystem;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.ARTIFACT_PACKAGE_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import io.gen2spring.mcp.application.generation.port.out.ArtifactPackager;
import io.gen2spring.mcp.application.generation.port.out.SourceSnapshot;
import io.gen2spring.mcp.application.generation.port.out.SourceSnapshot.EntryFingerprint;
import io.gen2spring.mcp.domain.error.GeneratorException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DeterministicZipPackager implements ArtifactPackager {
    private static final String STAGE = "PACKAGE";
    private static final LocalDateTime DOS_EPOCH_UTC = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final int CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
    private static final int LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50;
    private static final int DATA_DESCRIPTOR_SIGNATURE = 0x08074b50;
    private static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;
    private static final int ZIP64_END_SIGNATURE = 0x06064b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int CENTRAL_DIRECTORY_FIXED_SIZE = 46;
    private static final int LOCAL_FILE_HEADER_FIXED_SIZE = 30;
    private static final int DATA_DESCRIPTOR_FLAG = 0x0008;
    private static final int UTF_8_FLAG = 0x0800;
    private static final int DEFLATED_METHOD = 8;
    private static final int REGULAR_FILE_MODE = 0100644;
    private static final int EXECUTABLE_FILE_MODE = 0100755;
    private static final int DEFAULT_MAX_ENTRIES = 10_000;
    private static final long DEFAULT_MAX_ENTRY_BYTES = 100L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final long DEFAULT_MAX_ARCHIVE_BYTES = 768L * 1024 * 1024;
    private static final int BUFFER_BYTES = 8 * 1024;

    private final int maxEntries;
    private final long maxEntryBytes;
    private final long maxTotalBytes;
    private final long maxArchiveBytes;
    private final PublicationHook publicationHook;

    public DeterministicZipPackager() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_ARCHIVE_BYTES,
                (staging, target) -> {});
    }

    DeterministicZipPackager(PublicationHook publicationHook) {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_ARCHIVE_BYTES,
                publicationHook);
    }

    DeterministicZipPackager(int maxEntries, long maxEntryBytes, long maxTotalBytes, long maxArchiveBytes) {
        this(maxEntries, maxEntryBytes, maxTotalBytes, maxArchiveBytes, (staging, target) -> {});
    }

    private DeterministicZipPackager(
            int maxEntries,
            long maxEntryBytes,
            long maxTotalBytes,
            long maxArchiveBytes,
            PublicationHook publicationHook) {
        if (maxEntries <= 0 || maxEntries >= 0xffff
                || maxEntryBytes <= 0 || maxTotalBytes <= 0 || maxArchiveBytes <= 0
                || maxTotalBytes >= 0xffff_ffffL || maxArchiveBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Classic ZIP bounds are invalid");
        }
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxArchiveBytes = maxArchiveBytes;
        this.publicationHook = Objects.requireNonNull(publicationHook, "publicationHook");
    }

    public byte[] packageProject(Path projectRoot, Path archive) {
        return packageProject(projectRoot, archive, null);
    }

    public byte[] packageProject(
            Path projectRoot,
            Path archive,
            SourceSnapshot expectedSource) {
        Path root = requireProjectRoot(projectRoot);
        Path output = requireArchivePath(root, archive);
        Path staging = null;
        StablePathIdentity stagingIdentity = null;
        StablePathIdentity outputIdentity = null;
        ParentIdentity parentIdentity = parentIdentity(output.getParent());
        try {
            List<ProjectEntry> entries = collectEntries(root);
            validateSourceContract(entries, expectedSource);
            validateClassicBounds(entries);
            String prefix = "." + output.getFileName() + ".staging-";
            staging = Files.createTempFile(output.getParent(), prefix, ".tmp");
            StablePathIdentity reservedStagingIdentity = StablePathIdentity.capture(staging);
            createArchive(entries, staging, expectedSource);
            if (!reservedStagingIdentity.matchesObject(staging)) {
                throw failure("Generated project archive staging identity changed");
            }
            stagingIdentity = StablePathIdentity.capture(staging);
            byte[] bytes = readBoundedArchive(staging, stagingIdentity);
            verifyClassicZip(bytes);
            bytes = applyUnixModes(bytes, entries);
            verifyObjectIdentity(staging, stagingIdentity);
            Files.write(staging, bytes, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS);
            stagingIdentity = StablePathIdentity.capture(staging);
            if (!hasExactBytes(staging, stagingIdentity, bytes)) {
                throw failure("Generated project archive staging verification failed");
            }
            publicationHook.beforeTargetReservation(staging, output);
            verifyParentIdentity(output.getParent(), parentIdentity);
            verifyIdentity(staging, stagingIdentity);
            try {
                Files.createLink(output, staging);
            } catch (UnsupportedOperationException exception) {
                throw GeneratorException.system(
                        ARTIFACT_PACKAGE_FAILED,
                        STAGE,
                        "No-replace archive publication is unavailable for the output filesystem",
                        exception);
            }
            outputIdentity = StablePathIdentity.capture(output);
            if (!stagingIdentity.sameFile(staging, outputIdentity, output)
                    || !hasExactBytes(output, outputIdentity, bytes)) {
                throw failure("Published archive identity or bytes changed");
            }
            deleteOwnedFile(staging, output.getParent(), parentIdentity, stagingIdentity);
            staging = null;
            stagingIdentity = null;
            outputIdentity = null;
            return bytes;
        } catch (GeneratorException exception) {
            cleanupOwnedFile(exception, output, output.getParent(), parentIdentity, outputIdentity);
            cleanupOwnedFile(exception, staging, output.getParent(), parentIdentity, stagingIdentity);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            GeneratorException wrapped = GeneratorException.system(
                    ARTIFACT_PACKAGE_FAILED, STAGE, "Generated project archive could not be created", exception);
            cleanupOwnedFile(wrapped, output, output.getParent(), parentIdentity, outputIdentity);
            cleanupOwnedFile(wrapped, staging, output.getParent(), parentIdentity, stagingIdentity);
            throw wrapped;
        }
    }

    public void requireArchiveAvailable(Path projectRoot, Path archive) {
        Path root = projectRoot.toAbsolutePath().normalize();
        requireArchivePath(root, archive);
    }

    private Path requireProjectRoot(Path projectRoot) {
        if (projectRoot == null) {
            throw failure("Generated project root is required");
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw failure("Generated project root must be a regular directory");
        }
        return root;
    }

    private Path requireArchivePath(Path root, Path archive) {
        if (archive == null || archive.getFileName() == null) {
            throw failure("Archive output path is required");
        }
        Path output = archive.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent == null || Files.isSymbolicLink(parent) || !Files.isDirectory(parent, NOFOLLOW_LINKS)) {
            throw failure("Archive output parent must be a regular directory");
        }
        try {
            Path realParent = parent.toRealPath();
            if (Files.exists(root, NOFOLLOW_LINKS)) {
                Path realRoot = root.toRealPath();
                Path realOutput = realParent.resolve(output.getFileName()).normalize();
                if (realOutput.startsWith(realRoot)) {
                    throw failure("Archive output must be outside the generated project");
                }
            } else if (output.startsWith(root)) {
                throw failure("Archive output must be outside the generated project");
            }
        } catch (IOException exception) {
            throw GeneratorException.system(
                    ARTIFACT_PACKAGE_FAILED, STAGE, "Archive output path could not be verified", exception);
        }
        if (Files.exists(output, NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
            throw failure("Archive output already exists");
        }
        return output;
    }

    private List<ProjectEntry> collectEntries(Path root) throws IOException {
        Set<String> portablePathKeys = new HashSet<>();
        List<ProjectEntry> entries = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (path.equals(root)) {
                    continue;
                }
                ProjectEntry entry = projectEntry(root, path);
                if (entry == null) {
                    continue;
                }
                if (entries.size() >= maxEntries) {
                    throw failure("Generated project has too many entries for the P0 classic ZIP contract");
                }
                if (!portablePathKeys.add(PortablePathKey.caseFolded(entry.relativePath()))) {
                    throw failure("Generated project archive contains duplicate normalized paths");
                }
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(ProjectEntry::relativePath));
        return List.copyOf(entries);
    }

    private ProjectEntry projectEntry(Path root, Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw failure("Generated project archive must not contain symbolic links");
            }
            if (attributes.isDirectory()) {
                return null;
            }
            if (!attributes.isRegularFile()) {
                throw failure("Generated project archive contains an unsupported file entry");
            }
            String relative = portableRelativePath(root.relativize(path));
            int unixMode = relative.equals("gradlew") || relative.equals("mvnw")
                    ? EXECUTABLE_FILE_MODE
                    : REGULAR_FILE_MODE;
            return new ProjectEntry(
                    relative, path, attributes.size(), StablePathIdentity.capture(path), unixMode);
        } catch (IOException exception) {
            throw GeneratorException.system(
                    ARTIFACT_PACKAGE_FAILED, STAGE, "Generated project file metadata could not be read", exception);
        }
    }

    private String portableRelativePath(Path relative) {
        if (relative.isAbsolute() || relative.getNameCount() == 0) {
            throw failure("Generated project archive contains an invalid relative path");
        }
        StringBuilder portable = new StringBuilder();
        for (Path component : relative) {
            String name = component.toString();
            if (name.isBlank() || name.equals(".") || name.equals("..")
                    || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                    || looksLikeWindowsAbsolutePath(name)) {
                throw failure("Generated project archive contains a non-portable path");
            }
            if (!portable.isEmpty()) {
                portable.append('/');
            }
            portable.append(name);
        }
        return portable.toString();
    }

    private boolean looksLikeWindowsAbsolutePath(String value) {
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }

    private void createArchive(
            List<ProjectEntry> entries,
            Path staging,
            SourceSnapshot expectedSource) throws IOException {
        try (OutputStream file = Files.newOutputStream(staging, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS);
                var bounded = new BoundedOutputStream(file, maxArchiveBytes);
                var zip = new ZipOutputStream(bounded)) {
            for (ProjectEntry entry : entries) {
                ZipEntry zipEntry = new ZipEntry(entry.relativePath());
                zipEntry.setTimeLocal(DOS_EPOCH_UTC);
                zip.putNextEntry(zipEntry);
                copyEntry(entry, zip, expectedSource == null
                        ? null : expectedSource.entries().get(entry.relativePath()));
                zip.closeEntry();
            }
        } catch (ArchiveSizeLimitException exception) {
            throw failure("Generated project archive exceeds the classic ZIP offset bound");
        }
    }

    private void copyEntry(
            ProjectEntry entry,
            ZipOutputStream zip,
            EntryFingerprint expectedSource) throws IOException {
        verifyProjectEntry(entry);
        if (expectedSource != null && expectedSource.rawBytes() != entry.size()) {
            throw failure("Generated project source content changed after checksum calculation");
        }
        MessageDigest sourceDigest = expectedSource == null ? null : sha256();
        long total = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(entry.source(), READ, NOFOLLOW_LINKS)) {
            while (true) {
                int requested = (int) Math.min(buffer.length, entry.size() - total + 1);
                int read = input.read(buffer, 0, requested);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                if (read > entry.size() - total) {
                    throw failure("Generated project entry changed while it was packaged");
                }
                zip.write(buffer, 0, read);
                if (sourceDigest != null) {
                    sourceDigest.update(buffer, 0, read);
                }
                total += read;
            }
        }
        if (total != entry.size()) {
            throw failure("Generated project entry changed while it was packaged");
        }
        if (sourceDigest != null
                && !expectedSource.checksum().equals(HexFormat.of().formatHex(sourceDigest.digest()))) {
            throw failure("Generated project source content changed after checksum calculation");
        }
        verifyProjectEntry(entry);
    }

    private void validateSourceContract(
            List<ProjectEntry> entries,
            SourceSnapshot expectedSource) {
        if (expectedSource == null) {
            return;
        }
        Set<String> observedSourcePaths = new HashSet<>();
        boolean manifestPresent = false;
        boolean reportPresent = false;
        for (ProjectEntry entry : entries) {
            String path = entry.relativePath();
            if (path.equals("GENERATION_MANIFEST.json")) {
                manifestPresent = true;
                continue;
            }
            if (path.equals("VALIDATION_REPORT.json")) {
                reportPresent = true;
                continue;
            }
            if (!SourceTreeChecksum.sourceIncluded(path)) {
                throw failure("Generated project archive contains excluded process output");
            }
            EntryFingerprint fingerprint = expectedSource.entries().get(path);
            if (fingerprint == null || fingerprint.rawBytes() != entry.size()) {
                throw failure("Generated project source paths changed after checksum calculation");
            }
            observedSourcePaths.add(path);
        }
        if (!manifestPresent || !reportPresent
                || !observedSourcePaths.equals(expectedSource.entries().keySet())) {
            throw failure("Generated project source paths changed after checksum calculation");
        }
    }

    private void verifyProjectEntry(ProjectEntry entry) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(entry.source(), BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || attributes.size() != entry.size()
                || !entry.identity().matches(entry.source())) {
            throw failure("Generated project entry changed while it was packaged");
        }
    }

    private byte[] readBoundedArchive(Path archive, StablePathIdentity expectedIdentity) throws IOException {
        verifyIdentity(archive, expectedIdentity);
        long size = Files.size(archive);
        if (size < 0 || size > maxArchiveBytes || size > Integer.MAX_VALUE) {
            throw failure("Generated project archive exceeds the classic ZIP offset bound");
        }
        byte[] bytes = new byte[Math.toIntExact(size)];
        int offset = 0;
        try (InputStream input = Files.newInputStream(archive, READ, NOFOLLOW_LINKS)) {
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    throw failure("Generated project archive changed while it was read");
                }
                if (read > 0) {
                    offset += read;
                }
            }
            if (input.read() >= 0) {
                throw failure("Generated project archive changed while it was read");
            }
        }
        verifyIdentity(archive, expectedIdentity);
        return bytes;
    }

    private boolean hasExactBytes(Path path, StablePathIdentity expectedIdentity, byte[] expected) throws IOException {
        verifyIdentity(path, expectedIdentity);
        if (Files.size(path) != expected.length) {
            return false;
        }
        MessageDigest expectedDigest = sha256();
        expectedDigest.update(expected);
        MessageDigest observedDigest = sha256();
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0;
        try (InputStream input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                if (read == 0) {
                    continue;
                }
                if (read > expected.length - total) {
                    return false;
                }
                observedDigest.update(buffer, 0, read);
                total += read;
            }
        }
        verifyIdentity(path, expectedIdentity);
        return total == expected.length && MessageDigest.isEqual(expectedDigest.digest(), observedDigest.digest());
    }

    private byte[] applyUnixModes(byte[] archive, List<ProjectEntry> entries) {
        Map<String, Integer> modes = new HashMap<>();
        entries.forEach(entry -> modes.put(entry.relativePath(), entry.unixMode()));
        int end = findEndOfCentralDirectory(archive);
        int entryCount = unsignedShort(archive, end + 10);
        int offset = unsignedInt(archive, end + 16);
        for (int index = 0; index < entryCount; index++) {
            if (unsignedInt(archive, offset) != CENTRAL_DIRECTORY_SIGNATURE) {
                throw failure("Generated archive central directory is invalid");
            }
            int nameLength = unsignedShort(archive, offset + 28);
            int extraLength = unsignedShort(archive, offset + 30);
            int commentLength = unsignedShort(archive, offset + 32);
            String name = new String(archive, offset + CENTRAL_DIRECTORY_FIXED_SIZE, nameLength, UTF_8);
            Integer mode = modes.get(name);
            if (mode == null) {
                throw failure("Generated archive contains an unexpected central directory entry");
            }
            archive[offset + 5] = 3;
            writeUnsignedInt(archive, offset + 38, mode << 16);
            offset += CENTRAL_DIRECTORY_FIXED_SIZE + nameLength + extraLength + commentLength;
        }
        return archive;
    }

    void verifyClassicZip(byte[] archive) {
        int end = findEndOfCentralDirectory(archive);
        if (end >= 20 && unsignedInt(archive, end - 20) == ZIP64_LOCATOR_SIGNATURE) {
            rejectZip64Locator(archive, end - 20);
        }
        int disk = unsignedShort(archive, end + 4);
        int centralDisk = unsignedShort(archive, end + 6);
        int diskEntryCount = unsignedShort(archive, end + 8);
        int entryCount = unsignedShort(archive, end + 10);
        if (disk != 0 || centralDisk != 0 || diskEntryCount != entryCount) {
            throw failure("Multi-disk ZIP archives are not supported by the P0 packager");
        }
        if (diskEntryCount == 0xffff || entryCount == 0xffff
                || unsignedIntLong(archive, end + 12) == 0xffff_ffffL
                || unsignedIntLong(archive, end + 16) == 0xffff_ffffL) {
            throw failure("ZIP64 sentinel is not supported by the P0 packager");
        }
        if (entryCount > maxEntries) {
            throw failure("Generated archive has too many entries for the P0 classic ZIP contract");
        }
        long centralSize = unsignedIntLong(archive, end + 12);
        long centralOffset = unsignedIntLong(archive, end + 16);
        if (centralOffset > maxArchiveBytes
                || centralSize > maxArchiveBytes
                || centralOffset + centralSize != end) {
            throw failure("Generated archive exceeds the supported classic ZIP structure");
        }
        int classicCentralOffset = Math.toIntExact(centralOffset);
        List<CentralDirectoryEntry> entries = parseClassicCentralDirectory(
                archive, entryCount, classicCentralOffset, Math.toIntExact(centralSize));
        verifyLocalFileRecords(archive, entries, classicCentralOffset);
    }

    private List<CentralDirectoryEntry> parseClassicCentralDirectory(
            byte[] archive, int entryCount, int offset, int centralSize) {
        int expectedEnd = offset + centralSize;
        List<CentralDirectoryEntry> entries = new ArrayList<>(entryCount);
        long totalUncompressedSize = 0;
        for (int index = 0; index < entryCount; index++) {
            if (!fits(offset, CENTRAL_DIRECTORY_FIXED_SIZE, expectedEnd)
                    || unsignedInt(archive, offset) != CENTRAL_DIRECTORY_SIGNATURE) {
                throw failure("Generated archive central directory is invalid");
            }
            int flags = unsignedShort(archive, offset + 8);
            int method = unsignedShort(archive, offset + 10);
            long crc = unsignedIntLong(archive, offset + 16);
            long compressedSize = unsignedIntLong(archive, offset + 20);
            long uncompressedSize = unsignedIntLong(archive, offset + 24);
            int nameLength = unsignedShort(archive, offset + 28);
            int extraLength = unsignedShort(archive, offset + 30);
            int commentLength = unsignedShort(archive, offset + 32);
            int diskStart = unsignedShort(archive, offset + 34);
            long localHeaderOffset = unsignedIntLong(archive, offset + 42);
            long recordLength = (long) CENTRAL_DIRECTORY_FIXED_SIZE + nameLength + extraLength + commentLength;
            if (nameLength == 0 || !fits(offset, recordLength, expectedEnd)) {
                throw failure("Generated archive central directory entry exceeds its bounds");
            }
            if (method != DEFLATED_METHOD || (flags & ~(DATA_DESCRIPTOR_FLAG | UTF_8_FLAG)) != 0) {
                throw failure("Generated archive uses unsupported compression flags or method");
            }
            if (compressedSize == 0xffff_ffffL || uncompressedSize == 0xffff_ffffL
                    || localHeaderOffset == 0xffff_ffffL || diskStart == 0xffff) {
                throw failure("ZIP64 central directory fields are not supported by the P0 packager");
            }
            if (diskStart != 0 || compressedSize > maxArchiveBytes || uncompressedSize > maxEntryBytes
                    || localHeaderOffset >= offset) {
                throw failure("Generated archive central directory metadata is invalid");
            }
            totalUncompressedSize += uncompressedSize;
            if (totalUncompressedSize > maxTotalBytes) {
                throw failure("Generated archive exceeds the P0 classic ZIP content bound");
            }
            int nameStart = offset + CENTRAL_DIRECTORY_FIXED_SIZE;
            int extraStart = offset + CENTRAL_DIRECTORY_FIXED_SIZE + nameLength;
            validateClassicExtraFields(archive, extraStart, extraLength, expectedEnd);
            entries.add(new CentralDirectoryEntry(
                    flags,
                    method,
                    crc,
                    compressedSize,
                    uncompressedSize,
                    Math.toIntExact(localHeaderOffset),
                    nameStart,
                    nameLength));
            offset += Math.toIntExact(recordLength);
        }
        if (offset != expectedEnd) {
            throw failure("Generated archive central directory size is inconsistent");
        }
        return List.copyOf(entries);
    }

    private void verifyLocalFileRecords(
            byte[] archive, List<CentralDirectoryEntry> entries, int centralOffset) {
        int cursor = 0;
        for (CentralDirectoryEntry entry : entries) {
            if (entry.localHeaderOffset() != cursor
                    || !fits(cursor, LOCAL_FILE_HEADER_FIXED_SIZE, centralOffset)
                    || unsignedInt(archive, cursor) != LOCAL_FILE_HEADER_SIGNATURE) {
                throw failure("Generated archive local file records are missing, duplicated, or misaligned");
            }
            int flags = unsignedShort(archive, cursor + 6);
            int method = unsignedShort(archive, cursor + 8);
            long localCrc = unsignedIntLong(archive, cursor + 14);
            long localCompressedSize = unsignedIntLong(archive, cursor + 18);
            long localUncompressedSize = unsignedIntLong(archive, cursor + 22);
            int nameLength = unsignedShort(archive, cursor + 26);
            int extraLength = unsignedShort(archive, cursor + 28);
            long headerLength = (long) LOCAL_FILE_HEADER_FIXED_SIZE + nameLength + extraLength;
            if (!fits(cursor, headerLength, centralOffset)) {
                throw failure("Generated archive local file header exceeds its bounds");
            }
            int nameStart = cursor + LOCAL_FILE_HEADER_FIXED_SIZE;
            if (flags != entry.flags() || method != entry.method() || nameLength != entry.nameLength()
                    || !Arrays.equals(
                            archive,
                            nameStart,
                            nameStart + nameLength,
                            archive,
                            entry.nameOffset(),
                            entry.nameOffset() + entry.nameLength())) {
                throw failure("Generated archive local and central file metadata differs");
            }
            validateClassicExtraFields(archive, nameStart + nameLength, extraLength, centralOffset);
            boolean hasDescriptor = (flags & DATA_DESCRIPTOR_FLAG) != 0;
            if (hasDescriptor) {
                if (localCrc != 0 || localCompressedSize != 0 || localUncompressedSize != 0) {
                    throw failure("Generated archive descriptor-backed local sizes are invalid");
                }
            } else if (localCrc != entry.crc()
                    || localCompressedSize != entry.compressedSize()
                    || localUncompressedSize != entry.uncompressedSize()) {
                throw failure("Generated archive local and central size metadata differs");
            }
            long payloadStart = (long) cursor + headerLength;
            if (!fits(payloadStart, entry.compressedSize(), centralOffset)) {
                throw failure("Generated archive compressed payload exceeds its bounds");
            }
            cursor = Math.toIntExact(payloadStart + entry.compressedSize());
            if (hasDescriptor) {
                cursor = verifyDataDescriptor(archive, cursor, centralOffset, entry);
            }
        }
        if (cursor != centralOffset) {
            throw failure("Generated archive local file records do not reach the central directory");
        }
    }

    private int verifyDataDescriptor(
            byte[] archive, int offset, int centralOffset, CentralDirectoryEntry entry) {
        boolean hasSignature = fits(offset, 4, centralOffset)
                && unsignedInt(archive, offset) == DATA_DESCRIPTOR_SIGNATURE;
        int valuesOffset = offset + (hasSignature ? 4 : 0);
        if (!fits(valuesOffset, 12, centralOffset)) {
            throw failure("Generated archive data descriptor is truncated");
        }
        long crc = unsignedIntLong(archive, valuesOffset);
        long compressedSize = unsignedIntLong(archive, valuesOffset + 4);
        long uncompressedSize = unsignedIntLong(archive, valuesOffset + 8);
        if (crc != entry.crc()
                || compressedSize != entry.compressedSize()
                || uncompressedSize != entry.uncompressedSize()) {
            throw failure("Generated archive data descriptor differs from the central directory");
        }
        return valuesOffset + 12;
    }

    private void validateClassicExtraFields(byte[] archive, int offset, int length, int limit) {
        if (!fits(offset, length, limit)) {
            throw failure("Generated archive extra data exceeds its bounds");
        }
        int end = Math.toIntExact((long) offset + length);
        for (int cursor = offset; cursor < end; ) {
            if (!fits(cursor, 4, end)) {
                throw failure("Generated archive extra field is truncated");
            }
            int headerId = unsignedShort(archive, cursor);
            int fieldLength = unsignedShort(archive, cursor + 2);
            if (headerId == 0x0001) {
                throw failure("ZIP64 extra fields are not supported by the P0 packager");
            }
            if (!fits(cursor + 4L, fieldLength, end)) {
                throw failure("Generated archive extra field exceeds its entry");
            }
            cursor += 4 + fieldLength;
        }
    }

    private boolean fits(long offset, long length, int limit) {
        return offset >= 0 && length >= 0 && offset <= limit && length <= (long) limit - offset;
    }

    private void validateClassicBounds(List<ProjectEntry> entries) {
        if (entries.size() > maxEntries) {
            throw failure("Generated project has too many entries for the P0 classic ZIP contract");
        }
        long total = 0;
        for (ProjectEntry entry : entries) {
            int nameLength = entry.relativePath().getBytes(UTF_8).length;
            if (nameLength >= 0xffff || entry.size() < 0 || entry.size() > maxEntryBytes) {
                throw failure("Generated project entry exceeds the P0 classic ZIP contract");
            }
            if (entry.size() > maxTotalBytes - total) {
                throw failure("Generated project exceeds the P0 classic ZIP content bound");
            }
            total += entry.size();
        }
    }

    private void rejectZip64Locator(byte[] archive, int locator) {
        long disk = unsignedIntLong(archive, locator + 4);
        long totalDisks = unsignedIntLong(archive, locator + 16);
        long zip64EndOffset = boundedUnsignedLong(archive, locator + 8);
        if (disk != 0 || totalDisks != 1 || zip64EndOffset < 0 || zip64EndOffset + 12 > locator) {
            throw failure("ZIP64 locator is invalid and unsupported by the P0 packager");
        }
        int zip64End = Math.toIntExact(zip64EndOffset);
        if (unsignedInt(archive, zip64End) != ZIP64_END_SIGNATURE) {
            throw failure("ZIP64 locator does not reference a ZIP64 end record");
        }
        long recordSize = boundedUnsignedLong(archive, zip64End + 4);
        if (recordSize < 44 || recordSize > Integer.MAX_VALUE
                || zip64EndOffset + 12 + recordSize != locator) {
            throw failure("ZIP64 end record is invalid and unsupported by the P0 packager");
        }
        throw failure("ZIP64 locator is not supported by the P0 packager");
    }

    private int findEndOfCentralDirectory(byte[] archive) {
        int minimum = Math.max(0, archive.length - 65_557);
        for (int index = archive.length - 22; index >= minimum; index--) {
            if (unsignedInt(archive, index) == END_OF_CENTRAL_DIRECTORY_SIGNATURE
                    && (long) index + 22 + unsignedShort(archive, index + 20) == archive.length) {
                return index;
            }
        }
        throw failure("Generated archive end record is missing");
    }

    private int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private int unsignedInt(byte[] bytes, int offset) {
        return unsignedShort(bytes, offset) | unsignedShort(bytes, offset + 2) << 16;
    }

    private long unsignedIntLong(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(unsignedInt(bytes, offset));
    }

    private long boundedUnsignedLong(byte[] bytes, int offset) {
        long low = unsignedIntLong(bytes, offset);
        long high = unsignedIntLong(bytes, offset + 4);
        return high == 0 ? low : -1;
    }

    private void writeUnsignedInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw GeneratorException.system(
                    ARTIFACT_PACKAGE_FAILED, STAGE, "SHA-256 is unavailable for archive verification", exception);
        }
    }

    private ParentIdentity parentIdentity(Path parent) {
        try {
            return new ParentIdentity(StablePathIdentity.capture(parent));
        } catch (IOException exception) {
            throw GeneratorException.system(
                    ARTIFACT_PACKAGE_FAILED, STAGE, "Archive parent identity could not be recorded", exception);
        }
    }

    private void verifyParentIdentity(Path parent, ParentIdentity expected) {
        try {
            if (!expected.identity().matches(parent)) {
                throw failure("Archive output parent identity changed");
            }
        } catch (IOException exception) {
            throw GeneratorException.system(
                    ARTIFACT_PACKAGE_FAILED, STAGE, "Archive parent identity could not be verified", exception);
        }
    }

    private void verifyIdentity(Path path, StablePathIdentity expected) {
        try {
            if (!expected.matches(path)) {
                throw failure("Owned archive path identity changed");
            }
        } catch (GeneratorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw GeneratorException.system(
                    ARTIFACT_PACKAGE_FAILED, STAGE, "Archive path identity could not be read", exception);
        }
    }

    private void verifyObjectIdentity(Path path, StablePathIdentity expected) {
        try {
            if (!expected.matchesObject(path)) {
                throw failure("Owned archive path identity changed");
            }
        } catch (GeneratorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw GeneratorException.system(
                    ARTIFACT_PACKAGE_FAILED, STAGE, "Archive path identity could not be read", exception);
        }
    }

    private void deleteOwnedFile(
            Path path,
            Path parent,
            ParentIdentity parentIdentity,
            StablePathIdentity expectedIdentity) {
        verifyParentIdentity(parent, parentIdentity);
        verifyIdentity(path, expectedIdentity);
        try {
            Files.delete(path);
        } catch (IOException exception) {
            throw GeneratorException.system(
                    ARTIFACT_PACKAGE_FAILED, STAGE, "Owned archive path could not be cleaned", exception);
        }
    }

    private void cleanupOwnedFile(
            GeneratorException failure,
            Path path,
            Path parent,
            ParentIdentity parentIdentity,
            StablePathIdentity expectedIdentity) {
        if (path == null || expectedIdentity == null) {
            return;
        }
        try {
            deleteOwnedFile(path, parent, parentIdentity, expectedIdentity);
        } catch (GeneratorException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private GeneratorException failure(String message) {
        return GeneratorException.user(ARTIFACT_PACKAGE_FAILED, STAGE, message);
    }

    @FunctionalInterface
    interface PublicationHook {
        void beforeTargetReservation(Path staging, Path target) throws IOException;
    }

    private record CentralDirectoryEntry(
            int flags,
            int method,
            long crc,
            long compressedSize,
            long uncompressedSize,
            int localHeaderOffset,
            int nameOffset,
            int nameLength) {}

    private record ProjectEntry(
            String relativePath,
            Path source,
            long size,
            StablePathIdentity identity,
            int unixMode) {}

    private record ParentIdentity(StablePathIdentity identity) {}

    private static final class BoundedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long maxBytes;
        private long written;

        private BoundedOutputStream(OutputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            delegate.write(value);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            delegate.write(bytes, offset, length);
            written += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        private void requireCapacity(int length) throws ArchiveSizeLimitException {
            if (length > maxBytes - written) {
                throw new ArchiveSizeLimitException();
            }
        }
    }

    private static final class ArchiveSizeLimitException extends IOException {}
}
