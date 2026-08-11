package io.gen2spring.mcp.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StaticAssetContractTest {
    @Test
    void exposesTheAccessibleFiveStepLocalEditorWithoutExternalInputs() throws Exception {
        String index = resource("/web/index.html");
        String styles = resource("/web/styles.css");
        String app = resource("/web/app.js");
        String editor = resource("/web/editor.js");
        String scripts = app + resource("/web/api.js") + resource("/web/state.js") + editor;
        String all = index + styles + scripts;

        assertTrue(index.contains("<h2>1. Specification</h2>"));
        assertTrue(index.contains("<h2>2. Operations</h2>"));
        assertTrue(index.contains("<h2>3. Project and Target</h2>"));
        assertTrue(index.contains("<h2>4. Preview</h2>"));
        assertTrue(index.contains("<h2>5. Generate and Download</h2>"));
        assertTrue(index.contains("accept=\".yaml,.yml,.json\""));
        assertTrue(index.contains("id=\"error-summary\""));
        assertTrue(index.contains("aria-live=\"polite\""));
        assertTrue(index.contains("id=\"operation-filter\""));
        assertTrue(index.contains("id=\"target-profile\""));
        assertTrue(index.contains("id=\"preview-button\""));
        assertTrue(index.contains("id=\"generate-button\""));
        assertTrue(index.contains("id=\"progress-list\""));
        assertTrue(styles.contains("@media (max-width: 720px)"));
        assertTrue(styles.contains(":focus-visible"));
        assertTrue(styles.contains("min-height: 44px"));
        assertTrue(scripts.contains("addEventListener"));
        assertTrue(scripts.contains("textContent"));
        assertTrue(scripts.contains("sessionStorage"));
        assertTrue(scripts.contains("500"));
        assertTrue(scripts.contains("2000"));
        assertTrue(scripts.contains("Success values must be one valid JSON array."));
        assertTrue(scripts.contains("Every server secret needs an environment variable name."));
        assertTrue(editor.contains("['USER_INPUT', 'SERVER_SECRET']"));
        assertTrue(editor.contains("if (id === 'operation-enabled') renderValidationOperations();"));
        assertTrue(editor.contains("Number.isSafeInteger"));
        assertTrue(editor.contains("parseSafeJson(value('validation-arguments'))"));
        assertTrue(editor.contains("parseSafeJson(policy.successValuesText)"));
        assertTrue(app.contains("resumeRetainedJob();"));
        assertTrue(app.contains("await pollJob(jobId);"));
        assertTrue(app.contains("else ui['delete-job-button'].disabled = false;"));
        assertTrue(index.contains("id=\"tool-description\" maxlength=\"1024\""));

        assertFalse(index.contains("type=\"url\""));
        assertFalse(index.matches("(?s).*<(?:script|style)[^>]*>\\s*[^<]+.*"));
        assertFalse(all.matches("(?s).*https?://.*"));
        assertFalse(all.contains("localStorage"));
        assertFalse(all.contains("serviceWorker"));
        assertFalse(scripts.contains("innerHTML"));
        assertFalse(scripts.contains("Authorization"));
        assertFalse(editor.contains("SERVER_DEFAULT"));
        assertFalse(index.contains("id=\"tool-description\" maxlength=\"2048\""));
    }

    private String resource(String path) throws IOException {
        try (var input = StaticAssetContractTest.class.getResourceAsStream(path)) {
            if (input == null) {
                return "";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
