package io.gen2spring.mcp.adapter.validation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.UUID;

final class VerifiedWrapper implements AutoCloseable {
    private final Path root;
    private final StablePathIdentity rootIdentity;
    private final Path original;
    private final Path snapshot;
    private final StablePathIdentity snapshotIdentity;
    private final boolean requiresOwnerExecutable;
    private final MavenSnapshot mavenSnapshot;

    private VerifiedWrapper(
            Path root,
            StablePathIdentity rootIdentity,
            Path original,
            Path snapshot,
            StablePathIdentity snapshotIdentity,
            boolean requiresOwnerExecutable,
            MavenSnapshot mavenSnapshot) {
        this.root = root;
        this.rootIdentity = rootIdentity;
        this.original = original;
        this.snapshot = snapshot;
        this.snapshotIdentity = snapshotIdentity;
        this.requiresOwnerExecutable = requiresOwnerExecutable;
        this.mavenSnapshot = mavenSnapshot;
    }

    static VerifiedWrapper pin(
            Path validationRoot,
            Path candidate,
            ValidationHostPlatform platform,
            String buildTool) {
        if (validationRoot == null || candidate == null) {
            throw new IllegalArgumentException("Build wrapper path is required");
        }
        Objects.requireNonNull(platform, "platform");
        Path root = validationRoot.toAbsolutePath().normalize();
        Path wrapper = candidate.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)
                || !wrapper.startsWith(root)
                || !root.equals(wrapper.getParent())
                || !platform.wrapperFileName(buildTool).equals(wrapper.getFileName().toString())) {
            throw new IllegalArgumentException("Build wrapper escaped the validation workspace");
        }
        Path snapshot = null;
        StablePathIdentity originalIdentity = null;
        StablePathIdentity snapshotIdentity = null;
        MavenSnapshot mavenSnapshot = null;
        try {
            Path realRoot = root.toRealPath();
            if (!realRoot.equals(root) || !wrapper.getParent().toRealPath().equals(realRoot)) {
                throw new IllegalArgumentException("Build wrapper ancestry is not physical");
            }
            BasicFileAttributes rootAttributes = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!rootAttributes.isDirectory()) {
                throw new IllegalArgumentException("Validation workspace is not a physical directory");
            }
            StablePathIdentity rootIdentity = StablePathIdentity.capture(root);
            regularFileAttributes(wrapper);
            originalIdentity = StablePathIdentity.capture(wrapper);
            if (platform.requiresOwnerExecutable()) {
                requireOwnerExecutable(wrapper);
            }
            if ("MAVEN".equals(buildTool)) {
                mavenSnapshot = createMavenSnapshot(root, wrapper, originalIdentity);
                snapshot = mavenSnapshot.wrapper();
            } else {
                String suffix = wrapper.getFileName().toString().endsWith(".cmd")
                        ? ".cmd"
                        : wrapper.getFileName().toString().endsWith(".bat") ? ".bat" : "";
                for (int attempt = 0; attempt < 32; attempt++) {
                    Path candidateSnapshot = root.resolve(".gradlew-validated-" + UUID.randomUUID() + suffix);
                    try {
                        Files.createLink(candidateSnapshot, wrapper);
                        snapshot = candidateSnapshot;
                        break;
                    } catch (java.nio.file.FileAlreadyExistsException ignored) {
                        // Try another unpredictable path without replacing an existing file.
                    }
                }
            }
            if (snapshot == null) {
                throw new IllegalArgumentException("Build wrapper snapshot path could not be reserved");
            }
            regularFileAttributes(snapshot);
            snapshotIdentity = StablePathIdentity.capture(snapshot);
            if (!originalIdentity.sameFile(wrapper, snapshotIdentity, snapshot)) {
                throw new IllegalArgumentException("Build wrapper changed while its identity was pinned");
            }
            if (platform.requiresOwnerExecutable()) {
                requireOwnerExecutable(snapshot);
            }
            return new VerifiedWrapper(
                    root,
                    rootIdentity,
                    wrapper,
                    snapshot,
                    snapshotIdentity,
                    platform.requiresOwnerExecutable(),
                    mavenSnapshot);
        } catch (IllegalArgumentException exception) {
            deletePinnedSnapshot(snapshot, snapshotIdentity, wrapper, originalIdentity);
            cleanupMavenSnapshot(mavenSnapshot);
            throw exception;
        } catch (IOException | UnsupportedOperationException exception) {
            deletePinnedSnapshot(snapshot, snapshotIdentity, wrapper, originalIdentity);
            cleanupMavenSnapshot(mavenSnapshot);
            throw new IllegalArgumentException("Build wrapper identity could not be pinned", exception);
        }
    }

    Path original() {
        return original;
    }

    Path snapshot() {
        return snapshot;
    }

    Path verifiedExecutable() {
        try {
            BasicFileAttributes currentRoot = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!currentRoot.isDirectory()
                    || !rootIdentity.matches(root)
                    || !root.toRealPath().equals(root)) {
                throw new IllegalArgumentException("Build wrapper snapshot escaped the validation workspace");
            }
            if (mavenSnapshot == null) {
                if (!root.equals(snapshot.getParent()) || !snapshot.getParent().toRealPath().equals(root)) {
                    throw new IllegalArgumentException("Build wrapper snapshot escaped the validation workspace");
                }
            } else {
                requireStableMavenSnapshot(mavenSnapshot);
            }
            regularFileAttributes(snapshot);
            if (!snapshotIdentity.matches(snapshot)) {
                throw new IllegalArgumentException("Build wrapper snapshot identity changed before execution");
            }
            if (requiresOwnerExecutable) {
                requireOwnerExecutable(snapshot);
            }
            return snapshot;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Build wrapper snapshot could not be reverified", exception);
        }
    }

    @Override
    public void close() {
        try {
            BasicFileAttributes currentRoot = Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (currentRoot.isDirectory() && rootIdentity.matches(root)) {
                deletePinnedSnapshot(snapshot, snapshotIdentity, original, null);
                cleanupMavenSnapshot(mavenSnapshot);
            }
        } catch (IOException | RuntimeException ignored) {
            // Fail closed: cleanup never follows a changed workspace identity.
        }
    }

    private static BasicFileAttributes regularFileAttributes(Path path) throws IOException {
        if (path == null || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Build wrapper is not a regular workspace file");
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException("Build wrapper is not a regular workspace file");
        }
        return attributes;
    }

    private static void requireOwnerExecutable(Path wrapper) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                wrapper, PosixFileAttributeView.class, NOFOLLOW_LINKS);
        boolean executable = posix == null
                ? Files.isExecutable(wrapper)
                : posix.readAttributes().permissions().contains(PosixFilePermission.OWNER_EXECUTE);
        if (!executable) {
            throw new IllegalArgumentException("Build wrapper is not owner-executable");
        }
    }

    private static MavenSnapshot createMavenSnapshot(
            Path root,
            Path wrapper,
            StablePathIdentity originalIdentity) throws IOException {
        Path originalMetadata = root.resolve(".mvn/wrapper/maven-wrapper.properties");
        regularFileAttributes(originalMetadata);
        StablePathIdentity originalMetadataIdentity = StablePathIdentity.capture(originalMetadata);
        Path snapshotRoot = null;
        StablePathIdentity snapshotRootIdentity = null;
        Path metadataRoot = null;
        StablePathIdentity metadataRootIdentity = null;
        Path metadataParent = null;
        StablePathIdentity metadataParentIdentity = null;
        Path snapshotWrapper = null;
        StablePathIdentity snapshotWrapperIdentity = null;
        Path snapshotMetadata = null;
        StablePathIdentity snapshotMetadataIdentity = null;
        try {
            for (int attempt = 0; attempt < 32; attempt++) {
                Path candidate = root.resolve(".mvnw-validated-" + UUID.randomUUID());
                try {
                    snapshotRoot = Files.createDirectory(candidate);
                    break;
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Try another unpredictable path without replacing an existing file.
                }
            }
            if (snapshotRoot == null) {
                throw new IllegalArgumentException("Build wrapper snapshot path could not be reserved");
            }
            snapshotRootIdentity = StablePathIdentity.capture(snapshotRoot);
            metadataRoot = Files.createDirectory(snapshotRoot.resolve(".mvn"));
            metadataRootIdentity = StablePathIdentity.capture(metadataRoot);
            metadataParent = Files.createDirectory(metadataRoot.resolve("wrapper"));
            metadataParentIdentity = StablePathIdentity.capture(metadataParent);
            snapshotWrapper = Files.createLink(snapshotRoot.resolve(wrapper.getFileName()), wrapper);
            snapshotWrapperIdentity = StablePathIdentity.capture(snapshotWrapper);
            if (!originalIdentity.sameFile(wrapper, snapshotWrapperIdentity, snapshotWrapper)) {
                throw new IllegalArgumentException("Build wrapper changed while its identity was pinned");
            }
            snapshotMetadata = Files.createLink(
                    metadataParent.resolve("maven-wrapper.properties"), originalMetadata);
            snapshotMetadataIdentity = StablePathIdentity.capture(snapshotMetadata);
            if (!originalMetadataIdentity.sameFile(
                    originalMetadata, snapshotMetadataIdentity, snapshotMetadata)) {
                throw new IllegalArgumentException("Maven wrapper metadata changed while its identity was pinned");
            }
            return new MavenSnapshot(
                    snapshotRoot,
                    snapshotRootIdentity,
                    metadataRoot,
                    metadataRootIdentity,
                    metadataParent,
                    metadataParentIdentity,
                    snapshotWrapper,
                    snapshotWrapperIdentity,
                    snapshotMetadata,
                    snapshotMetadataIdentity);
        } catch (IOException | RuntimeException failure) {
            deleteOwnedFile(snapshotMetadata, snapshotMetadataIdentity);
            deleteOwnedFile(snapshotWrapper, snapshotWrapperIdentity);
            deleteOwnedDirectory(metadataParent, metadataParentIdentity);
            deleteOwnedDirectory(metadataRoot, metadataRootIdentity);
            deleteOwnedDirectory(snapshotRoot, snapshotRootIdentity);
            throw failure;
        }
    }

    private static void requireStableMavenSnapshot(MavenSnapshot value) throws IOException {
        if (!rootedPhysicalDirectory(value.root(), value.rootIdentity(), value.root().getParent())
                || !rootedPhysicalDirectory(value.metadataRoot(), value.metadataRootIdentity(), value.root())
                || !rootedPhysicalDirectory(value.metadataParent(), value.metadataParentIdentity(), value.metadataRoot())) {
            throw new IllegalArgumentException("Maven wrapper snapshot metadata escaped the validation workspace");
        }
        regularFileAttributes(value.metadata());
        if (!value.metadataIdentity().matches(value.metadata())) {
            throw new IllegalArgumentException("Maven wrapper snapshot metadata changed before execution");
        }
    }

    private static boolean rootedPhysicalDirectory(
            Path directory,
            StablePathIdentity identity,
            Path expectedParent) throws IOException {
        if (directory == null || Files.isSymbolicLink(directory) || !expectedParent.equals(directory.getParent())) {
            return false;
        }
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
        return attributes.isDirectory() && identity.matches(directory)
                && directory.toRealPath().getParent().equals(expectedParent.toRealPath());
    }

    private static void cleanupMavenSnapshot(MavenSnapshot value) {
        if (value == null) {
            return;
        }
        deleteOwnedFile(value.metadata(), value.metadataIdentity());
        deleteOwnedDirectory(value.metadataParent(), value.metadataParentIdentity());
        deleteOwnedDirectory(value.metadataRoot(), value.metadataRootIdentity());
        deleteOwnedDirectory(value.root(), value.rootIdentity());
    }

    private static void deleteOwnedFile(Path path, StablePathIdentity identity) {
        if (path == null || identity == null) {
            return;
        }
        try {
            if (!Files.isSymbolicLink(path) && identity.matches(path)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // Fail closed: an unverified path is never removed.
        }
    }

    private static void deleteOwnedDirectory(Path path, StablePathIdentity identity) {
        if (path == null || identity == null) {
            return;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!Files.isSymbolicLink(path) && attributes.isDirectory() && identity.matches(path)) {
                Files.delete(path);
            }
        } catch (IOException | RuntimeException ignored) {
            // Fail closed: non-empty or unverified directories remain untouched.
        }
    }

    private static void deletePinnedSnapshot(
            Path snapshot,
            StablePathIdentity snapshotIdentity,
            Path original,
            StablePathIdentity originalIdentity) {
        if (snapshot == null) {
            return;
        }
        try {
            boolean owned = snapshotIdentity != null
                    ? snapshotIdentity.matches(snapshot)
                    : originalIdentity != null && originalIdentity.sameFile(original, snapshot);
            if (owned) {
                Files.deleteIfExists(snapshot);
            }
        } catch (IOException | RuntimeException ignored) {
            // Fail closed: an unverified path is never removed.
        }
    }

    private record MavenSnapshot(
            Path root,
            StablePathIdentity rootIdentity,
            Path metadataRoot,
            StablePathIdentity metadataRootIdentity,
            Path metadataParent,
            StablePathIdentity metadataParentIdentity,
            Path wrapper,
            StablePathIdentity wrapperIdentity,
            Path metadata,
            StablePathIdentity metadataIdentity) {}
}
