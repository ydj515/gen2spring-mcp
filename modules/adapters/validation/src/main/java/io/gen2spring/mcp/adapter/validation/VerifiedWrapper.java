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

    private VerifiedWrapper(
            Path root,
            StablePathIdentity rootIdentity,
            Path original,
            Path snapshot,
            StablePathIdentity snapshotIdentity,
            boolean requiresOwnerExecutable) {
        this.root = root;
        this.rootIdentity = rootIdentity;
        this.original = original;
        this.snapshot = snapshot;
        this.snapshotIdentity = snapshotIdentity;
        this.requiresOwnerExecutable = requiresOwnerExecutable;
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
            String prefix = "MAVEN".equals(buildTool) ? ".mvnw-validated-" : ".gradlew-validated-";
            String suffix = wrapper.getFileName().toString().endsWith(".cmd")
                    ? ".cmd"
                    : wrapper.getFileName().toString().endsWith(".bat") ? ".bat" : "";
            for (int attempt = 0; attempt < 32; attempt++) {
                Path candidateSnapshot = root.resolve(prefix + UUID.randomUUID() + suffix);
                try {
                    Files.createLink(candidateSnapshot, wrapper);
                    snapshot = candidateSnapshot;
                    break;
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Try another unpredictable path without replacing an existing file.
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
                    platform.requiresOwnerExecutable());
        } catch (IllegalArgumentException exception) {
            deletePinnedSnapshot(snapshot, snapshotIdentity, wrapper, originalIdentity);
            throw exception;
        } catch (IOException | UnsupportedOperationException exception) {
            deletePinnedSnapshot(snapshot, snapshotIdentity, wrapper, originalIdentity);
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
                    || !root.toRealPath().equals(root)
                    || !root.equals(snapshot.getParent())
                    || !snapshot.getParent().toRealPath().equals(root)) {
                throw new IllegalArgumentException("Build wrapper snapshot escaped the validation workspace");
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
}
