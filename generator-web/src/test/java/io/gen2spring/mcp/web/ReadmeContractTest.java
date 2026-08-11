package io.gen2spring.mcp.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReadmeContractTest {
    @Test
    void documentsTheInstalledLocalEditorAndItsBoundaries() throws Exception {
        String readme = Files.readString(repositoryRoot().resolve("README.md"));

        assertTrue(readme.contains("./gradlew :generator-web:installDist"));
        assertTrue(readme.contains(
                "generator-web/build/install/gen2spring-mcp-web/bin/gen2spring-mcp-web --port 0"));
        assertTrue(readme.contains("numeric loopback only"));
        assertTrue(readme.contains("local files only; no URL import"));
        assertTrue(readme.contains("one running plus one queued job"));
        assertTrue(readme.contains("UI operation editor complete"));
        assertTrue(readme.contains("supported JSON object response에서 typed output DTO를 생성한다"));
        assertTrue(readme.contains("GET operation에 bounded retry를 실행한다"));
        assertTrue(readme.contains("GET operation에 bounded pagination을 실행한다"));
        assertTrue(readme.contains("https://github.com/ydj515/gen2spring-mcp/issues/2"));
        assertFalse(readme.contains("Windows validation host remains follow-up P1"));
        assertFalse(readme.contains(
                "Generator API와 UI operation editor, Windows validation host 지원은 후속 P1 범위다"));
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Unable to locate the repository root");
        }
        return current;
    }
}
