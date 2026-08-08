package io.gen2spring.mcp.cli;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class LocalPathBoundary {
    private static final Pattern SAFE_SUFFIX = Pattern.compile("\\.[A-Za-z0-9]{1,8}");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            Set.copyOf(PosixFilePermissions.fromString("rwx------"));
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            Set.copyOf(PosixFilePermissions.fromString("rw-------"));

    RegularFile regularFile(Path supplied, String label) {
        if (supplied == null) {
            throw failure(Reason.INVALID, label + " path is required", null);
        }
        Path path = supplied.toAbsolutePath().normalize();
        verifyPhysical(path, false, label);
        Path parent = path.getParent();
        if (parent == null) {
            throw failure(Reason.INVALID, label + " parent is required", null);
        }
        BasicFileAttributes parentAttributes = attributes(parent, label + " parent");
        BasicFileAttributes fileAttributes = attributes(path, label);
        if (!parentAttributes.isDirectory() || !fileAttributes.isRegularFile()) {
            throw failure(Reason.INVALID, label + " must be a regular local file", null);
        }
        return new RegularFile(path, requiredFileKey(parentAttributes, label + " parent"),
                requiredFileKey(fileAttributes, label), label);
    }

    NewFile newFile(Path supplied, String label) {
        if (supplied == null) {
            throw failure(Reason.INVALID, label + " path is required", null);
        }
        Path path = supplied.toAbsolutePath().normalize();
        if (path.getFileName() == null) {
            throw failure(Reason.INVALID, label + " must name a new file or directory", null);
        }
        Path parent = path.getParent();
        if (parent == null) {
            throw failure(Reason.INVALID, label + " parent is required", null);
        }
        verifyPhysical(parent, false, label + " parent");
        BasicFileAttributes parentAttributes = attributes(parent, label + " parent");
        if (!parentAttributes.isDirectory()) {
            throw failure(Reason.INVALID, label + " parent must be a regular directory", null);
        }
        requireMissing(path, label);
        return new NewFile(path, requiredFileKey(parentAttributes, label + " parent"), label);
    }

    PrivateDirectory privateDirectory(NewFile target, String prefix, String label) {
        Objects.requireNonNull(target, "target");
        if (prefix == null || prefix.isBlank()) {
            throw failure(Reason.INVALID, label + " prefix is required", null);
        }
        target.verifyParentStable();
        Path parent = target.path().getParent();
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class, NOFOLLOW_LINKS) == null) {
            throw failure(Reason.INVALID, label + " requires owner-only POSIX permissions", null);
        }
        Path directory = null;
        Object directoryKey = null;
        try {
            directory = Files.createTempDirectory(parent, prefix,
                    PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS));
            PosixFileAttributes directoryAttributes = posixAttributes(directory, label);
            directoryKey = requiredFileKey(directoryAttributes, label);
            PrivateDirectory result = new PrivateDirectory(
                    directory, target.parentKey, directoryKey, label);
            result.verifyStable();
            return result;
        } catch (PathBoundaryException exception) {
            cleanupNewPrivateDirectory(directory, target.parentKey, directoryKey);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            cleanupNewPrivateDirectory(directory, target.parentKey, directoryKey);
            throw failure(Reason.IO, label + " could not be created safely", exception);
        }
    }

    VerifiedCopy verifiedCopy(RegularFile source, int maxBytes, String suffix) {
        Objects.requireNonNull(source, "source");
        if (suffix == null || !SAFE_SUFFIX.matcher(suffix).matches()) {
            throw failure(Reason.INVALID, "Specification extension is invalid", null);
        }
        byte[] bytes = source.readBounded(maxBytes);
        Path directory = null;
        Object directoryKey = null;
        try {
            Path configuredTemp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            Path physicalTemp = configuredTemp.toRealPath();
            if (!Files.isDirectory(physicalTemp, NOFOLLOW_LINKS) || Files.isSymbolicLink(physicalTemp)) {
                throw failure(Reason.INVALID, "Temporary input directory is unsafe", null);
            }
            directory = Files.createTempDirectory(physicalTemp, ".openapi-mcp-input-");
            BasicFileAttributes directoryAttributes = attributes(directory, "Temporary input directory");
            directoryKey = requiredFileKey(directoryAttributes, "Temporary input directory");
            Path copy = directory.resolve("source" + suffix);
            Files.write(copy, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS);
            RegularFile staged = regularFile(copy, "Temporary specification");
            if (!java.util.Arrays.equals(bytes, staged.readBounded(maxBytes))) {
                throw failure(Reason.IO, "Temporary specification verification failed", null);
            }
            return new VerifiedCopy(directory, directoryKey, staged);
        } catch (PathBoundaryException exception) {
            cleanupUnpublishedDirectory(directory, directoryKey);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            cleanupUnpublishedDirectory(directory, directoryKey);
            throw failure(Reason.IO, "Temporary specification could not be created", exception);
        }
    }

    final class RegularFile {
        private final Path path;
        private final Object parentKey;
        private final Object fileKey;
        private final String label;

        private RegularFile(Path path, Object parentKey, Object fileKey, String label) {
            this.path = path;
            this.parentKey = parentKey;
            this.fileKey = fileKey;
            this.label = label;
        }

        Path path() {
            return path;
        }

        void verifyStable() {
            verifyPhysical(path, false, label);
            BasicFileAttributes parentAttributes = attributes(path.getParent(), label + " parent");
            BasicFileAttributes fileAttributes = attributes(path, label);
            if (!parentAttributes.isDirectory() || !fileAttributes.isRegularFile()
                    || !parentKey.equals(requiredFileKey(parentAttributes, label + " parent"))
                    || !fileKey.equals(requiredFileKey(fileAttributes, label))) {
                throw failure(Reason.INVALID, label + " changed during use", null);
            }
        }

        byte[] readBounded(int maxBytes) {
            if (maxBytes < 0) {
                throw failure(Reason.INVALID, label + " size limit is invalid", null);
            }
            verifyStable();
            try (InputStream input = Files.newInputStream(path, READ, NOFOLLOW_LINKS)) {
                verifyStable();
                byte[] bytes = bounded(input, maxBytes, label);
                verifyStable();
                return bytes;
            } catch (PathBoundaryException exception) {
                throw exception;
            } catch (IOException exception) {
                throw failure(Reason.IO, label + " could not be read safely", exception);
            }
        }

        void requireSameFileIdentity(RegularFile other) {
            Objects.requireNonNull(other, "other");
            verifyStable();
            other.verifyStable();
            if (!fileKey.equals(other.fileKey)) {
                throw failure(Reason.INVALID, label + " identity does not match the published file", null);
            }
        }
    }

    final class NewFile {
        private final Path path;
        private final Object parentKey;
        private final String label;

        private NewFile(Path path, Object parentKey, String label) {
            this.path = path;
            this.parentKey = parentKey;
            this.label = label;
        }

        Path path() {
            return path;
        }

        void verifyAvailable() {
            verifyParentStable();
            requireMissing(path, label);
        }

        void verifyParentStable() {
            Path parent = path.getParent();
            verifyPhysical(parent, false, label + " parent");
            BasicFileAttributes parentAttributes = attributes(parent, label + " parent");
            if (!parentAttributes.isDirectory()
                    || !parentKey.equals(requiredFileKey(parentAttributes, label + " parent"))) {
                throw failure(Reason.INVALID, label + " parent changed during use", null);
            }
        }
    }

    final class PrivateDirectory {
        private final Path path;
        private final Object parentKey;
        private final Object directoryKey;
        private final String label;

        private PrivateDirectory(Path path, Object parentKey, Object directoryKey, String label) {
            this.path = path;
            this.parentKey = parentKey;
            this.directoryKey = directoryKey;
            this.label = label;
        }

        RegularFile createFile(String prefix, String suffix, String fileLabel) {
            verifyStable();
            Path file = null;
            RegularFile identity = null;
            try {
                file = Files.createTempFile(path, prefix, suffix,
                        PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS));
                identity = regularFile(file, fileLabel);
                PosixFileAttributes attributes = posixAttributes(file, fileLabel);
                if (!PRIVATE_FILE_PERMISSIONS.equals(attributes.permissions())) {
                    throw failure(Reason.INVALID, fileLabel + " must have owner-only permissions", null);
                }
                verifyStable();
                return identity;
            } catch (PathBoundaryException exception) {
                cleanupNewPrivateFile(identity);
                throw exception;
            } catch (IOException | RuntimeException exception) {
                cleanupNewPrivateFile(identity);
                throw failure(Reason.IO, fileLabel + " could not be created safely", exception);
            }
        }

        void verifyStable() {
            verifyPhysical(path, false, label);
            BasicFileAttributes parentAttributes = attributes(path.getParent(), label + " parent");
            PosixFileAttributes directoryAttributes = posixAttributes(path, label);
            if (!parentAttributes.isDirectory() || !directoryAttributes.isDirectory()
                    || !parentKey.equals(requiredFileKey(parentAttributes, label + " parent"))
                    || !directoryKey.equals(requiredFileKey(directoryAttributes, label))
                    || !PRIVATE_DIRECTORY_PERMISSIONS.equals(directoryAttributes.permissions())) {
                throw failure(Reason.INVALID, label + " changed during use", null);
            }
        }

        boolean deleteStagingIfOwned(RegularFile staging) throws IOException {
            Objects.requireNonNull(staging, "staging");
            verifyStable();
            if (!path.equals(staging.path.getParent()) || !directoryKey.equals(staging.parentKey)) {
                return false;
            }
            BasicFileAttributes stagingAttributes;
            try {
                stagingAttributes = Files.readAttributes(staging.path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            } catch (NoSuchFileException exception) {
                return false;
            }
            if (!stagingAttributes.isRegularFile() || !staging.fileKey.equals(stagingAttributes.fileKey())) {
                return false;
            }
            Files.delete(staging.path);
            verifyStable();
            return true;
        }

        boolean deleteIfOwnedAndEmpty() throws IOException {
            verifyStable();
            Files.delete(path);
            return true;
        }

        private void cleanupNewPrivateFile(RegularFile identity) {
            if (identity == null) {
                return;
            }
            try {
                deleteStagingIfOwned(identity);
            } catch (IOException | RuntimeException ignored) {
                // Preserve any path whose private-directory or file identity changed.
            }
        }
    }

    final class VerifiedCopy implements AutoCloseable {
        private final Path directory;
        private final Object directoryKey;
        private final RegularFile file;

        private VerifiedCopy(Path directory, Object directoryKey, RegularFile file) {
            this.directory = directory;
            this.directoryKey = directoryKey;
            this.file = file;
        }

        Path path() {
            file.verifyStable();
            return file.path();
        }

        @Override
        public void close() {
            try {
                file.verifyStable();
                BasicFileAttributes attributes = LocalPathBoundary.this.attributes(
                        directory, "Temporary input directory");
                if (!attributes.isDirectory()
                        || !directoryKey.equals(requiredFileKey(attributes, "Temporary input directory"))) {
                    return;
                }
                Files.delete(file.path());
                Files.delete(directory);
            } catch (IOException | RuntimeException ignored) {
                // Never delete a temporary path after its recorded identity changes.
            }
        }
    }

    enum Reason { INVALID, TOO_LARGE, IO }

    static final class PathBoundaryException extends RuntimeException {
        private final Reason reason;

        private PathBoundaryException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }

    private void verifyPhysical(Path path, boolean allowMissingLeaf, String label) {
        if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) {
            throw failure(Reason.INVALID, label + " must be an absolute normalized path", null);
        }
        Path current = path.getRoot();
        int index = 0;
        for (Path component : path) {
            current = current.resolve(component);
            index++;
            boolean leaf = index == path.getNameCount();
            if (!Files.exists(current, NOFOLLOW_LINKS)) {
                if (allowMissingLeaf && leaf) {
                    break;
                }
                throw failure(Reason.INVALID, label + " does not exist", null);
            }
            BasicFileAttributes attributes = attributes(current, label);
            if (attributes.isSymbolicLink()) {
                throw failure(Reason.INVALID, label + " must not traverse symbolic links", null);
            }
            if (!leaf && !attributes.isDirectory()) {
                throw failure(Reason.INVALID, label + " has a non-directory ancestor", null);
            }
        }
        if (!allowMissingLeaf || Files.exists(path, NOFOLLOW_LINKS)) {
            try {
                if (!path.equals(path.toRealPath())) {
                    throw failure(Reason.INVALID, label + " must use its physical path", null);
                }
            } catch (PathBoundaryException exception) {
                throw exception;
            } catch (IOException exception) {
                throw failure(Reason.IO, label + " physical path could not be verified", exception);
            }
        }
    }

    private void requireMissing(Path path, String label) {
        verifyPhysical(path.getParent(), false, label + " parent");
        if (Files.exists(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw failure(Reason.INVALID, label + " already exists", null);
        }
    }

    private BasicFileAttributes attributes(Path path, String label) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw failure(Reason.IO, label + " identity could not be read", exception);
        }
    }

    private PosixFileAttributes posixAttributes(Path path, String label) {
        try {
            return Files.readAttributes(path, PosixFileAttributes.class, NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException exception) {
            throw failure(Reason.INVALID, label + " owner-only identity could not be read", exception);
        }
    }

    private Object requiredFileKey(BasicFileAttributes attributes, String label) {
        if (attributes.fileKey() == null) {
            throw failure(Reason.INVALID, label + " filesystem does not expose stable identity", null);
        }
        return attributes.fileKey();
    }

    private byte[] bounded(InputStream input, int maxBytes, String label) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        while (true) {
            int remaining = maxBytes - total;
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining + 1));
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) {
                continue;
            }
            if (read > remaining) {
                throw failure(Reason.TOO_LARGE, label + " exceeds the maximum size", null);
            }
            output.write(buffer, 0, read);
            total += read;
        }
    }

    private void cleanupUnpublishedDirectory(Path directory, Object expectedDirectoryKey) {
        if (directory == null || expectedDirectoryKey == null) {
            return;
        }
        try {
            BasicFileAttributes before = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!before.isDirectory() || !expectedDirectoryKey.equals(before.fileKey())) {
                return;
            }
        } catch (IOException ignored) {
            return;
        }
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                BasicFileAttributes current = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!current.isDirectory() || !expectedDirectoryKey.equals(current.fileKey())) {
                    return;
                }
                if (entry.getParent().equals(directory) && !Files.isSymbolicLink(entry)
                        && Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(entry);
                }
            }
            BasicFileAttributes after = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (after.isDirectory() && expectedDirectoryKey.equals(after.fileKey())) {
                Files.deleteIfExists(directory);
            }
        } catch (IOException ignored) {
            // The random private temporary directory remains if safe cleanup is unavailable.
        }
    }

    private void cleanupNewPrivateDirectory(Path directory, Object expectedParentKey, Object expectedDirectoryKey) {
        if (directory == null || expectedParentKey == null || expectedDirectoryKey == null) {
            return;
        }
        try {
            BasicFileAttributes parentAttributes = Files.readAttributes(
                    directory.getParent(), BasicFileAttributes.class, NOFOLLOW_LINKS);
            PosixFileAttributes directoryAttributes = Files.readAttributes(
                    directory, PosixFileAttributes.class, NOFOLLOW_LINKS);
            if (parentAttributes.isDirectory() && directoryAttributes.isDirectory()
                    && expectedParentKey.equals(parentAttributes.fileKey())
                    && expectedDirectoryKey.equals(directoryAttributes.fileKey())) {
                Files.delete(directory);
            }
        } catch (IOException | RuntimeException ignored) {
            // Preserve a private path when its recorded identity or emptiness cannot be proven.
        }
    }

    private PathBoundaryException failure(Reason reason, String message, Throwable cause) {
        return new PathBoundaryException(reason, message, cause);
    }
}
