package io.gen2spring.mcp.adapter.filesystem;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

public final class StablePathIdentity {
    private final Path physicalPath;
    private final Object nativeFileKey;
    private final String fileStoreName;
    private final String fileStoreType;
    private final FileTime creationTime;
    private final FileTime lastModifiedTime;
    private final long size;
    private final boolean regularFile;
    private final boolean directory;

    private StablePathIdentity(
            Path physicalPath,
            Object nativeFileKey,
            String fileStoreName,
            String fileStoreType,
            FileTime creationTime,
            FileTime lastModifiedTime,
            long size,
            boolean regularFile,
            boolean directory) {
        this.physicalPath = Objects.requireNonNull(physicalPath, "physicalPath");
        this.nativeFileKey = nativeFileKey;
        this.fileStoreName = Objects.requireNonNull(fileStoreName, "fileStoreName");
        this.fileStoreType = Objects.requireNonNull(fileStoreType, "fileStoreType");
        this.creationTime = Objects.requireNonNull(creationTime, "creationTime");
        this.lastModifiedTime = Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        this.size = size;
        this.regularFile = regularFile;
        this.directory = directory;
    }

    public static StablePathIdentity capture(Path path) throws IOException {
        Path physical = path.toAbsolutePath().normalize().toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(physical, BasicFileAttributes.class, NOFOLLOW_LINKS);
        FileStore store = Files.getFileStore(physical);
        return from(physical, attributes, store.name(), store.type());
    }

    static StablePathIdentity from(
            Path physical,
            BasicFileAttributes attributes,
            String fileStoreName,
            String fileStoreType) {
        Objects.requireNonNull(attributes, "attributes");
        return new StablePathIdentity(
                physical.toAbsolutePath().normalize(),
                attributes.fileKey(),
                fileStoreName,
                fileStoreType,
                attributes.creationTime(),
                attributes.lastModifiedTime(),
                attributes.size(),
                attributes.isRegularFile(),
                attributes.isDirectory());
    }

    public boolean matches(Path path) throws IOException {
        return sameIdentity(capture(path));
    }

    public boolean matchesObject(Path path) throws IOException {
        return sameObject(capture(path));
    }

    public boolean sameObjectIdentity(StablePathIdentity other) {
        return sameObject(Objects.requireNonNull(other, "other"));
    }

    public boolean sameFile(Path first, StablePathIdentity other, Path second) throws IOException {
        Objects.requireNonNull(other, "other");
        return matches(first) && other.matches(second) && Files.isSameFile(first, second);
    }

    public boolean sameFile(Path first, Path second) throws IOException {
        return matches(first) && Files.isSameFile(first, second);
    }

    boolean sameIdentity(StablePathIdentity other) {
        return sameObject(other)
                && (directory || lastModifiedTime.equals(other.lastModifiedTime) && size == other.size);
    }

    private boolean sameObject(StablePathIdentity other) {
        if (!physicalPath.equals(other.physicalPath)
                || !fileStoreName.equals(other.fileStoreName)
                || !fileStoreType.equals(other.fileStoreType)
                || regularFile != other.regularFile
                || directory != other.directory
                || !creationTime.equals(other.creationTime)) {
            return false;
        }
        return nativeFileKey == null && other.nativeFileKey == null
                || nativeFileKey != null && nativeFileKey.equals(other.nativeFileKey);
    }
}
