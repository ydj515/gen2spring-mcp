package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.app.web.security.WebSecurityConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StaticAssetContractTest {
    @Test
    void packagesTheLocalDesignSystemWithoutWeakeningTheCsp() throws Exception {
        String styles = resource("/static/styles.css");
        String tokens = resource("/static/design-tokens.css");
        String shell = resource("/static/app-shell.css");
        String editor = resource("/static/editor.css");
        String hosted = resource("/static/hosted.css");

        assertTrue(tokens.contains("--app-bg: #f6f8fc"));
        assertTrue(tokens.contains("--app-primary: #3568f4"));
        assertTrue(tokens.contains("--app-danger-soft: #fff0f1"));
        assertTrue(tokens.contains("@font-face"));
        assertTrue(tokens.contains("/webjars/bootstrap-icons/1.13.1/font/fonts/bootstrap-icons.woff2"));
        assertFalse(tokens.contains("bootstrap-icons.woff2?"));
        assertTrue(styles.contains("@import url('/design-tokens.css')"));
        assertTrue(styles.contains("@import url('/app-shell.css')"));
        assertTrue(styles.contains("@import url('/editor.css')"));
        assertTrue(styles.contains("@import url('/hosted.css')"));
        assertTrue(styles.indexOf("design-tokens.css") < styles.indexOf("app-shell.css"));
        assertTrue(styles.indexOf("app-shell.css") < styles.indexOf("editor.css"));
        assertTrue(styles.indexOf("editor.css") < styles.indexOf("hosted.css"));
        assertFalse(shell.isBlank());
        assertFalse(editor.isBlank());
        assertFalse(hosted.isBlank());
        assertTrue(WebSecurityConfiguration.CONTENT_SECURITY_POLICY.contains("font-src 'self'"));
    }

    @Test
    void sharesOneNoSidebarApplicationShellAcrossEveryScreen() throws Exception {
        String fragment = resource("/templates/fragments/ui.html");
        String editor = resource("/templates/editor.html");
        String dashboard = resource("/templates/dashboard.html");
        String jobDetail = resource("/templates/job-detail.html");

        assertTrue(fragment.contains("th:fragment=\"head-assets(title)\""));
        assertTrue(fragment.contains("/webjars/bootstrap/5.3.8/css/bootstrap.min.css"));
        assertTrue(fragment.contains("/webjars/bootstrap-icons/1.13.1/font/bootstrap-icons.min.css"));
        assertTrue(fragment.contains("th:fragment=\"app-header(appMode)\""));
        assertTrue(fragment.contains("th:if=\"${appMode == 'hosted'}\""));
        assertFalse(fragment.contains("bootstrap.bundle"));
        for (String template : new String[] {editor, dashboard, jobDetail}) {
            assertTrue(template.contains("<html lang=\"ko\""));
            assertTrue(template.contains("th:replace=\"~{fragments/ui :: head-assets"));
            assertTrue(template.contains("th:replace=\"~{fragments/ui :: app-header"));
            assertFalse(template.contains("app-sidebar"));
        }
        assertTrue(dashboard.contains("href=\"/editor\""));
        assertTrue(jobDetail.contains("href=\"/\""));
    }

    @Test
    void keepsWizardContextAndActionsVisibleWhileLongStepsScroll() throws Exception {
        String fragment = resource("/templates/fragments/ui.html");
        String editor = resource("/templates/editor.html");
        String editorStyles = resource("/static/editor.css");
        String wizard = resource("/static/wizard.js");

        assertTrue(fragment.contains("class=\"wizard-step-connector\""));
        assertTrue(editor.contains("class=\"generation-summary summary-strip\""));
        assertTrue(editor.indexOf("id=\"generation-summary\"")
                < editor.indexOf("id=\"specification-step\""));
        assertTrue(editorStyles.contains("--action-dock-clearance"));
        assertTrue(editorStyles.contains(".wizard-nav {\n  position: fixed;"));
        assertTrue(editorStyles.contains("scroll-padding-inline"));
        assertTrue(editorStyles.contains("padding-bottom: var(--action-dock-clearance)"));
        assertTrue(wizard.contains("scrollIntoView"));
        assertTrue(wizard.contains("prefers-reduced-motion"));
        assertTrue(wizard.contains("subscribe(() => render())"));
        assertFalse(wizard.contains("innerHTML"));
    }

    @Test
    void groupsEndpointsByResourceWithoutChangingSelectionIdentity() throws Exception {
        String operations = resource("/static/operations.js");
        String editorStyles = resource("/static/editor.css");

        assertTrue(operations.contains("export function groupOperations(operations)"));
        assertTrue(operations.contains("'schema-contracts': '스키마 계약'"));
        assertTrue(operations.contains("admin: '관리'"));
        assertTrue(operations.contains("auth: '인증'"));
        assertTrue(operations.contains("customers: '고객'"));
        assertTrue(operations.contains("const groupExpansion = new Map()"));
        assertTrue(operations.contains("captureGroupExpansion(list, groupExpansion)"));
        assertTrue(operations.contains("group.open = groupExpansion.get(resourceGroup.key) ?? true"));
        assertTrue(operations.contains("operation.issues"));
        assertTrue(operations.contains("group.replaceChildren"));
        assertTrue(operations.contains("operation.sourceIndex"));
        assertTrue(operations.contains("document.createElement('details')"));
        assertTrue(operations.contains("document.createElement('summary')"));
        assertFalse(operations.contains("innerHTML"));
        assertTrue(editorStyles.contains(".endpoint-group"));
        assertTrue(editorStyles.contains(".endpoint-row"));
        assertTrue(editorStyles.contains(".endpoint-issue-row"));
        assertTrue(editorStyles.contains(".wizard-panel h2:focus-visible { outline: none; }"));
    }

    @Test
    void composesEveryEditorStepAroundOneClearPrimaryTask() throws Exception {
        String index = resource("/templates/editor.html");
        String app = resource("/static/app.js");
        String progress = resource("/static/progress.js");
        String editorStyles = resource("/static/editor.css");

        assertTrue(index.contains("class=\"uploaded-file file-state-card\""));
        assertTrue(index.contains("class=\"field-grid project-settings-grid\""));
        assertTrue(index.contains("class=\"preview-workspace\""));
        assertTrue(index.contains("class=\"preview-input surface-subtle\""));
        assertTrue(index.contains("class=\"preview-result surface-subtle\""));
        assertTrue(index.contains("class=\"job-progress-hero\""));
        assertTrue(index.contains("id=\"job-progress-percent\""));
        assertTrue(index.contains("class=\"pipeline-panel\""));
        assertTrue(index.contains("<ul id=\"downloads\" class=\"downloads artifact-list\""));
        assertTrue(index.contains("class=\"bi bi-question-circle\""));
        assertFalse(index.contains(">?</button>"));
        assertFalse(index.contains("<svg"));
        assertTrue(app.contains("document.createElement('li')"));
        assertTrue(app.contains("미리보기를 생성하고 있습니다."));
        assertTrue(progress.contains("#job-progress-percent"));
        assertTrue(progress.contains("document.createElement('i')"));
        assertTrue(editorStyles.contains(".project-settings-grid"));
        assertTrue(editorStyles.contains(".preview-workspace"));
        assertTrue(editorStyles.contains(".job-progress-hero"));
        assertFalse(app.contains("innerHTML"));
        assertFalse(progress.contains("innerHTML"));
    }

    @Test
    void givesHostedResourcesAndJobStateTheSameResponsiveHierarchy() throws Exception {
        String dashboard = resource("/templates/dashboard.html");
        String jobDetail = resource("/templates/job-detail.html");
        String hosted = resource("/static/hosted.js");
        String hostedStyles = resource("/static/hosted.css");

        assertTrue(dashboard.contains("class=\"hosted-dashboard-grid\""));
        assertTrue(dashboard.contains("class=\"table-responsive hosted-resource-table\""));
        assertTrue(dashboard.contains("아직 등록된 OpenAPI 문서가 없습니다."));
        assertTrue(dashboard.contains("아직 실행한 작업이 없습니다."));
        assertTrue(jobDetail.contains("class=\"job-detail-grid\""));
        assertTrue(jobDetail.contains("class=\"job-timeline\""));
        assertTrue(jobDetail.contains("class=\"artifact-list hosted-artifact-list\""));
        assertTrue(jobDetail.contains("class=\"job-cancel-form\""));
        assertTrue(hosted.contains("feedback.dataset.feedbackState"));
        assertTrue(hosted.contains("가져오기 작업을 등록했습니다."));
        assertFalse(hosted.contains("innerHTML"));
        assertTrue(hostedStyles.contains(".hosted-dashboard-grid"));
        assertTrue(hostedStyles.contains(".hosted-resource-table"));
        assertTrue(hostedStyles.contains("@media (max-width: 400px)"));
    }

    @Test
    void exposesTheAccessibleFourStepEndpointEditor() throws Exception {
        String index = resource("/templates/editor.html") + resource("/templates/fragments/ui.html");
        String styles = applicationStyles();

        assertTrue(index.contains("<html lang=\"ko\""));
        assertTrue(index.contains("<h2 id=\"specification-title\" tabindex=\"-1\">1. OpenAPI 파일</h2>"));
        assertTrue(index.contains("<h2 id=\"operations-title\" tabindex=\"-1\">2. API endpoint 선택</h2>"));
        assertTrue(index.contains("<h2 id=\"generation-title\" tabindex=\"-1\">3. 생성 설정</h2>"));
        assertTrue(index.contains("<h2 id=\"generation-run-title\" tabindex=\"-1\">4. 미리보기와 생성</h2>"));
        assertTrue(index.contains("<h2 id=\"generation-job-title\" tabindex=\"-1\">5. 생성 진행</h2>"));
        assertFalse(index.contains("<h2>6."));

        assertTrue(index.contains("class=\"wizard-steps\""));
        assertTrue(index.contains("aria-current=\"step\""));
        assertTrue(index.contains("data-step=\"1\""));
        assertTrue(index.contains("data-step=\"5\""));
        assertTrue(index.contains("class=\"workflow-step wizard-panel\""));
        assertTrue(index.contains("id=\"wizard-live\""));
        assertTrue(index.contains("id=\"step-back-3\""));
        assertTrue(index.contains("id=\"step-next-3\""));
        assertTrue(index.contains("id=\"step-hint-3\""));
        assertTrue(index.contains("id=\"step-next-4\""));
        assertTrue(index.contains("id=\"step-back-5\""));
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
        assertTrue(index.contains("id=\"profile-help-button\""));
        assertTrue(index.contains("aria-expanded=\"false\""));
        assertTrue(index.contains("aria-controls=\"profile-help\""));
        assertTrue(index.contains("id=\"profile-help\""));
        assertTrue(index.contains("id=\"profile-notice-list\""));
        assertTrue(index.contains("id=\"profile-help\" class=\"profile-help\" role=\"region\"")
                && index.contains("hidden"));
        assertTrue(index.contains("id=\"preview-button\" type=\"button\" disabled"));
        assertTrue(index.contains("id=\"generate-button\""));
        assertTrue(index.contains("name=\"csrf-token\""));
        assertTrue(index.contains("name=\"csrf-header\""));

        assertTrue(styles.contains("--primary: #4f46e5"));
        assertTrue(styles.contains("--surface: #ffffff"));
        assertTrue(styles.contains("--line: #e2e8f0"));
        assertTrue(styles.contains("--text: #0f172a"));
        assertTrue(styles.contains("--muted: #475569"));
        assertTrue(styles.contains("--success: #0d9488"));
        assertTrue(styles.contains("--success-text: #0f766e"));
        // Selection, drop targets, and loaded state are brand affordances, not
        // success. Painting them green gave the screen two owners of action colour.
        assertTrue(styles.contains("accent-color: var(--primary);"));
        assertFalse(styles.contains("accent-color: var(--success)"));
        assertFalse(styles.contains("color-mix(in srgb, var(--success) 8%, white)"));
        assertFalse(styles.contains("color-mix(in srgb, var(--success) 12%, white)"));
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
        assertTrue(styles.contains(".profile-help[hidden] { display: none; }"));

        // A wizard panel and the summary aside share a grid row, so a margin on
        // only one of them misaligns their tops. Both containers supply a gap.
        assertFalse(styles.contains("section, .error-summary {\n  margin-top"));
        // Buttons carry three weights, and every one of them reacts to a pointer.
        assertTrue(styles.contains("--radius-control: 8px"));
        assertTrue(styles.contains("button:hover:not(:disabled)"));
        assertTrue(styles.contains("button:active:not(:disabled)"));
        assertTrue(styles.contains("button.secondary:hover:not(:disabled)"));
        assertTrue(styles.contains("@media (prefers-reduced-motion: reduce)"));
        // Artifacts form a row; stacked blocks read as competing primary actions.
        assertTrue(styles.contains(".downloads { display: flex; flex-wrap: wrap;"));
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
        // Step 5 opens once a job exists, so starting generation carries the user there.
        assertTrue(wizard.contains("if (step === 4) return Boolean(state.jobId);"));
        assertTrue(wizard.contains("#step-([1-5])"));
        assertTrue(app.contains("wizard.goToStep(5)"));
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
    void rendersProfileLabelsAndCompatibilityNoticesWithoutHtmlInjection() throws Exception {
        String app = resource("/static/app.js");

        assertTrue(app.contains("formatProfileLabel(profile)"));
        assertTrue(app.contains("return `Spring AI ${springAi} · Java ${profile.javaVersion}"));
        assertTrue(app.contains("payload.compatibilityNotices"));
        assertTrue(app.contains("renderCompatibilityNotices"));
        assertTrue(app.contains("document.createElement('li')"));
        assertTrue(app.contains("document.createElement('a')"));
        assertTrue(app.contains("event.key === 'Escape'"));
        assertTrue(app.contains("aria-expanded"));
        assertFalse(app.contains("innerHTML"));
        assertFalse(app.contains("SPRING_AI_1_WEBFLUX_ASYNC_DEFERRED"));
        assertFalse(app.contains("spring-projects/spring-ai/issues/6274"));
    }

    @Test
    void keepsARetainedLocalJobReachableWithoutARestoredAnalysis() throws Exception {
        String wizard = resource("/static/wizard.js");

        assertTrue(wizard.contains("if (step === 5 && state.jobId) return true;"));
        assertTrue(wizard.contains("isReachable(getState().currentStep, getState())"));
    }

    @Test
    void restoresTheHostedWizardStepAfterReloadingTheRetainedSpecification() throws Exception {
        String upload = resource("/static/upload.js");

        assertTrue(upload.contains("const sameSpecification = getState().specificationId === specificationId;"));
        assertTrue(upload.contains("const retainedStep = sameSpecification ? getState().currentStep : 1;"));
        assertTrue(upload.contains("currentStep: retainedStep"));
    }

    @Test
    void summarizesGenerationProgressWithoutLosingStageDetail() throws Exception {
        String index = resource("/templates/editor.html");
        String progress = resource("/static/progress.js");
        String app = resource("/static/app.js");
        String styles = applicationStyles();

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
        // The job state line must be Korean, not the raw RUNNING — COMPILE constants.
        // Both modes' status vocabularies are covered: local emits VALIDATED and
        // UNVERIFIED, hosted emits SUCCEEDED and CANCELLED.
        assertTrue(progress.contains("export function stateLabel(state)"));
        for (String status : new String[] {
                "QUEUED", "RUNNING", "VALIDATED", "UNVERIFIED", "SUCCEEDED", "FAILED", "CANCELLED"
        }) {
            assertTrue(progress.contains(status + ": '"), status);
        }
        assertTrue(app.contains("stateLabel(snapshot.state)"));
        assertFalse(app.contains("${snapshot.currentStage}"));
        // Download labels are Korean like the rest of the interface, and the
        // buttons stay at secondary weight beside the primary generate action.
        assertTrue(app.contains("function artifactLabel(name)"));
        assertTrue(app.contains("프로젝트 아카이브"));
        assertTrue(app.contains("검증 리포트"));
        assertFalse(app.contains("`Download ${name}`"));
        assertTrue(styles.contains(".progress-fill"));
        // A failure marks every later stage SKIPPED. Counting the full list would
        // render a build that died at stage 3 as 88% complete.
        assertTrue(progress.contains("const counted = failed ? stages.slice(0, failedIndex) : stages;"));
        assertTrue(progress.contains("const completed = snapshot.state === 'SUCCEEDED';"));
        assertTrue(progress.contains("const settled = completed ? stages.length"));
        assertTrue(styles.contains(".progress-fill[data-state=\"failed\"] { background: var(--danger); }"));

        assertFalse(progress.contains("innerHTML"));
        assertFalse(progress.contains("EventSource"));
    }

    @Test
    void streamsJobProgressWithAPollingFallback() throws Exception {
        String api = resource("/static/api.js");
        String app = resource("/static/app.js");

        assertTrue(api.contains("export function jobEvents(jobId"));
        assertTrue(api.contains("new EventSource("));
        assertTrue(api.contains("/events"));
        // One normalization for both transports, or hosted payloads would be
        // converted in one path and not the other.
        assertTrue(api.contains("function normalizeJob(payload)"));
        assertTrue(app.contains("async function followJob(jobId)"));
        // The fallback is what keeps progress visible where a proxy blocks the stream.
        assertTrue(app.contains("await pollJob(jobId)"));
        // A reconnecting stream must not trigger the fallback; only a closed one.
        assertTrue(api.contains("EventSource.CLOSED"));
        assertTrue(api.contains("source.addEventListener('heartbeat'"));
        assertTrue(api.contains("onHeartbeat"));
        assertTrue(app.contains("STREAM_LIVENESS_DEADLINE_MILLIS"));
        assertTrue(app.contains("refreshDeadline"));
        assertFalse(api.contains("innerHTML"));
    }

    @Test
    void scopesTheEditorGridAwayFromHostedPages() throws Exception {
        String index = resource("/templates/editor.html");
        String styles = applicationStyles();

        assertTrue(index.contains("<main class=\"editor-main\">"));
        assertTrue(styles.contains(".editor-main {\n"));
        assertTrue(styles.contains(".editor-main > #error-summary"));
        assertFalse(styles.contains("\nmain {\n"));
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

    private String applicationStyles() throws IOException {
        return resource("/static/styles.css")
                + resource("/static/design-tokens.css")
                + resource("/static/legacy.css")
                + resource("/static/app-shell.css")
                + resource("/static/editor.css")
                + resource("/static/hosted.css");
    }
}
