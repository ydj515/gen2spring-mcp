package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.READ;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectFiles;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SourceTreeChecksum {
    private static final String STAGE = "SOURCE_CHECKSUM";
    private static final String MANIFEST_PATH = "GENERATION_MANIFEST.json";
    private static final String REPORT_PATH = "VALIDATION_REPORT.json";
    private static final String PROCESS_LOG_DIRECTORY = "process-logs";
    private static final int DEFAULT_MAX_ENTRIES = 10_000;
    private static final long DEFAULT_MAX_ENTRY_BYTES = 100L * 1024 * 1024;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final int BUFFER_BYTES = 8 * 1024;

    private final int maxEntries;
    private final long maxEntryBytes;
    private final long maxTotalBytes;

    public SourceTreeChecksum() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    SourceTreeChecksum(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
        if (maxEntries <= 0 || maxEntryBytes <= 0 || maxTotalBytes <= 0) {
            throw new IllegalArgumentException("Checksum bounds must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public String calculate(GeneratedProjectFiles projectFiles) {
        if (projectFiles == null || projectFiles.files() == null) {
            throw failure("Generated project files are required", null);
        }
        List<Content> content = new ArrayList<>();
        Set<String> portablePathKeys = new HashSet<>();
        long totalBytes = 0;
        for (Map.Entry<String, byte[]> entry : projectFiles.files().entrySet()) {
            String path = normalizedRelativePath(entry.getKey());
            if (!portablePathKeys.add(PortablePathKey.caseFolded(path))) {
                throw failure("Generated project contains duplicate checksum paths", null);
            }
            if (excluded(path)) {
                continue;
            }
            if (entry.getValue() == null) {
                throw failure("Generated project file content is required", null);
            }
            requireEntryCount(content.size() + 1);
            requireEntrySize(entry.getValue().length);
            totalBytes = checkedTotal(totalBytes, entry.getValue().length);
            content.add(new Content(path, entry.getValue()));
        }
        return calculateMemoryContent(content);
    }

    public String calculate(Path projectRoot) {
        return snapshot(projectRoot).checksum();
    }

    SourceSnapshot snapshot(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot, NOFOLLOW_LINKS)) {
            throw failure("Generated project root is not a directory", null);
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        List<FileContent> content = new ArrayList<>();
        Set<String> portablePathKeys = new HashSet<>();
        long totalBytes = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (path.equals(root) || Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                    throw failure("Generated project contains an unsupported file entry", null);
                }
                String relative = normalizedRelativePath(root.relativize(path));
                if (!portablePathKeys.add(PortablePathKey.caseFolded(relative))) {
                    throw failure("Generated project contains duplicate checksum paths", null);
                }
                if (excluded(relative)) {
                    continue;
                }
                requireEntryCount(content.size() + 1);
                requireEntrySize(attributes.size());
                totalBytes = checkedTotal(totalBytes, attributes.size());
                content.add(new FileContent(
                        relative, path, attributes.size(), StablePathIdentity.capture(path)));
            }
        } catch (GeneratorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure("Generated project checksum could not be calculated", exception);
        }
        return calculateDiskContent(content);
    }

    private String calculateMemoryContent(List<Content> content) {
        content.sort(Comparator.comparing(Content::path));
        MessageDigest digest = sha256();
        for (Content item : content) {
            byte[] path = item.path().getBytes(UTF_8);
            updateLength(digest, path.length);
            digest.update(path);
            updateLength(digest, normalizedLength(item.bytes()));
            updateNormalized(digest, item.bytes());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private SourceSnapshot calculateDiskContent(List<FileContent> content) {
        content.sort(Comparator.comparing(FileContent::path));
        MessageDigest digest = sha256();
        Map<String, EntryFingerprint> fingerprints = new LinkedHashMap<>();
        for (FileContent item : content) {
            byte[] path = item.path().getBytes(UTF_8);
            updateLength(digest, path.length);
            digest.update(path);
            try {
                NormalizedScan first = scan(item, null);
                updateLength(digest, first.normalizedBytes());
                NormalizedScan second = scan(item, digest);
                if (!first.equals(second)) {
                    throw failure("Generated project file changed during checksum calculation", null);
                }
                fingerprints.put(item.path(), new EntryFingerprint(
                        item.rawBytes(), HexFormat.of().formatHex(second.rawChecksum())));
            } catch (GeneratorException exception) {
                throw exception;
            } catch (IOException exception) {
                throw failure("Generated project checksum could not be calculated", exception);
            }
        }
        return new SourceSnapshot(
                HexFormat.of().formatHex(digest.digest()),
                fingerprints);
    }

    private NormalizedScan scan(FileContent content, MessageDigest destination) throws IOException {
        verifyIdentity(content);
        MessageDigest verification = sha256();
        MessageDigest rawVerification = sha256();
        long rawBytes = 0;
        long normalizedBytes = 0;
        boolean pendingCarriageReturn = false;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(content.file(), READ, NOFOLLOW_LINKS)) {
            while (true) {
                int requested = (int) Math.min(buffer.length, content.rawBytes() - rawBytes + 1);
                int read = input.read(buffer, 0, requested);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                if (read > content.rawBytes() - rawBytes) {
                    throw failure("Generated project file changed during checksum calculation", null);
                }
                rawBytes += read;
                rawVerification.update(buffer, 0, read);
                for (int index = 0; index < read; index++) {
                    byte value = buffer[index];
                    if (pendingCarriageReturn) {
                        updateNormalizedByte(destination, verification, (byte) '\n');
                        normalizedBytes++;
                        pendingCarriageReturn = false;
                        if (value == '\n') {
                            continue;
                        }
                    }
                    if (value == '\r') {
                        pendingCarriageReturn = true;
                    } else {
                        updateNormalizedByte(destination, verification, value);
                        normalizedBytes++;
                    }
                }
            }
        }
        if (rawBytes != content.rawBytes()) {
            throw failure("Generated project file changed during checksum calculation", null);
        }
        if (pendingCarriageReturn) {
            updateNormalizedByte(destination, verification, (byte) '\n');
            normalizedBytes++;
        }
        verifyIdentity(content);
        return new NormalizedScan(normalizedBytes, verification.digest(), rawVerification.digest());
    }

    private void verifyIdentity(FileContent content) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(content.file(), BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || attributes.size() != content.rawBytes()
                || !content.identity().matches(content.file())) {
            throw failure("Generated project file changed during checksum calculation", null);
        }
    }

    private void updateNormalizedByte(MessageDigest destination, MessageDigest verification, byte value) {
        if (destination != null) {
            destination.update(value);
        }
        verification.update(value);
    }

    private long normalizedLength(byte[] bytes) {
        long length = 0;
        for (int index = 0; index < bytes.length; index++) {
            length++;
            if (bytes[index] == '\r' && index + 1 < bytes.length && bytes[index + 1] == '\n') {
                index++;
            }
        }
        return length;
    }

    private void updateNormalized(MessageDigest digest, byte[] bytes) {
        for (int index = 0; index < bytes.length; index++) {
            byte value = bytes[index];
            if (value == '\r') {
                if (index + 1 < bytes.length && bytes[index + 1] == '\n') {
                    index++;
                }
                digest.update((byte) '\n');
            } else {
                digest.update(value);
            }
        }
    }

    private void updateLength(MessageDigest digest, long length) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(length).array());
    }

    private void requireEntryCount(int count) {
        if (count > maxEntries) {
            throw failure("Generated project checksum has too many entries for the P0 bound", null);
        }
    }

    private void requireEntrySize(long size) {
        if (size < 0 || size > maxEntryBytes) {
            throw failure("Generated project checksum entry exceeds the P0 size bound", null);
        }
    }

    private long checkedTotal(long total, long size) {
        if (size > maxTotalBytes - total) {
            throw failure("Generated project checksum content exceeds the P0 size bound", null);
        }
        return total + size;
    }

    private String normalizedRelativePath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || looksLikeWindowsAbsolutePath(value)) {
            throw failure("Generated project contains an invalid checksum path", null);
        }
        try {
            return normalizedRelativePath(Path.of(value));
        } catch (InvalidPathException exception) {
            throw failure("Generated project contains an invalid checksum path", exception);
        }
    }

    private String normalizedRelativePath(Path value) {
        Path path = value.normalize();
        if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")) {
            throw failure("Generated project contains an unsafe checksum path", null);
        }
        StringBuilder portable = new StringBuilder();
        for (Path component : path) {
            String name = component.toString();
            if (name.isBlank() || name.equals(".") || name.equals("..")
                    || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                    || looksLikeWindowsAbsolutePath(name)) {
                throw failure("Generated project contains an invalid checksum path", null);
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

    static boolean sourceIncluded(String path) {
        return !excluded(path);
    }

    private static boolean excluded(String path) {
        return path.equals(MANIFEST_PATH)
                || path.equals(REPORT_PATH)
                || path.equals(PROCESS_LOG_DIRECTORY)
                || path.startsWith(PROCESS_LOG_DIRECTORY + "/");
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw failure("SHA-256 is unavailable", exception);
        }
    }

    private GeneratorException failure(String message, Throwable cause) {
        return cause == null
                ? GeneratorException.user(SOURCE_GENERATION_FAILED, STAGE, message)
                : GeneratorException.system(SOURCE_GENERATION_FAILED, STAGE, message, cause);
    }

    private record Content(String path, byte[] bytes) {}

    private record FileContent(String path, Path file, long rawBytes, StablePathIdentity identity) {}

    record SourceSnapshot(String checksum, Map<String, EntryFingerprint> entries) {
        SourceSnapshot {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
    }

    record EntryFingerprint(long rawBytes, String checksum) {}

    private record NormalizedScan(long normalizedBytes, byte[] checksum, byte[] rawChecksum) {
        @Override
        public boolean equals(Object other) {
            return other instanceof NormalizedScan scan
                    && normalizedBytes == scan.normalizedBytes
                    && Arrays.equals(checksum, scan.checksum)
                    && Arrays.equals(rawChecksum, scan.rawChecksum);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(normalizedBytes) * 31 + Arrays.hashCode(checksum);
            return result * 31 + Arrays.hashCode(rawChecksum);
        }
    }
}
