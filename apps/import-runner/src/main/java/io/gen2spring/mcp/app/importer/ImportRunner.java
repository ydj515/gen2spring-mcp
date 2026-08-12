package io.gen2spring.mcp.app.importer;

import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class ImportRunner {
    private static final int MAX_TARGET_BYTES = 4096;
    private static final Set<PosixFilePermission> WRITE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE);

    private final SpecificationAnalyzer analyzer;
    private final ImportGatewayClient gateway;
    private final int maxSpecificationBytes;

    public ImportRunner(
            SpecificationAnalyzer analyzer,
            ImportGatewayClient gateway,
            int maxSpecificationBytes) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        if (maxSpecificationBytes < 1 || maxSpecificationBytes > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("Import runner configuration is invalid");
        }
        this.maxSpecificationBytes = maxSpecificationBytes;
    }

    public ImportResult run(Path targetFile, Path workspace) {
        byte[] targetBytes = null;
        byte[] fetchedBytes = null;
        Path specification = null;
        try {
            BasicFileAttributes before = requireTarget(targetFile);
            targetBytes = Files.readAllBytes(targetFile);
            if (targetBytes.length < 1 || targetBytes.length > MAX_TARGET_BYTES) {
                throw new IllegalArgumentException();
            }
            BasicFileAttributes after = requireTarget(targetFile);
            if (!stable(before, after)) {
                throw new IllegalArgumentException();
            }
            ImportTarget target = ImportTarget.parse(decodeUtf8(targetBytes));
            ImportGatewayClient.Fetched fetched = gateway.fetch(target);
            fetchedBytes = fetched.source();
            if (fetchedBytes.length < 1 || fetchedBytes.length > maxSpecificationBytes) {
                throw new IllegalArgumentException();
            }

            requireWorkspace(workspace);
            specification = workspace.resolve("specification-source." + fetched.extension());
            Files.write(
                    specification,
                    fetchedBytes,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            setOwnerReadWrite(specification);
            SpecificationAnalyzer.AnalysisResult analysis = analyzer.analyze(
                    specification, maxSpecificationBytes);
            return new ImportResult(analysis.originalSpecification(), fetched.mediaType());
        } catch (Exception failure) {
            throw new ImportRunnerFailure();
        } finally {
            if (targetBytes != null) {
                Arrays.fill(targetBytes, (byte) 0);
            }
            if (fetchedBytes != null) {
                Arrays.fill(fetchedBytes, (byte) 0);
            }
            deleteOwned(specification);
            deleteOwned(workspace);
        }
    }

    private BasicFileAttributes requireTarget(Path targetFile) throws Exception {
        if (targetFile == null || !targetFile.isAbsolute() || Files.isSymbolicLink(targetFile)) {
            throw new IllegalArgumentException();
        }
        BasicFileAttributes attributes = Files.readAttributes(
                targetFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > MAX_TARGET_BYTES) {
            throw new IllegalArgumentException();
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    targetFile, LinkOption.NOFOLLOW_LINKS);
            if (permissions.stream().anyMatch(WRITE_PERMISSIONS::contains)) {
                throw new IllegalArgumentException();
            }
        } catch (UnsupportedOperationException ignored) {
            // The container deployment mounts this file read-only; Windows has no POSIX permission view.
        }
        return attributes;
    }

    private boolean stable(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(before.fileKey(), after.fileKey());
    }

    private void requireWorkspace(Path workspace) throws Exception {
        if (workspace == null || !workspace.isAbsolute() || Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException();
        }
        Files.createDirectory(workspace);
        setOwnerReadWriteExecute(workspace);
        if (Files.isSymbolicLink(workspace) || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException();
        }
    }

    private String decodeUtf8(byte[] value) throws Exception {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value));
        return decoded.toString();
    }

    private void setOwnerReadWrite(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows container images use ACL provisioning instead.
        }
    }

    private void setOwnerReadWriteExecute(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // Windows container images use ACL provisioning instead.
        }
    }

    private void deleteOwned(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (!Files.isSymbolicLink(path)) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // The fixed failure remains authoritative; the sandbox is removed by the worker.
        }
    }

    public record ImportResult(byte[] source, String mediaType) {
        public ImportResult {
            source = Arrays.copyOf(Objects.requireNonNull(source, "source"), source.length);
            Objects.requireNonNull(mediaType, "mediaType");
        }

        @Override
        public byte[] source() {
            return Arrays.copyOf(source, source.length);
        }
    }
}
