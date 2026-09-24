package io.gen2spring.mcp.app.importer.infrastructure.analysis;

import io.gen2spring.mcp.app.importer.application.imports.ImportRunnerFailure;
import io.gen2spring.mcp.app.importer.application.imports.port.out.ImportSpecificationAnalyzer;
import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;

public final class WorkspaceSpecificationAnalyzer implements ImportSpecificationAnalyzer {
    private final SpecificationAnalyzer analyzer;
    private final Path workspace;
    private final int maxBytes;

    public WorkspaceSpecificationAnalyzer(SpecificationAnalyzer analyzer, Path workspace, int maxBytes) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Import size limit is invalid");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public byte[] analyze(byte[] source, String mediaType) {
        Path specification = null;
        boolean workspaceCreated = false;
        try {
            if (!workspace.isAbsolute() || Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException();
            }
            Files.createDirectory(workspace);
            workspaceCreated = true;
            setPermissions(workspace, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            if (Files.isSymbolicLink(workspace) || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException();
            }
            String extension = "application/json".equals(mediaType) || mediaType.endsWith("+json")
                    ? "json" : "yaml";
            specification = workspace.resolve("specification-source." + extension);
            Files.write(specification, source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            setPermissions(specification, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return analyzer.analyze(specification, maxBytes).originalSpecification();
        } catch (Exception failure) {
            throw new ImportRunnerFailure();
        } finally {
            deleteOwned(specification);
            if (workspaceCreated) {
                deleteOwned(workspace);
            }
        }
    }

    private void setPermissions(Path path, EnumSet<PosixFilePermission> permissions) throws Exception {
        try {
            Files.setPosixFilePermissions(path, permissions);
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
            // The sandbox is removed by the worker after the fixed failure is returned.
        }
    }
}
