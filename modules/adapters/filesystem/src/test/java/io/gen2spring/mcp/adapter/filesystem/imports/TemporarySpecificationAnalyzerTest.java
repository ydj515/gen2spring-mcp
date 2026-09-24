package io.gen2spring.mcp.adapter.filesystem.imports;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.generation.analysis.AnalysisResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporarySpecificationAnalyzerTest {
    @TempDir
    Path workRoot;

    @Test
    void passesExactBytesAndLimitToParserAndRemovesPrivateCopy() throws Exception {
        byte[] source = "{\"openapi\":\"3.1.0\"}".getBytes(StandardCharsets.UTF_8);
        AtomicReference<Path> observed = new AtomicReference<>();
        var analyzer = new TemporarySpecificationAnalyzer((path, maxBytes) -> {
            observed.set(path);
            assertEquals(1024, maxBytes);
            assertTrue(path.getFileName().toString().endsWith(".json"));
            try {
                return new AnalysisResult(null, Files.readAllBytes(path));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }, workRoot);

        assertArrayEquals(source, analyzer.analyze(source, "application/problem+json", 1024));
        assertFalse(Files.exists(observed.get()));
        assertEmpty();
    }

    @Test
    void cleansPrivateCopyOnParserFailureAndPreservesTheCause() throws Exception {
        IllegalArgumentException failure = new IllegalArgumentException("parser failure");
        var analyzer = new TemporarySpecificationAnalyzer((path, maxBytes) -> {
            assertTrue(path.getFileName().toString().endsWith(".yaml"));
            throw failure;
        }, workRoot);

        var thrown = assertThrows(IllegalStateException.class,
                () -> analyzer.analyze(new byte[] {1}, "application/yaml", 1024));

        assertSame(failure, thrown.getCause());
        assertEmpty();
    }

    @Test
    void cleansPrivateCopyOnFatalFailureWithoutReplacingIt() throws Exception {
        AssertionError failure = new AssertionError("fatal parser failure");
        var analyzer = new TemporarySpecificationAnalyzer((path, maxBytes) -> { throw failure; }, workRoot);
        assertSame(failure, assertThrows(AssertionError.class,
                () -> analyzer.analyze(new byte[] {1}, "application/yaml", 1024)));
        assertEmpty();
    }

    @Test
    void rejectsInvalidWorkspaceAndOversizedInputBeforeParsing() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new TemporarySpecificationAnalyzer((path, maxBytes) -> null, Path.of("relative")));
        var analyzer = new TemporarySpecificationAnalyzer((path, maxBytes) -> {
            throw new AssertionError("Parser must not run");
        }, workRoot);
        assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze(new byte[] {1, 2}, "application/yaml", 1));
        assertEmpty();
    }

    private void assertEmpty() throws Exception {
        try (var files = Files.list(workRoot)) {
            assertEquals(0, files.count());
        }
    }
}
