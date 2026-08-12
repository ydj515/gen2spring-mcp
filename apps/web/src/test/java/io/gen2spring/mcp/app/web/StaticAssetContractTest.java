package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StaticAssetContractTest {
    @Test
    void exposesTheAccessibleFiveStepLocalEditorWithoutExternalInputs() throws Exception {
        String index = resource("/templates/editor.html");
        String styles = resource("/static/styles.css");
        String app = resource("/static/app.js");
        String editor = resource("/static/editor.js");
        String api = resource("/static/api.js");
        String scripts = app + api + resource("/static/state.js") + editor;
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
        for (String id : new String[] {
                "output-mode",
                "retry-enabled", "retry-status-codes", "retry-network-errors", "retry-max-retries",
                "retry-initial-backoff", "retry-max-backoff", "retry-respect-retry-after",
                "pagination-enabled", "pagination-request-parameter", "pagination-initial-value",
                "pagination-items-path", "pagination-next-value-path", "pagination-max-pages",
                "pagination-max-items"
        }) {
            assertTrue(index.contains("id=\"" + id + "\""), id);
            assertTrue(index.contains("for=\"" + id + "\""), id + " label");
        }
        assertTrue(index.contains("<option value=\"GENERIC_JSON\">"));
        assertTrue(index.contains("<option value=\"TYPED\">"));
        assertTrue(index.contains("id=\"retry-max-retries\" type=\"number\" min=\"1\" max=\"3\""));
        assertTrue(index.contains("id=\"retry-initial-backoff\" type=\"number\" min=\"1\" max=\"5000\""));
        assertTrue(index.contains("id=\"retry-max-backoff\" type=\"number\" min=\"1\" max=\"10000\""));
        assertTrue(index.contains("id=\"pagination-max-pages\" type=\"number\" min=\"2\" max=\"20\""));
        assertTrue(index.contains("id=\"pagination-max-items\" type=\"number\" min=\"1\" max=\"2000\""));
        assertTrue(styles.contains("@media (max-width: 400px)"));
        assertTrue(editor.contains("normalizeRetryStatusCodes"));
        assertTrue(editor.contains("Retry status codes must be unique HTTP error integers."));
        assertTrue(editor.contains("Pagination initial value must be one JSON string or integer."));
        assertTrue(editor.contains("Number.isSafeInteger"));
        assertTrue(editor.contains("...(operation.retry.enabled ? {retry:"));
        assertTrue(editor.contains("...(operation.pagination.enabled ? {pagination:"));
        assertTrue(editor.contains("output: {mode: operation.outputMode}"));
        assertTrue(editor.contains("delete pagination.initialValue"));
        assertTrue(resource("/static/state.js").contains("outputMode: 'GENERIC_JSON'"));
        assertTrue(resource("/static/state.js").contains("retry: {"));
        assertTrue(resource("/static/state.js").contains("pagination: {"));
        assertTrue(index.contains("name=\"csrf-token\""));
        assertTrue(index.contains("name=\"csrf-header\""));
        assertTrue(api.contains("SAFE_METHODS"));
        assertTrue(api.contains("headers.set(csrfHeader, csrfToken)"));

        assertFalse(index.contains("type=\"url\""));
        assertFalse(index.matches("(?s).*<(?:script|style)[^>]*>\\s*[^<]+.*"));
        assertFalse(all.matches("(?s).*https?://.*"));
        assertFalse(all.contains("localStorage"));
        assertFalse(all.contains("serviceWorker"));
        assertFalse(scripts.contains("innerHTML"));
        assertFalse(scripts.contains("Authorization"));
        assertFalse(index.contains("generator-api-token"));
        assertFalse(api.contains("X-Gen2Spring-Token"));
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
