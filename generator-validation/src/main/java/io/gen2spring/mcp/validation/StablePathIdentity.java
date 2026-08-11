package io.gen2spring.mcp.validation;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

record StablePathIdentity(
        Path physicalPath,
        Object nativeFileKey,
        String fileStoreName,
        String fileStoreType,
        FileTime creationTime,
        FileTime lastModifiedTime,
        long size,
        boolean regularFile,
        boolean directory) {

    StablePathIdentity {
        Objects.requireNonNull(physicalPath, "physicalPath");
        Objects.requireNonNull(fileStoreName, "fileStoreName");
        Objects.requireNonNull(fileStoreType, "fileStoreType");
        Objects.requireNonNull(creationTime, "creationTime");
        Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
    }

    static StablePathIdentity capture(Path path) throws IOException {
        Path physical = path.toAbsolutePath().normalize().toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(physical, BasicFileAttributes.class, NOFOLLOW_LINKS);
        FileStore store = Files.getFileStore(physical);
        return new StablePathIdentity(
                physical,
                attributes.fileKey(),
                store.name(),
                store.type(),
                attributes.creationTime(),
                attributes.lastModifiedTime(),
                attributes.size(),
                attributes.isRegularFile(),
                attributes.isDirectory());
    }

    boolean matches(Path path) throws IOException {
        return sameIdentity(capture(path));
    }

    boolean sameFile(Path first, StablePathIdentity other, Path second) throws IOException {
        return matches(first) && other.matches(second) && Files.isSameFile(first, second);
    }

    boolean sameFile(Path first, Path second) throws IOException {
        return matches(first) && Files.isSameFile(first, second);
    }

    private boolean sameIdentity(StablePathIdentity other) {
        if (!physicalPath.equals(other.physicalPath)
                || !fileStoreName.equals(other.fileStoreName)
                || !fileStoreType.equals(other.fileStoreType)
                || regularFile != other.regularFile
                || directory != other.directory
                || !creationTime.equals(other.creationTime)) {
            return false;
        }
        if ((nativeFileKey != null || other.nativeFileKey != null)
                && (nativeFileKey == null || !nativeFileKey.equals(other.nativeFileKey))) {
            return false;
        }
        return directory || lastModifiedTime.equals(other.lastModifiedTime) && size == other.size;
    }
}
