package io.gen2spring.mcp.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;

class StablePathIdentityTest {
    @Test
    void usesBoundedPhysicalAttributesWhenTheProviderDoesNotExposeAFileKey() {
        Path physical = Path.of("C:\\verified\\java.exe");
        var original = StablePathIdentity.from(
                physical, attributes(null, 128, 11, 17, true, false), "system", "NTFS");

        assertTrue(original.sameIdentity(StablePathIdentity.from(
                physical, attributes(null, 128, 11, 17, true, false), "system", "NTFS")));
        assertFalse(original.sameIdentity(StablePathIdentity.from(
                physical, attributes(null, 129, 11, 19, true, false), "system", "NTFS")));
    }

    @Test
    void includesCreationTimeWhenANativeFileKeyIsReused() {
        Path physical = Path.of("/verified/java");
        var original = StablePathIdentity.from(
                physical, attributes("inode-1", 128, 11, 17, true, false), "disk", "apfs");

        assertTrue(original.sameIdentity(StablePathIdentity.from(
                physical, attributes("inode-1", 128, 11, 17, true, false), "disk", "apfs")));
        assertFalse(original.sameIdentity(StablePathIdentity.from(
                physical, attributes("inode-1", 999, 11, 99, true, false), "disk", "apfs")));
        assertFalse(original.sameIdentity(StablePathIdentity.from(
                physical, attributes("inode-1", 128, 12, 17, true, false), "disk", "apfs")));
    }

    @Test
    void ignoresMutableDirectoryShapeAfterCreation() {
        Path physical = Path.of("C:\\verified\\workspace");
        var original = StablePathIdentity.from(
                physical, attributes(null, 0, 11, 17, false, true), "system", "NTFS");

        assertTrue(original.sameIdentity(StablePathIdentity.from(
                physical, attributes(null, 4096, 11, 99, false, true), "system", "NTFS")));
    }

    private BasicFileAttributes attributes(
            Object fileKey,
            long size,
            long createdMillis,
            long modifiedMillis,
            boolean regularFile,
            boolean directory) {
        return new BasicFileAttributes() {
            @Override
            public FileTime lastModifiedTime() {
                return FileTime.fromMillis(modifiedMillis);
            }

            @Override
            public FileTime lastAccessTime() {
                return FileTime.fromMillis(modifiedMillis);
            }

            @Override
            public FileTime creationTime() {
                return FileTime.fromMillis(createdMillis);
            }

            @Override
            public boolean isRegularFile() {
                return regularFile;
            }

            @Override
            public boolean isDirectory() {
                return directory;
            }

            @Override
            public boolean isSymbolicLink() {
                return false;
            }

            @Override
            public boolean isOther() {
                return false;
            }

            @Override
            public long size() {
                return size;
            }

            @Override
            public Object fileKey() {
                return fileKey;
            }
        };
    }
}
