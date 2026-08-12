package io.gen2spring.mcp.app.web.api;

import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class SpecificationStore implements AutoCloseable {
    static final int MAX_SPECIFICATION_BYTES = 10 * 1024 * 1024;
    private static final int MAX_RETAINED_SPECIFICATIONS = 8;
    private static final Pattern SAFE_NAME = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?:yaml|yml|json)");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-f0-9]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final SpecificationAnalyzer analyzer;
    private final BoundedBodyReader bodyReader = new BoundedBodyReader(MAX_SPECIFICATION_BYTES);
    private final Map<String, StoredEntry> specifications = new LinkedHashMap<>();
    private long accessSequence;
    private boolean closed;

    public SpecificationStore(Path temporaryParent, SpecificationAnalyzer analyzer) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        try {
            Path parent = Objects.requireNonNull(temporaryParent, "temporaryParent")
                    .toAbsolutePath().normalize();
            Files.createDirectories(parent);
            root = Files.createTempDirectory(parent, "gen2spring-web-")
                    .toAbsolutePath().normalize();
            setPermissions(root, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (IOException exception) {
            throw WebErrorMapper.failure(
                    500, "WORKSPACE_CREATE_FAILED", "WEB_START", "The private workspace could not be created");
        }
    }

    synchronized StoredSpecification store(String name, InputStream input) {
        requireOpen();
        if (name == null || !SAFE_NAME.matcher(name).matches()
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw WebErrorMapper.failure(400, "SPECIFICATION_NAME_INVALID", "SPEC_STORE",
                    "The specification name is invalid");
        }
        byte[] bytes = bodyReader.read(input);
        String identifier = identifier();
        Path path = root.resolve(identifier + suffix(name)).normalize();
        if (!path.getParent().equals(root)) {
            throw new IllegalArgumentException("Specification name is invalid");
        }

        boolean published = false;
        try {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            setPermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            BasicFileAttributes attributes = attributes(path);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
                throw new IOException("Stored specification is not a regular file");
            }
            SpecificationAnalyzer.AnalysisResult analysis = analyzer.analyze(path, MAX_SPECIFICATION_BYTES);
            StoredSpecification stored = new StoredSpecification(
                    identifier, path, attributes.fileKey(), attributes.size(), analysis);
            evictForCapacity();
            specifications.put(identifier, new StoredEntry(stored, ++accessSequence));
            published = true;
            return stored;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw WebErrorMapper.failure(
                    500, "SPECIFICATION_STORE_FAILED", "SPEC_STORE",
                    "The specification could not be stored");
        } finally {
            if (!published) {
                deleteOwned(path);
            }
        }
    }

    synchronized StoredSpecification require(String identifier) {
        requireOpen();
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw WebErrorMapper.failure(404, "SPECIFICATION_NOT_FOUND", "SPEC_STORE",
                    "The specification was not found");
        }
        StoredEntry entry = specifications.get(identifier);
        if (entry == null || !stable(entry.stored)) {
            throw WebErrorMapper.failure(404, "SPECIFICATION_NOT_FOUND", "SPEC_STORE",
                    "The specification was not found");
        }
        entry.lastAccess = ++accessSequence;
        return entry.stored;
    }

    synchronized StoredSpecification retain(String identifier) {
        StoredSpecification stored = require(identifier);
        specifications.get(identifier).pins++;
        return stored;
    }

    synchronized void release(String identifier) {
        StoredEntry entry = specifications.get(identifier);
        if (entry != null && entry.pins > 0) {
            entry.pins--;
            entry.lastAccess = ++accessSequence;
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        specifications.values().forEach(entry -> deleteOwned(entry.stored.path()));
        specifications.clear();
        deleteOwned(root);
    }

    private boolean stable(StoredSpecification stored) {
        try {
            if (!stored.path().getParent().equals(root)
                    || Files.isSymbolicLink(stored.path())) {
                return false;
            }
            BasicFileAttributes current = attributes(stored.path());
            return current.isRegularFile()
                    && current.size() == stored.size()
                    && Objects.equals(current.fileKey(), stored.fileKey());
        } catch (IOException exception) {
            return false;
        }
    }

    private BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private String identifier() {
        byte[] bytes = new byte[32];
        String candidate;
        do {
            RANDOM.nextBytes(bytes);
            candidate = java.util.HexFormat.of().formatHex(bytes);
        } while (specifications.containsKey(candidate));
        return candidate;
    }

    private void evictForCapacity() {
        if (specifications.size() < MAX_RETAINED_SPECIFICATIONS) {
            return;
        }
        Map.Entry<String, StoredEntry> oldest = specifications.entrySet().stream()
                .filter(entry -> entry.getValue().pins == 0)
                .min(java.util.Comparator.comparingLong(entry -> entry.getValue().lastAccess))
                .orElseThrow(() -> WebErrorMapper.failure(
                        429, "SPECIFICATION_CAPACITY_EXCEEDED", "SPEC_STORE",
                        "The specification capacity is exhausted"));
        specifications.remove(oldest.getKey());
        deleteOwned(oldest.getValue().stored.path());
    }

    private String suffix(String name) {
        return name.substring(name.lastIndexOf('.'));
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Specification store is closed");
        }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are unavailable on this filesystem.
        }
    }

    private void deleteOwned(Path path) {
        if (path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.equals(root) && !normalized.getParent().equals(root)) {
            return;
        }
        try {
            Files.deleteIfExists(normalized);
        } catch (IOException ignored) {
            // Best-effort cleanup is bounded to the owned private workspace.
        }
    }

    record StoredSpecification(
            String id,
            Path path,
            Object fileKey,
            long size,
            SpecificationAnalyzer.AnalysisResult analysis) {
        StoredSpecification {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(analysis, "analysis");
        }
    }

    private static final class StoredEntry {
        private final StoredSpecification stored;
        private long lastAccess;
        private int pins;

        private StoredEntry(StoredSpecification stored, long lastAccess) {
            this.stored = stored;
            this.lastAccess = lastAccess;
        }
    }
}
