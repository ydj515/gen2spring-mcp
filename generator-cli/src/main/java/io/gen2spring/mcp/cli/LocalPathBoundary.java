package io.gen2spring.mcp.cli;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import io.gen2spring.mcp.adapter.filesystem.StablePathIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class LocalPathBoundary {
    private static final Pattern SAFE_SUFFIX = Pattern.compile("\\.[A-Za-z0-9]{1,8}");
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            Set.copyOf(PosixFilePermissions.fromString("rwx------"));
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            Set.copyOf(PosixFilePermissions.fromString("rw-------"));
    private static final Set<AclEntryPermission> PRIVATE_ACL_PERMISSIONS =
            Set.copyOf(EnumSet.allOf(AclEntryPermission.class));

    private interface PrivateAccess {
        FileAttribute<?> attribute();

        boolean matches(Path path) throws IOException;
    }

    private record PosixPrivateAccess(Set<PosixFilePermission> permissions) implements PrivateAccess {
        @Override
        public FileAttribute<Set<PosixFilePermission>> attribute() {
            return PosixFilePermissions.asFileAttribute(permissions);
        }

        @Override
        public boolean matches(Path path) throws IOException {
            return permissions.equals(Files.readAttributes(
                    path, PosixFileAttributes.class, NOFOLLOW_LINKS).permissions());
        }
    }

    private record AclPrivateAccess(UserPrincipal owner, List<AclEntry> entries) implements PrivateAccess {
        @Override
        public FileAttribute<List<AclEntry>> attribute() {
            return new FileAttribute<>() {
                @Override
                public String name() {
                    return "acl:acl";
                }

                @Override
                public List<AclEntry> value() {
                    return entries;
                }
            };
        }

        @Override
        public boolean matches(Path path) throws IOException {
            AclFileAttributeView view = Files.getFileAttributeView(
                    path, AclFileAttributeView.class, NOFOLLOW_LINKS);
            return view != null && owner.equals(Files.getOwner(path, NOFOLLOW_LINKS))
                    && entries.equals(view.getAcl());
        }
    }

    RegularFile regularFile(Path supplied, String label) {
        return regularFile(supplied, label, null);
    }

    private RegularFile regularFile(Path supplied, String label, PrivateAccess privateAccess) {
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
        try {
            return new RegularFile(path, StablePathIdentity.capture(parent),
                    StablePathIdentity.capture(path), privateAccess, label);
        } catch (IOException exception) {
            throw failure(Reason.IO, label + " identity could not be read", exception);
        }
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
        try {
            return new NewFile(path, StablePathIdentity.capture(parent), label);
        } catch (IOException exception) {
            throw failure(Reason.IO, label + " parent identity could not be read", exception);
        }
    }

    PrivateDirectory privateDirectory(NewFile target, String prefix, String label) {
        Objects.requireNonNull(target, "target");
        if (prefix == null || prefix.isBlank()) {
            throw failure(Reason.INVALID, label + " prefix is required", null);
        }
        target.verifyParentStable();
        Path parent = target.path().getParent();
        PrivateAccess privateAccess = privateAccess(parent, PRIVATE_DIRECTORY_PERMISSIONS, label);
        Path directory = null;
        StablePathIdentity directoryKey = null;
        try {
            directory = Files.createTempDirectory(parent, prefix, privateAccess.attribute());
            if (!privateAccess.matches(directory)) {
                throw failure(Reason.INVALID, label + " must have owner-only permissions", null);
            }
            directoryKey = StablePathIdentity.capture(directory);
            PrivateDirectory result = new PrivateDirectory(
                    directory, target.parentKey, directoryKey, privateAccess, label);
            result.verifyStable();
            return result;
        } catch (PathBoundaryException exception) {
            cleanupNewPrivateDirectory(directory, target.parentKey, directoryKey, privateAccess);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            cleanupNewPrivateDirectory(directory, target.parentKey, directoryKey, privateAccess);
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
        StablePathIdentity directoryKey = null;
        try {
            Path configuredTemp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            Path physicalTemp = configuredTemp.toRealPath();
            if (!Files.isDirectory(physicalTemp, NOFOLLOW_LINKS) || Files.isSymbolicLink(physicalTemp)) {
                throw failure(Reason.INVALID, "Temporary input directory is unsafe", null);
            }
            directory = Files.createTempDirectory(physicalTemp, ".openapi-mcp-input-");
            BasicFileAttributes directoryAttributes = attributes(directory, "Temporary input directory");
            directoryKey = StablePathIdentity.capture(directory);
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
        private final StablePathIdentity parentKey;
        private StablePathIdentity fileKey;
        private final PrivateAccess privateAccess;
        private final String label;

        private RegularFile(
                Path path,
                StablePathIdentity parentKey,
                StablePathIdentity fileKey,
                PrivateAccess privateAccess,
                String label) {
            this.path = path;
            this.parentKey = parentKey;
            this.fileKey = fileKey;
            this.privateAccess = privateAccess;
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
                    || !matches(parentKey, path.getParent())
                    || !matches(fileKey, path)) {
                throw failure(Reason.INVALID, label + " changed during use", null);
            }
            if (privateAccess != null) {
                try {
                    if (!privateAccess.matches(path)) {
                        throw failure(Reason.INVALID, label + " permissions changed during use", null);
                    }
                } catch (IOException exception) {
                    throw failure(Reason.IO, label + " permissions could not be verified", exception);
                }
            }
        }

        void refreshAfterWrite() {
            verifyPhysical(path, false, label);
            try {
                if (!parentKey.matches(path.getParent()) || !fileKey.matchesObject(path)
                        || privateAccess != null && !privateAccess.matches(path)) {
                    throw failure(Reason.INVALID, label + " changed during write", null);
                }
                fileKey = StablePathIdentity.capture(path);
            } catch (PathBoundaryException exception) {
                throw exception;
            } catch (IOException exception) {
                throw failure(Reason.IO, label + " identity could not be refreshed", exception);
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
            try {
                if (!fileKey.sameFile(path, other.fileKey, other.path)) {
                    throw failure(Reason.INVALID, label + " identity does not match the published file", null);
                }
            } catch (IOException exception) {
                throw failure(Reason.IO, label + " identity could not be compared", exception);
            }
        }
    }

    final class NewFile {
        private final Path path;
        private final StablePathIdentity parentKey;
        private final String label;

        private NewFile(Path path, StablePathIdentity parentKey, String label) {
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
                    || !matches(parentKey, parent)) {
                throw failure(Reason.INVALID, label + " parent changed during use", null);
            }
        }
    }

    final class PrivateDirectory {
        private final Path path;
        private final StablePathIdentity parentKey;
        private final StablePathIdentity directoryKey;
        private final PrivateAccess privateAccess;
        private final String label;

        private PrivateDirectory(
                Path path,
                StablePathIdentity parentKey,
                StablePathIdentity directoryKey,
                PrivateAccess privateAccess,
                String label) {
            this.path = path;
            this.parentKey = parentKey;
            this.directoryKey = directoryKey;
            this.privateAccess = privateAccess;
            this.label = label;
        }

        RegularFile createFile(String prefix, String suffix, String fileLabel) {
            verifyStable();
            Path file = null;
            RegularFile identity = null;
            try {
                PrivateAccess fileAccess = privateAccess(path, PRIVATE_FILE_PERMISSIONS, fileLabel);
                file = Files.createTempFile(path, prefix, suffix, fileAccess.attribute());
                if (!fileAccess.matches(file)) {
                    throw failure(Reason.INVALID, fileLabel + " must have owner-only permissions", null);
                }
                identity = regularFile(file, fileLabel, fileAccess);
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
            BasicFileAttributes directoryAttributes = attributes(path, label);
            try {
                if (!parentAttributes.isDirectory() || !directoryAttributes.isDirectory()
                    || !matches(parentKey, path.getParent())
                    || !matches(directoryKey, path)
                    || !privateAccess.matches(path)) {
                    throw failure(Reason.INVALID, label + " changed during use", null);
                }
            } catch (IOException exception) {
                throw failure(Reason.IO, label + " permissions could not be verified", exception);
            }
        }

        boolean deleteStagingIfOwned(RegularFile staging) throws IOException {
            Objects.requireNonNull(staging, "staging");
            verifyStable();
            if (!path.equals(staging.path.getParent()) || !sameRecordedIdentity(directoryKey, staging.parentKey)) {
                return false;
            }
            BasicFileAttributes stagingAttributes;
            try {
                stagingAttributes = Files.readAttributes(staging.path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            } catch (NoSuchFileException exception) {
                return false;
            }
            if (!stagingAttributes.isRegularFile() || !matches(staging.fileKey, staging.path)) {
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
        private final StablePathIdentity directoryKey;
        private final RegularFile file;

        private VerifiedCopy(Path directory, StablePathIdentity directoryKey, RegularFile file) {
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
                        || !matches(directoryKey, directory)) {
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

    private PrivateAccess privateAccess(
            Path parent, Set<PosixFilePermission> posixPermissions, String label) {
        try {
            if (Files.getFileAttributeView(parent, PosixFileAttributeView.class, NOFOLLOW_LINKS) != null) {
                return new PosixPrivateAccess(posixPermissions);
            }
            AclFileAttributeView view = Files.getFileAttributeView(
                    parent, AclFileAttributeView.class, NOFOLLOW_LINKS);
            if (view == null) {
                throw failure(Reason.INVALID, label + " requires owner-only filesystem permissions", null);
            }
            UserPrincipal owner = Files.getOwner(parent, NOFOLLOW_LINKS);
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(PRIVATE_ACL_PERMISSIONS)
                    .build();
            return new AclPrivateAccess(owner, List.of(ownerOnly));
        } catch (IOException | UnsupportedOperationException exception) {
            throw failure(Reason.INVALID, label + " owner-only identity could not be read", exception);
        }
    }

    private boolean matches(StablePathIdentity expected, Path path) {
        try {
            return expected.matches(path);
        } catch (IOException exception) {
            throw failure(Reason.IO, "Local path identity could not be read", exception);
        }
    }

    private boolean sameRecordedIdentity(StablePathIdentity first, StablePathIdentity second) {
        return first.sameObjectIdentity(second);
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

    private void cleanupUnpublishedDirectory(Path directory, StablePathIdentity expectedDirectoryKey) {
        if (directory == null || expectedDirectoryKey == null) {
            return;
        }
        try {
            BasicFileAttributes before = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!before.isDirectory() || !matches(expectedDirectoryKey, directory)) {
                return;
            }
        } catch (IOException ignored) {
            return;
        }
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                BasicFileAttributes current = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!current.isDirectory() || !matches(expectedDirectoryKey, directory)) {
                    return;
                }
                if (entry.getParent().equals(directory) && !Files.isSymbolicLink(entry)
                        && Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(entry);
                }
            }
            BasicFileAttributes after = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (after.isDirectory() && matches(expectedDirectoryKey, directory)) {
                Files.deleteIfExists(directory);
            }
        } catch (IOException ignored) {
            // The random private temporary directory remains if safe cleanup is unavailable.
        }
    }

    private void cleanupNewPrivateDirectory(
            Path directory,
            StablePathIdentity expectedParentKey,
            StablePathIdentity expectedDirectoryKey,
            PrivateAccess privateAccess) {
        if (directory == null || expectedParentKey == null || expectedDirectoryKey == null) {
            return;
        }
        try {
            BasicFileAttributes parentAttributes = Files.readAttributes(
                    directory.getParent(), BasicFileAttributes.class, NOFOLLOW_LINKS);
            BasicFileAttributes directoryAttributes = Files.readAttributes(
                    directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (parentAttributes.isDirectory() && directoryAttributes.isDirectory()
                    && matches(expectedParentKey, directory.getParent())
                    && matches(expectedDirectoryKey, directory)
                    && privateAccess.matches(directory)) {
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
