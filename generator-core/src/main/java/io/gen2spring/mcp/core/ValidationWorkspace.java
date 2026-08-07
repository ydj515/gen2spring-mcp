package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.INTERNAL_ERROR;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class ValidationWorkspace implements AutoCloseable {
    private static final String STAGE = "VALIDATION";

    private final Path canonicalRoot;
    private final Path workspaceRoot;
    private final String workspacePrefix;
    private final Path parentRealPath;
    private final Object parentFileKey;
    private final Object workspaceFileKey;

    private ValidationWorkspace(
            Path canonicalRoot,
            Path workspaceRoot,
            String workspacePrefix,
            Path parentRealPath,
            Object parentFileKey,
            Object workspaceFileKey) {
        this.canonicalRoot = canonicalRoot;
        this.workspaceRoot = workspaceRoot;
        this.workspacePrefix = workspacePrefix;
        this.parentRealPath = parentRealPath;
        this.parentFileKey = parentFileKey;
        this.workspaceFileKey = workspaceFileKey;
    }

    static ValidationWorkspace copyOf(Path canonicalProjectRoot, SafeProjectWriter writer) {
        if (canonicalProjectRoot == null || Files.isSymbolicLink(canonicalProjectRoot)
                || !Files.isDirectory(canonicalProjectRoot, NOFOLLOW_LINKS)) {
            throw failure("Canonical project root is unavailable for validation", null);
        }
        Path canonical = canonicalProjectRoot.toAbsolutePath().normalize();
        String prefix = "." + canonical.getFileName() + ".validation-";
        Path workspace = canonical.getParent().resolve(prefix + UUID.randomUUID());
        Map<String, byte[]> files = readCanonicalFiles(canonical);
        writer.write(workspace, new GeneratedProjectFiles(files));
        Path parent = canonical.getParent();
        return new ValidationWorkspace(
                canonical,
                workspace,
                prefix,
                realPath(parent),
                requiredFileKey(parent),
                requiredFileKey(workspace));
    }

    Path root() {
        return workspaceRoot;
    }

    @Override
    public void close() {
        if (!canonicalRoot.getParent().equals(workspaceRoot.getParent())
                || !workspaceRoot.getFileName().toString().startsWith(workspacePrefix)) {
            throw failure("Validation workspace cleanup boundary is invalid", null);
        }
        try {
            verifyCleanupIdentity();
            if (!Files.exists(workspaceRoot, NOFOLLOW_LINKS) && !Files.isSymbolicLink(workspaceRoot)) {
                return;
            }
            try (var paths = Files.walk(workspaceRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    verifyCleanupIdentity();
                    if (!path.toAbsolutePath().normalize().startsWith(workspaceRoot)) {
                        throw failure("Validation workspace cleanup escaped its boundary", null);
                    }
                    Files.deleteIfExists(path);
                }
            }
        } catch (GeneratorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure("Validation workspace could not be cleaned", exception);
        }
    }

    private void verifyCleanupIdentity() {
        Path parent = workspaceRoot.getParent();
        if (!parentRealPath.equals(realPath(parent)) || !parentFileKey.equals(requiredFileKey(parent))) {
            throw failure("Validation workspace parent identity changed before cleanup", null);
        }
        if (!workspaceFileKey.equals(requiredFileKey(workspaceRoot))) {
            throw failure("Validation workspace identity changed before cleanup", null);
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw failure("Validation workspace path identity could not be resolved", exception);
        }
    }

    private static Object requiredFileKey(Path path) {
        try {
            Object fileKey = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey();
            if (fileKey == null) {
                throw failure("Validation filesystem does not expose stable file keys", null);
            }
            return fileKey;
        } catch (GeneratorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure("Validation workspace file key could not be read", exception);
        }
    }

    private static Map<String, byte[]> readCanonicalFiles(Path canonicalRoot) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var paths = Files.walk(canonicalRoot)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(canonicalRoot) || Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    throw failure("Canonical project contains an unsupported validation entry", null);
                }
                String relative = canonicalRoot.relativize(path).toString().replace('\\', '/');
                files.put(relative, Files.readAllBytes(path));
            }
            return Map.copyOf(files);
        } catch (GeneratorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure("Canonical project could not be copied for validation", exception);
        }
    }

    private static GeneratorException failure(String message, Throwable cause) {
        return cause == null
                ? GeneratorException.user(INTERNAL_ERROR, STAGE, message)
                : GeneratorException.system(INTERNAL_ERROR, STAGE, message, cause);
    }
}
