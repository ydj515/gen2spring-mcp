package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StaticAssetContractTest {
    @Test
    void exposesTheAccessibleThreeStepEndpointEditor() throws Exception {
        String index = resource("/templates/editor.html");
        String styles = resource("/static/styles.css");

        assertTrue(index.contains("<html lang=\"ko\""));
        assertTrue(index.contains("<h2 id=\"specification-title\">1. OpenAPI 파일</h2>"));
        assertTrue(index.contains("<h2 id=\"operations-title\">2. API endpoint 선택</h2>"));
        assertTrue(index.contains("<h2 id=\"generation-title\">3. 생성 설정</h2>"));
        assertFalse(index.contains("<h2>4."));
        assertFalse(index.contains("<h2>5."));
        assertTrue(index.contains("id=\"specification-file\" class=\"visually-hidden\" type=\"file\" accept=\".yaml,.yml,.json\""));
        assertTrue(index.contains("id=\"upload-dropzone\""));
        assertTrue(index.contains("data-upload-state=\"idle\""));
        assertTrue(index.contains("id=\"upload-live-status\""));
        assertTrue(index.contains("aria-live=\"polite\""));
        assertTrue(index.contains("id=\"replace-file-button\""));
        assertTrue(index.contains("id=\"remove-file-button\""));
        assertTrue(index.contains("id=\"operation-search\""));
        assertTrue(index.contains("id=\"select-all-operations\""));
        assertTrue(index.contains("id=\"operation-counts\""));
        assertTrue(index.contains("id=\"operation-list\""));
        assertTrue(index.contains("id=\"selected-tool-list\""));
        assertTrue(index.contains("id=\"operation-editor-home\""));
        assertTrue(index.contains("id=\"generation-summary\""));
        for (String id : new String[] {
                "summary-version", "summary-selected", "summary-excluded",
                "summary-warnings", "summary-profile", "summary-validation"
        }) {
            assertTrue(index.contains("id=\"" + id + "\""), id);
        }
        assertTrue(index.contains("id=\"target-profile\""));
        assertTrue(index.contains("id=\"preview-button\" type=\"button\" disabled"));
        assertTrue(index.contains("id=\"generate-button\""));
        assertTrue(index.contains("name=\"csrf-token\""));
        assertTrue(index.contains("name=\"csrf-header\""));

        assertTrue(styles.contains("--success: #148f77"));
        assertTrue(styles.contains("[data-upload-state=\"drag-over\"]"));
        assertTrue(styles.contains("min-height: 6.5rem"));
        assertTrue(styles.contains(".upload-dropzone[hidden] { display: none; }"));
        assertTrue(styles.contains("[data-support-status=\"UNSUPPORTED\"]"));
        assertTrue(styles.contains(":focus-visible"));
        assertTrue(styles.contains("min-height: 44px"));
        assertTrue(styles.contains("@media (max-width: 400px)"));
        assertTrue(styles.contains(".endpoint-toolbar .grow { flex-basis: auto; }"));
        assertTrue(styles.contains(".generation-summary { position: sticky;"));
        assertTrue(styles.contains(".generation-layout { grid-template-columns:"));
        assertTrue(styles.contains(".generation-summary { position: static; }"));
        assertTrue(styles.contains(".selected-tool-summary-content { display: grid;"));
    }

    @Test
    void assignsUploadAndEndpointSelectionToDedicatedStateOwners() throws Exception {
        String app = resource("/static/app.js");
        String api = resource("/static/api.js");
        String state = resource("/static/state.js");
        String upload = resource("/static/upload.js");
        String operations = resource("/static/operations.js");
        String editor = resource("/static/editor.js");
        String scripts = app + api + state + upload + operations + editor;

        for (String event : new String[] {"dragenter", "dragover", "dragleave", "drop", "change", "keydown"}) {
            assertTrue(upload.contains("'" + event + "'"), event);
        }
        assertTrue(upload.contains("idle"));
        assertTrue(upload.contains("drag-over"));
        assertTrue(upload.contains("analyzing"));
        assertTrue(upload.contains("completed"));
        assertTrue(upload.contains("error"));
        assertTrue(upload.contains("file.name"));
        assertTrue(upload.contains("file.size"));
        assertTrue(upload.contains("resetSpecificationState"));
        assertTrue(operations.contains("operation.issues"));
        assertTrue(operations.contains("operation.status"));
        assertTrue(operations.contains("operation.supported"));
        assertTrue(operations.contains("selectable"));
        assertTrue(operations.contains("textContent"));
        assertTrue(operations.contains("operation-search"));
        assertTrue(operations.contains("select-all-operations"));
        assertTrue(operations.contains("preservedSelection"));
        assertTrue(editor.contains("document.createElement('details')"));
        assertTrue(editor.contains("기본값"));
        assertTrue(editor.contains("사용자 설정"));
        assertTrue(editor.contains("operation-editor-home"));
        assertTrue(editor.contains("selected-tool-list"));
        assertTrue(editor.contains("selected-tool-summary-content"));
        assertTrue(editor.contains("if (!openOperationId) return;"));
        assertTrue(state.contains("resetSpecificationState"));
        assertTrue(state.contains("operation.supported"));
        assertTrue(api.contains("X-Specification-Name"));
        assertTrue(api.contains("/api/specifications/uploads"));
        assertTrue(api.contains("meta[name=\"app-mode\"]"));
        assertTrue(app.contains("initializeUpload"));
        assertTrue(app.contains("initializeOperations"));
        assertTrue(app.contains("onAnalysis: analysis => {\n    ui['preview-button'].disabled = false;"));
        assertTrue(app.contains("function resetSpecificationPresentation() {\n  ui['preview-button'].disabled = true;"));
        assertTrue(app.contains("renderGenerationSummary"));
        assertTrue(app.contains("['VALIDATED', 'UNVERIFIED', 'SUCCEEDED', 'FAILED', 'CANCELLED']"));
        assertTrue(app.contains("ui['delete-job-button'].disabled = api.hostedMode;"));
        assertTrue(api.contains("crypto.randomUUID()"));
        assertTrue(api.contains("Idempotency-Key"));
        assertTrue(api.contains("'/api/jobs'"));
        assertTrue(api.contains("/cancellation"));
        assertTrue(api.contains("/api/artifacts/"));

        assertFalse(scripts.contains("innerHTML"));
        assertFalse(scripts.contains("Authorization"));
        assertFalse(scripts.contains("js-yaml"));
        assertFalse(scripts.contains("SwaggerParser"));
        assertFalse(scripts.contains("X-Gen2Spring-Token"));
        assertFalse(state.contains("secretValue"));
    }

    @Test
    void preservesTheStrictAdvancedGenerationContracts() throws Exception {
        String index = resource("/templates/editor.html");
        String editor = resource("/static/editor.js");
        String api = resource("/static/api.js");

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
        assertTrue(index.contains("id=\"tool-description\" maxlength=\"1024\""));
        assertTrue(editor.contains("['USER_INPUT', 'SERVER_SECRET']"));
        assertTrue(editor.contains("Number.isSafeInteger"));
        assertTrue(editor.contains("parseSafeJson(value('validation-arguments'))"));
        assertTrue(editor.contains("Success values must be one valid JSON array."));
        assertTrue(editor.contains("Every server secret needs an environment variable name."));
        assertTrue(editor.contains("output: {mode: operation.outputMode}"));
        assertTrue(api.contains("SAFE_METHODS"));
        assertTrue(api.contains("headers.set(csrfHeader, csrfToken)"));
    }

    private String resource(String path) throws IOException {
        try (var input = StaticAssetContractTest.class.getResourceAsStream(path)) {
            return input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
