package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.gen2spring.mcp.app.web.config.WebModeProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HostedModeContractTest {
    @Test
    void supportsOnlyExplicitLocalAndHostedModes() {
        assertEquals(WebModeProperties.Mode.LOCAL, new WebModeProperties(WebModeProperties.Mode.LOCAL).mode());
        assertEquals(WebModeProperties.Mode.HOSTED, new WebModeProperties(WebModeProperties.Mode.HOSTED).mode());
        assertEquals("Web runtime mode is invalid", assertThrows(
                IllegalArgumentException.class, () -> new WebModeProperties(null)).getMessage());
    }

    @Test
    void keepsHistoryOnTheDashboardAndStartsCreationInTheSharedEditor() throws Exception {
        String dashboard;
        String editor;
        String hosted;
        String fragment;
        try (var dashboardInput = getClass().getResourceAsStream("/templates/dashboard.html");
             var editorInput = getClass().getResourceAsStream("/templates/editor.html");
             var hostedInput = getClass().getResourceAsStream("/static/hosted.js");
             var fragmentInput = getClass().getResourceAsStream("/templates/fragments/ui.html")) {
            dashboard = new String(dashboardInput.readAllBytes(), StandardCharsets.UTF_8);
            editor = new String(editorInput.readAllBytes(), StandardCharsets.UTF_8);
            hosted = new String(hostedInput.readAllBytes(), StandardCharsets.UTF_8);
            fragment = new String(fragmentInput.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(dashboard.contains("href=\"/editor\""));
        assertTrue(dashboard.contains("새 MCP 프로젝트"));
        assertTrue(dashboard.contains("OpenAPI 문서"));
        assertTrue(dashboard.contains("작업 내역"));
        assertTrue(dashboard.contains("th:replace=\"~{fragments/ui :: app-header('hosted')}\""));
        assertTrue(dashboard.contains("th:href=\"@{/editor(specification=${spec.id.value})}\""));
        assertTrue(fragment.contains("meta name=\"app-mode\""));
        assertTrue(editor.contains("app-header(${appMode})"));
        assertFalse(dashboard.contains("id=\"hosted-upload-form\""));
        assertFalse(dashboard.contains("id=\"hosted-generation-form\""));
        assertFalse(hosted.contains("generation-configuration"));
    }
}
