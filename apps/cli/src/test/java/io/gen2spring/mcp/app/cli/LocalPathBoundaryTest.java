package io.gen2spring.mcp.app.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalPathBoundaryTest {
    @TempDir
    Path tempDir;

    private final LocalPathBoundary boundary = new LocalPathBoundary();

    @Test
    void readsOnlyAStablePhysicalRegularFile() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path input = Files.writeString(safeTemp.resolve("input.yaml"), "safe");
        var snapshot = boundary.regularFile(input, "Input");

        assertArrayEquals("safe".getBytes(StandardCharsets.UTF_8), snapshot.readBounded(16));

        Files.delete(input);
        Files.writeString(input, "replacement");
        assertThrows(LocalPathBoundary.PathBoundaryException.class, snapshot::verifyStable);
    }

    @Test
    void rejectsEveryAncestorSymbolicLink() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path real = Files.createDirectory(safeTemp.resolve("real"));
        Path file = Files.writeString(real.resolve("input.yaml"), "safe");
        Path link = safeTemp.resolve("linked");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThrows(LocalPathBoundary.PathBoundaryException.class,
                () -> boundary.regularFile(link.resolve(file.getFileName()), "Input"));
    }

    @Test
    void detectsOutputParentReplacementBeforePublication() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path parent = Files.createDirectory(safeTemp.resolve("parent"));
        var output = boundary.newFile(parent.resolve("analysis.json"), "Analysis output");
        Path moved = safeTemp.resolve("moved-parent");

        Files.move(parent, moved);
        Files.createDirectory(parent);

        assertThrows(LocalPathBoundary.PathBoundaryException.class, output::verifyAvailable);
    }

    @Test
    void rejectsMacStyleAliasInsteadOfCanonicalizingIt() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path real = Files.createDirectory(safeTemp.resolve("physical"));
        Path alias = safeTemp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThrows(LocalPathBoundary.PathBoundaryException.class,
                () -> boundary.newFile(alias.resolve("output.json"), "Output"));
    }
}
