package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StaticAssetContractTest {
    @Test
    void exposesTheAccessibleFourStepEndpointEditor() throws Exception {
        String index = resource("/templates/editor.html");
        String styles = resource("/static/styles.css");

        assertTrue(index.contains("<html lang=\"ko\""));
        assertTrue(index.contains("<h2 id=\"specification-title\" tabindex=\"-1\">1. OpenAPI 파일</h2>"));
        assertTrue(index.contains("<h2 id=\"operations-title\" tabindex=\"-1\">2. API endpoint 선택</h2>"));
        assertTrue(index.contains("<h2 id=\"generation-title\" tabindex=\"-1\">3. 생성 설정</h2>"));
        assertTrue(index.contains("<h2 id=\"generation-run-title\" tabindex=\"-1\">4. 생성 및 결과</h2>"));
        assertFalse(index.contains("<h2>5."));

        assertTrue(index.contains("class=\"wizard-steps\""));
        assertTrue(index.contains("aria-current=\"step\""));
        assertTrue(index.contains("data-step=\"1\""));
        assertTrue(index.contains("data-step=\"4\""));
        assertTrue(index.contains("class=\"workflow-step wizard-panel\""));
        assertTrue(index.contains("id=\"wizard-live\""));
        assertTrue(index.contains("id=\"step-back-3\""));
        assertTrue(index.contains("id=\"step-next-3\""));
        assertTrue(index.contains("id=\"step-hint-3\""));
        assertTrue(index.contains("tabindex=\"-1\""));
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

        assertTrue(styles.contains("--primary: #4f46e5"));
        assertTrue(styles.contains("--surface: #ffffff"));
        assertTrue(styles.contains("--line: #e2e8f0"));
        assertTrue(styles.contains("--text: #0f172a"));
        assertTrue(styles.contains("--muted: #475569"));
        assertTrue(styles.contains("--success: #10b981"));
        assertTrue(styles.contains("--success-text: #047857"));
        assertTrue(styles.contains("--warning-text: #92400e"));
        assertTrue(styles.contains("--danger-text: #b91c1c"));
        assertTrue(styles.contains("color: var(--success-text)"));
        assertFalse(styles.contains("#148f77"));
        assertFalse(styles.contains("#2455a6"));
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
    void ownsStepNavigationInADedicatedWizardModule() throws Exception {
        String wizard = resource("/static/wizard.js");
        String state = resource("/static/state.js");
        String app = resource("/static/app.js");

        assertTrue(wizard.contains("export function canAdvance(step, state)"));
        assertTrue(wizard.contains("export function initializeWizard"));
        // Local mode has no analysis route, so a retained specificationId can name
        // a specification whose operations were never re-fetched. Gate 1 must read
        // the in-memory analysis or a reload strands the user on an empty step 2.
        assertTrue(wizard.contains("if (step === 1) return Boolean(state.analysis);"));
        assertFalse(wizard.contains("Boolean(state.specificationId)"));
        assertTrue(wizard.contains("hashchange"));
        assertTrue(wizard.contains("aria-current"));
        assertTrue(wizard.contains("focus()"));
        // A disabled next button must always name what is blocking it.
        assertTrue(wizard.contains("export function blockingReason(step, state)"));
        assertTrue(wizard.contains("aria-invalid"));
        assertTrue(wizard.contains("항목을 채워 주세요."));
        assertTrue(state.contains("currentStep"));
        assertTrue(state.contains("gen2spring.currentStep"));
        assertTrue(app.contains("initializeWizard"));
        assertTrue(app.contains("syncGate"));
        assertFalse(wizard.contains("innerHTML"));
    }

    @Test
    void summarizesGenerationProgressWithoutLosingStageDetail() throws Exception {
        String index = resource("/templates/editor.html");
        String progress = resource("/static/progress.js");
        String app = resource("/static/app.js");
        String styles = resource("/static/styles.css");

        assertTrue(index.contains("id=\"job-progress\""));
        assertTrue(index.contains("id=\"job-stage-label\""));
        assertTrue(index.contains("id=\"job-progress-value\""));
        assertTrue(index.contains("id=\"job-progress-fill\""));
        assertTrue(index.contains("id=\"job-progress-details\""));
        assertTrue(index.contains("id=\"progress-list\""));
        assertTrue(index.contains("<summary>상세 보기</summary>"));

        assertTrue(progress.contains("export function renderProgress(snapshot)"));
        assertTrue(progress.contains("OpenAPI 문서 분석"));
        assertTrue(progress.contains("Spring 컨텍스트 기동"));
        assertTrue(progress.contains("대표 Tool 호출 검증"));
        assertTrue(progress.contains("산출물 패키징"));
        assertTrue(progress.contains("'SKIPPED'"));
        assertTrue(app.contains("renderProgress(snapshot)"));
        assertTrue(styles.contains(".progress-fill"));
        // A failure marks every later stage SKIPPED. Counting the full list would
        // render a build that died at stage 3 as 88% complete.
        assertTrue(progress.contains("const counted = failed ? stages.slice(0, failedIndex) : stages;"));
        assertTrue(styles.contains(".progress-fill[data-state=\"failed\"] { background: var(--danger); }"));

        assertFalse(progress.contains("innerHTML"));
        assertFalse(progress.contains("EventSource"));
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
        assertTrue(upload.contains("let requestVersion = 0;"));
        assertTrue(upload.contains("if (version !== requestVersion) return;"));
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
        assertTrue(api.contains("export async function analysis(specificationId)"));
        assertTrue(api.contains("/api/specifications/${specificationId}/analysis"));
        assertTrue(api.contains("meta[name=\"app-mode\"]"));
        assertTrue(app.contains("initializeUpload"));
        assertTrue(app.contains("initializeOperations"));
        assertTrue(app.contains("resumeRetainedSpecification"));
        assertTrue(app.contains("await resumeRetainedSpecification();\n  await resumeRetainedJob();"));
        assertTrue(app.contains("onAnalysis: analysis => {\n    ui['preview-button'].disabled = false;"));
        assertTrue(app.contains("function resetSpecificationPresentation() {\n  ui['preview-button'].disabled = true;"));
        assertTrue(app.contains("renderGenerationSummary"));
        assertTrue(app.contains("['VALIDATED', 'UNVERIFIED', 'SUCCEEDED', 'FAILED', 'CANCELLED']"));
        assertTrue(app.contains("ui['delete-job-button'].disabled = api.hostedMode;"));
        assertTrue(app.contains("ui['delete-job-button'].disabled = !api.hostedMode;"));
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
