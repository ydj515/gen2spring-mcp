package io.gen2spring.mcp.app.web;

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
        String userGuide = Files.readString(root.resolve("docs/user-guide.md"));
        String mise = Files.readString(root.resolve("mise.toml"));

        assertTrue(readme.lines().count() <= 200, "Root README should remain a concise landing page");
        assertTrue(readme.contains("[사용자 가이드](docs/user-guide.md)"));
        assertTrue(userGuide.contains(":apps:web:bootJar"));
        assertTrue(userGuide.contains("apps/web/build/libs/web.jar"));
        assertTrue(userGuide.contains("mise run dev"));
        assertTrue(userGuide.contains("mise run ui:build"));
        assertTrue(userGuide.contains("mise run ui:test"));
        assertTrue(userGuide.contains("public multi-user service가 아니다"));
        assertTrue(userGuide.contains("numeric loopback only"));
        assertTrue(userGuide.contains("local files only; no URL import"));
        assertTrue(userGuide.contains("one running plus one queued job"));
        assertFalse(userGuide.contains("UI operation editor complete"));
        assertTrue(userGuide.contains("OpenAPI 파일, Endpoint 선택, 생성 설정, 설정 검증 및 프로젝트 생성, 생성 진행의 5단계"));
        assertTrue(userGuide.contains("지원 불가 항목은 이유와 함께 비활성화"));
        assertTrue(userGuide.contains("supported JSON object response에서 typed output DTO를 생성한다"));
        assertTrue(userGuide.contains("GET operation에 bounded retry를 실행"));
        assertTrue(userGuide.contains("GET operation에 bounded pagination을 실행"));
        assertTrue(userGuide.contains("https://github.com/ydj515/gen2spring-mcp/issues/2"));
        assertFalse(userGuide.contains("Windows validation host remains follow-up P1"));
        assertFalse(userGuide.contains(
                "Generator API와 UI operation editor, Windows validation host 지원은 후속 P1 범위다"));
        assertFalse(userGuide.contains(":apps:web:installDist"));
        assertFalse(userGuide.contains("X-Gen2Spring-Token"));
        assertFalse(userGuide.contains("per-process token"));

        assertTrue(mise.contains("[tasks.dev]"));
        assertTrue(mise.contains(
                ":apps:web:bootJar --quiet --no-daemon --non-interactive"));
        assertTrue(mise.contains("[tasks.\"ui:build\"]"));
        assertTrue(mise.contains(":apps:web:bootJar"));
        assertTrue(mise.contains("[tasks.\"ui:test\"]"));
        assertTrue(mise.contains(":apps:web:test"));
        assertTrue(mise.contains("run_windows"));
        assertTrue(mise.contains("GEN2SPRING_JAVA_17_HOME"));
        assertTrue(mise.contains("GEN2SPRING_JAVA_21_HOME"));
        assertTrue(mise.contains("GEN2SPRING_UI_PORT"));
        assertFalse(mise.contains(":apps:web:installDist"));
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
