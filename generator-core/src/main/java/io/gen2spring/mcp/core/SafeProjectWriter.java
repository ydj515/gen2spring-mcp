package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GeneratedProjectFiles;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SafeProjectWriter {
    private static final String STAGE = "SOURCE_GENERATE";
    private static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE);

    private final PublicationHook publicationHook;

    public SafeProjectWriter() {
        this((staging, target) -> {});
    }

    SafeProjectWriter(PublicationHook publicationHook) {
        this.publicationHook = Objects.requireNonNull(publicationHook, "publicationHook");
    }

    public Path write(Path outputRoot, GeneratedProjectFiles projectFiles) {
        if (outputRoot == null || projectFiles == null || projectFiles.files() == null) {
            throw failure("Project output and generated files are required");
        }
        Path root = outputRoot.toAbsolutePath().normalize();
        Path parent = requireSafeParent(root);
        rejectExistingRoot(root);
        List<Entry> entries = validateEntries(projectFiles.files());
        ParentIdentity parentIdentity = parentIdentity(parent);
        String stagingPrefix = "." + root.getFileName() + ".staging-";
        OwnedTree stagingOwnership = null;
        OwnedTree outputOwnership = null;
        try {
            Path staging = Files.createTempDirectory(parent, stagingPrefix);
            stagingOwnership = new OwnedTree(parent, parentIdentity);
            stagingOwnership.record(staging);
            verifyParentIdentity(parent, parentIdentity);
            writeTree(staging, entries, stagingOwnership);
            verifyTree(staging, entries, stagingOwnership);

            publicationHook.beforeTargetReservation(staging, root);
            verifyParentIdentity(parent, parentIdentity);
            verifyTree(staging, entries, stagingOwnership);
            cleanupOwnedTree(stagingOwnership);
            stagingOwnership = null;

            Files.createDirectory(root);
            outputOwnership = new OwnedTree(parent, parentIdentity);
            outputOwnership.record(root);
            writeTree(root, entries, outputOwnership);
            verifyTree(root, entries, outputOwnership);
            outputOwnership = null;
            return root;
        } catch (GeneratorException exception) {
            cleanupAfterFailure(exception, outputOwnership);
            cleanupAfterFailure(exception, stagingOwnership);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            GeneratorException wrapped = GeneratorException.system(
                    SOURCE_GENERATION_FAILED, STAGE, "Generated project files could not be written", exception);
            cleanupAfterFailure(wrapped, outputOwnership);
            cleanupAfterFailure(wrapped, stagingOwnership);
            throw wrapped;
        }
    }

    private Path requireSafeParent(Path root) {
        Path parent = root.getParent();
        if (parent == null) {
            throw failure("The project output parent is required");
        }
        rejectExistingSymbolicLinkAncestors(root);
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) {
            throw failure("The project output parent must be an existing regular directory");
        }
        return parent;
    }

    private void rejectExistingSymbolicLinkAncestors(Path path) {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current == null ? component : current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw failure("The project output path must not traverse symbolic links");
            }
            if (!Files.exists(current, NOFOLLOW_LINKS)) {
                break;
            }
        }
    }

    private ParentIdentity parentIdentity(Path parent) {
        try {
            return new ParentIdentity(StablePathIdentity.capture(parent));
        } catch (GeneratorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw GeneratorException.system(
                    SOURCE_GENERATION_FAILED, STAGE, "The project output parent could not be verified", exception);
        }
    }

    private void verifyParentIdentity(Path parent, ParentIdentity expected) {
        rejectExistingSymbolicLinkAncestors(parent);
        try {
            if (!expected.identity().matches(parent)) {
                throw failure("The project output parent changed during generation");
            }
        } catch (IOException exception) {
            throw GeneratorException.system(
                    SOURCE_GENERATION_FAILED, STAGE, "The project output parent could not be verified", exception);
        }
    }

    private List<Entry> validateEntries(Map<String, byte[]> files) {
        List<Entry> entries = new ArrayList<>();
        Set<String> portablePathKeys = new HashSet<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path relative = safeRelativePath(file.getKey());
            String portablePath = relative.toString().replace('\\', '/');
            if (!portablePathKeys.add(PortablePathKey.caseFolded(portablePath))) {
                throw failure("Generated project contains a duplicate output path");
            }
            if (file.getValue() == null) {
                throw failure("Generated project file content is required");
            }
            entries.add(new Entry(relative, portablePath, file.getValue()));
        }
        entries.sort(Comparator.comparing(Entry::portablePath));
        return List.copyOf(entries);
    }

    private Path safeRelativePath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || looksLikeWindowsAbsolutePath(value)) {
            throw failure("Generated project contains an invalid output path");
        }
        try {
            Path path = Path.of(value);
            Path normalized = path.normalize();
            if (path.isAbsolute() || normalized.getNameCount() == 0 || normalized.startsWith("..")) {
                throw failure("Generated project contains an unsafe output path");
            }
            return normalized;
        } catch (InvalidPathException exception) {
            throw GeneratorException.user(
                    SOURCE_GENERATION_FAILED, STAGE, "Generated project contains an invalid output path", exception);
        }
    }

    private boolean looksLikeWindowsAbsolutePath(String value) {
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }

    private void rejectExistingRoot(Path root) {
        if (Files.exists(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw failure("The project output directory already exists");
        }
    }

    private void writeTree(Path treeRoot, List<Entry> entries, OwnedTree ownership) throws IOException {
        for (Entry entry : entries) {
            ownership.verify(treeRoot);
            Path destination = treeRoot.resolve(entry.relativePath()).normalize();
            if (!destination.startsWith(treeRoot)) {
                throw failure("Generated project contains an unsafe publication path");
            }
            createOwnedParents(treeRoot, destination.getParent(), ownership);
            Files.write(destination, entry.bytes(), CREATE_NEW, WRITE, NOFOLLOW_LINKS);
            ownership.record(destination);
            if (entry.portablePath().equals("gradlew")) {
                makeExecutableOnPosix(destination);
            }
        }
    }

    private void createOwnedParents(Path root, Path destinationParent, OwnedTree ownership) throws IOException {
        if (destinationParent == null || destinationParent.equals(root)) {
            return;
        }
        Path current = root;
        for (Path component : root.relativize(destinationParent)) {
            ownership.verify(root);
            current = current.resolve(component);
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) {
                    throw failure("Generated project path encountered a non-directory component");
                }
            } else {
                Files.createDirectory(current);
                ownership.record(current);
            }
        }
    }

    private void verifyTree(Path treeRoot, List<Entry> entries, OwnedTree ownership) throws IOException {
        ownership.verify(treeRoot);
        Set<String> expectedPaths = new HashSet<>();
        for (Entry entry : entries) {
            expectedPaths.add(entry.portablePath());
            Path parent = entry.relativePath().getParent();
            while (parent != null) {
                expectedPaths.add(parent.toString().replace('\\', '/') + "/");
                parent = parent.getParent();
            }
            Path file = treeRoot.resolve(entry.relativePath());
            ownership.verify(file);
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, NOFOLLOW_LINKS)
                    || !Arrays.equals(entry.bytes(), Files.readAllBytes(file))) {
                throw failure("Generated project publication verification failed");
            }
        }
        Set<String> observedPaths = new HashSet<>();
        try (var paths = Files.walk(treeRoot)) {
            for (Path path : paths.toList()) {
                ownership.verify(treeRoot);
                if (path.equals(treeRoot)) {
                    continue;
                }
                ownership.verify(path);
                if (Files.isSymbolicLink(path)) {
                    throw failure("Generated project publication contains a symbolic link");
                }
                String relative = treeRoot.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    observedPaths.add(relative + "/");
                } else if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    observedPaths.add(relative);
                } else {
                    throw failure("Generated project publication contains an unsupported entry");
                }
            }
        }
        if (!expectedPaths.equals(observedPaths)) {
            throw failure("Generated project publication contains unexpected entries");
        }
    }

    private void makeExecutableOnPosix(Path gradlew) throws IOException {
        FileStore store = Files.getFileStore(gradlew);
        if (store.supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(gradlew, EXECUTABLE_PERMISSIONS);
        }
    }

    private void cleanupOwnedTree(OwnedTree ownership) {
        List<Path> paths = ownership.pathsInReverseOrder();
        for (Path path : paths) {
            verifyParentIdentity(ownership.parent(), ownership.parentIdentity());
            ownership.verify(path);
            try {
                Files.delete(path);
            } catch (IOException exception) {
                throw GeneratorException.system(
                        SOURCE_GENERATION_FAILED, STAGE, "Owned generation path could not be cleaned", exception);
            }
        }
    }

    private void cleanupAfterFailure(GeneratorException failure, OwnedTree ownership) {
        if (ownership == null) {
            return;
        }
        try {
            cleanupOwnedTree(ownership);
        } catch (GeneratorException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void verifyIdentity(Path path, StablePathIdentity expected) {
        try {
            if (!expected.matches(path)) {
                throw failure("Owned generation path identity changed");
            }
        } catch (GeneratorException exception) {
            throw exception;
        } catch (IOException exception) {
            throw GeneratorException.system(
                    SOURCE_GENERATION_FAILED, STAGE, "Owned generation path identity could not be verified", exception);
        }
    }

    private GeneratorException failure(String message) {
        return GeneratorException.user(SOURCE_GENERATION_FAILED, STAGE, message);
    }

    @FunctionalInterface
    interface PublicationHook {
        void beforeTargetReservation(Path staging, Path target) throws IOException;
    }

    private final class OwnedTree {
        private final Path parent;
        private final ParentIdentity parentIdentity;
        private final Map<Path, StablePathIdentity> identities = new LinkedHashMap<>();

        private OwnedTree(Path parent, ParentIdentity parentIdentity) {
            this.parent = parent;
            this.parentIdentity = parentIdentity;
        }

        private void record(Path path) {
            try {
                identities.put(path, StablePathIdentity.capture(path));
            } catch (GeneratorException exception) {
                throw exception;
            } catch (IOException exception) {
                throw GeneratorException.system(
                        SOURCE_GENERATION_FAILED, STAGE, "Owned generation path could not be recorded", exception);
            }
        }

        private void verify(Path path) {
            StablePathIdentity expected = identities.get(path);
            if (expected == null) {
                throw failure("Generation cleanup encountered an unowned path");
            }
            verifyIdentity(path, expected);
        }

        private List<Path> pathsInReverseOrder() {
            return identities.keySet().stream().sorted(Comparator.reverseOrder()).toList();
        }

        private Path parent() {
            return parent;
        }

        private ParentIdentity parentIdentity() {
            return parentIdentity;
        }
    }

    private record Entry(Path relativePath, String portablePath, byte[] bytes) {}

    private record ParentIdentity(StablePathIdentity identity) {}
}
