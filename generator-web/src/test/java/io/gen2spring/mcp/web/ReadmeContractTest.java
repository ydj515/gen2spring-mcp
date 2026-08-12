package io.gen2spring.mcp.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReadmeContractTest {
    @Test
    void documentsTheInstalledLocalEditorAndItsBoundaries() throws Exception {
        Path root = repositoryRoot();
        String readme = Files.readString(root.resolve("README.md"));
        String mise = Files.readString(root.resolve("mise.toml"));

        assertTrue(readme.contains("./gradlew :generator-web:bootRun"));
        assertTrue(readme.contains("./gradlew :generator-web:bootJar"));
        assertTrue(readme.contains("java -jar generator-web/build/libs/generator-web.jar"));
        assertTrue(readme.contains("mise run ui"));
        assertTrue(readme.contains("mise run ui:build"));
        assertTrue(readme.contains("mise run ui:test"));
        assertTrue(readme.contains("public multi-user service가 아니다"));
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
        assertFalse(readme.contains(":generator-web:installDist"));
        assertFalse(readme.contains("X-Gen2Spring-Token"));
        assertFalse(readme.contains("per-process token"));

        assertTrue(mise.contains("[tasks.ui]"));
        assertTrue(mise.contains(":generator-web:bootRun"));
        assertTrue(mise.contains("[tasks.\"ui:build\"]"));
        assertTrue(mise.contains(":generator-web:bootJar"));
        assertTrue(mise.contains("[tasks.\"ui:test\"]"));
        assertTrue(mise.contains(":generator-web:test"));
        assertTrue(mise.contains("run_windows"));
        assertTrue(mise.contains("GEN2SPRING_JAVA_17_HOME"));
        assertTrue(mise.contains("GEN2SPRING_JAVA_21_HOME"));
        assertTrue(mise.contains("GEN2SPRING_UI_PORT"));
        assertFalse(mise.contains(":generator-web:installDist"));
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
