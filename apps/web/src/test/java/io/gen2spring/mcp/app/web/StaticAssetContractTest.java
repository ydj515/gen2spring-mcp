package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.app.web.config.security.WebSecurityConfiguration;
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
        assertTrue(tokens.contains("font-family: gen2spring-bootstrap-icons"));
        assertTrue(tokens.contains("/webjars/bootstrap-icons/1.13.1/font/fonts/bootstrap-icons.woff2"));
        assertFalse(tokens.contains("bootstrap-icons.woff2?"));
        assertTrue(tokens.contains("font-family: gen2spring-bootstrap-icons !important"));
        assertTrue(editor.contains("--bs-form-select-bg-img: url('/select-chevron.svg')"));
        assertFalse(editor.contains("data:image"));
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
        assertFalse(WebSecurityConfiguration.CONTENT_SECURITY_POLICY.contains("img-src 'self' data:"));
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
    void alignsWizardContextAndKeepsActionsInDocumentFlow() throws Exception {
        String fragment = resource("/templates/fragments/ui.html");
        String editor = resource("/templates/editor.html");
        String editorStyles = resource("/static/editor.css");
        String wizard = resource("/static/wizard.js");

        assertTrue(fragment.contains("class=\"wizard-step-connector\""));
        assertTrue(editor.contains("class=\"generation-summary summary-strip\""));
        assertTrue(editor.contains("class=\"generation-summary__item\""));
        assertTrue(editor.contains("class=\"generation-summary__icon"));
        assertTrue(editor.indexOf("id=\"generation-summary\"")
                < editor.indexOf("id=\"specification-step\""));
        assertTrue(editorStyles.contains("--editor-frame-width: 90rem"));
        assertTrue(editorStyles.contains("width: min(100%, var(--editor-frame-width))"));
        assertTrue(editorStyles.contains(".wizard-progress {\n  width: 100%;"));
        assertFalse(editorStyles.contains("--action-dock-clearance"));
        assertFalse(editorStyles.contains(".wizard-nav {\n  position: fixed;"));
        assertTrue(editorStyles.contains("scroll-padding-inline"));
        assertFalse(editorStyles.contains("padding-bottom: var(--action-dock-clearance)"));
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
        assertTrue(operations.contains("counts.replaceChildren("));
        assertTrue(operations.contains("document.createElement('details')"));
        assertTrue(operations.contains("document.createElement('summary')"));
        assertFalse(operations.contains("innerHTML"));
        assertTrue(editorStyles.contains(".endpoint-group"));
        assertTrue(editorStyles.contains(".endpoint-row"));
        assertTrue(editorStyles.contains(".endpoint-issue-row"));
        assertTrue(editorStyles.contains(".wizard-panel h2:focus-visible { outline: none; }"));
        assertTrue(operations.contains("checkbox.dataset.operationIndex"));
        assertFalse(operations.contains("edit.className = 'endpoint-edit';"));
        assertFalse(operations.contains("settingsIcon.className = 'bi bi-sliders';"));
        assertFalse(operations.contains("chevron.className = 'bi bi-chevron-right';"));
        assertFalse(editorStyles.contains(".endpoint-row .endpoint-edit:hover:not(:disabled)"));
        assertTrue(editorStyles.contains(".wizard-progress .wizard-chip:hover:not(:disabled)"));
    }

    @Test
    void composesEveryEditorStepAroundOneClearPrimaryTask() throws Exception {
        String index = resource("/templates/editor.html");
        String app = resource("/static/app.js");
        String progress = resource("/static/progress.js");
        String editorStyles = resource("/static/editor.css");

        assertTrue(index.contains("class=\"uploaded-file file-state-card\""));
        assertTrue(index.contains("class=\"field-grid project-settings-grid row g-3\""));
        assertTrue(index.contains("class=\"preview-workspace\""));
        assertTrue(index.contains("class=\"preview-input\""));
        assertTrue(index.contains("class=\"preview-result\""));
        assertTrue(index.contains("id=\"validation-arguments-details\""));
        assertTrue(index.contains("class=\"validation-checklist\""));
        assertTrue(index.contains("class=\"job-progress-hero\""));
        assertTrue(index.contains("id=\"job-progress-percent\""));
        assertTrue(index.contains("class=\"pipeline-panel\""));
        assertTrue(index.contains("<ul id=\"downloads\" class=\"downloads artifact-list\""));
        assertTrue(index.contains("id=\"artifact-placeholder-list\""));
        assertTrue(index.contains("class=\"bi bi-question-circle\""));
        assertFalse(index.contains(">?</button>"));
        assertFalse(index.contains("<svg"));
        assertTrue(app.contains("document.createElement('li')"));
        assertTrue(app.contains("설정을 검증하고 있습니다."));
        assertTrue(progress.contains("#job-progress-percent"));
        assertTrue(progress.contains("document.createElement('i')"));
        assertTrue(editorStyles.contains(".project-settings-grid"));
        assertTrue(index.contains("col-12 col-sm-6 col-lg-4 col-xl-2"));
        assertTrue(editorStyles.contains("@media (min-width: 1200px)"));
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
        assertTrue(index.contains("<h2 id=\"specification-title\" tabindex=\"-1\">API를 MCP 도구로 바꾸세요.</h2>"));
        assertTrue(index.contains("<h2 id=\"operations-title\" tabindex=\"-1\">API endpoint 선택</h2>"));
        assertTrue(index.contains("<h2 id=\"generation-title\" tabindex=\"-1\">생성 설정</h2>"));
        assertTrue(index.contains("<h2 id=\"generation-run-title\" tabindex=\"-1\">설정 검증 및 프로젝트 생성</h2>"));
        assertTrue(index.contains("<h2 id=\"generation-job-title\" tabindex=\"-1\">생성 진행</h2>"));
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
        assertFalse(index.contains("id=\"step-next-4\""));
        assertTrue(index.contains("id=\"step-back-5\""));
        assertTrue(index.contains("tabindex=\"-1\""));
        assertTrue(index.contains("id=\"specification-file\" class=\"visually-hidden\" type=\"file\" accept=\".yaml,.yml,.json\""));
        assertTrue(index.contains("id=\"upload-dropzone\""));
        assertTrue(index.contains("data-upload-state=\"idle\""));
        assertTrue(index.contains("id=\"upload-live-status\""));
        assertTrue(index.contains("id=\"upload-status-icon\""));
        assertTrue(index.contains("id=\"upload-status-text\""));
        assertTrue(index.contains("aria-live=\"polite\""));
        assertTrue(index.contains("id=\"replace-file-button\""));
        assertTrue(index.contains("id=\"remove-file-button\""));
        assertTrue(index.contains("id=\"operation-search\""));
        assertTrue(index.contains("id=\"select-all-operations\""));
        assertTrue(index.contains("id=\"operation-counts\""));
        assertTrue(index.contains("id=\"operation-list\""));
        assertTrue(index.contains("id=\"selected-tool-list\""));
        assertTrue(index.contains("class=\"tool-workspace row g-0\""));
        assertTrue(index.contains("class=\"tool-editor-panel col-12 col-xl-5\""));
        assertFalse(index.contains("id=\"operation-editor-home\""));
        assertTrue(index.contains("id=\"generation-summary\""));
        for (String id : new String[] {
                "summary-version", "summary-selected", "summary-excluded", "summary-profile"
        }) {
            assertTrue(index.contains("id=\"" + id + "\""), id);
        }
        assertTrue(index.contains("id=\"target-profile\""));
        assertTrue(index.contains("id=\"profile-help-button\""));
        assertTrue(index.contains("aria-expanded=\"false\""));
        assertTrue(index.contains("aria-controls=\"profile-help\""));
        assertTrue(index.contains("id=\"profile-help\""));
        assertTrue(index.contains("id=\"profile-notice-list\""));
        assertTrue(index.contains("id=\"profile-help\" class=\"profile-help\" role=\"tooltip\"")
                && index.contains("hidden"));
        for (String policy : new String[] {"retry", "pagination", "parameters", "normalization"}) {
            assertTrue(index.contains("id=\"policy-" + policy + "-toggle\""), policy + " toggle");
            assertTrue(index.contains("id=\"policy-" + policy + "-help-button\""), policy + " help button");
            assertTrue(index.contains("id=\"policy-" + policy + "-help\""), policy + " help");
            assertTrue(index.contains("aria-controls=\"policy-" + policy + "-panel\""), policy + " panel binding");
        }
        assertTrue(index.contains("id=\"policy-retry-status\""));
        assertTrue(index.contains("id=\"policy-pagination-status\""));
        assertTrue(index.contains("id=\"policy-parameters-status\""));
        assertTrue(index.contains("id=\"policy-normalization-status\""));
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
        assertTrue(styles.contains(".tool-workspace {"));
        assertTrue(styles.contains(".tool-table__row[aria-selected=\"true\"]"));
        assertFalse(styles.contains(".selected-tool-editor"));
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
    void keepsWizardAnnouncementsAndProfileHelpStableAcrossFocusTransitions() throws Exception {
        String wizard = resource("/static/wizard.js");
        String app = resource("/static/app.js");

        // A programmatic hash write must not focus and announce the same step
        // again when the resulting hashchange event arrives.
        assertTrue(wizard.contains("if (target === getState().currentStep)"));
        assertTrue(wizard.contains("keepActiveStepVisible(target, true)"));

        // Hovering the select must not open help. The compact anchor owns hover,
        // focus, and tap so the icon behaves like the visible popover trigger.
        assertTrue(app.contains("closest('.profile-help-anchor')"));
        assertTrue(app.contains("anchor.addEventListener('mouseenter'"));
        assertTrue(app.contains("anchor.addEventListener('mouseleave'"));
        assertTrue(app.contains("anchor.addEventListener('focusin'"));
        assertTrue(app.contains("anchor.addEventListener('focusout'"));
        assertTrue(app.contains("ui['profile-help-button'].addEventListener('click'"));
        assertTrue(app.contains("!anchor.contains(event.relatedTarget)"));
        assertFalse(app.contains("closest('.profile-field')"));
    }

    @Test
    void replacesCompletedUploadAndKeepsPolicyHelpIndependentFromDisclosure() throws Exception {
        String upload = resource("/static/upload.js");
        String editor = resource("/static/editor.js");

        assertTrue(upload.contains("dropzone.hidden = showCompleted"));
        assertTrue(upload.contains("icon.hidden = !finished"));
        assertTrue(upload.contains("getState().analysis ? 'completed' : 'idle'"));
        assertTrue(editor.contains("function setPolicySectionOpen(section, open)"));
        assertTrue(editor.contains("function setPolicyHelpOpen(button, open)"));
        assertTrue(editor.contains("function updatePolicySummaries(operation)"));
        assertTrue(editor.contains("if (event.key === 'Escape') closePolicyHelp();"));
    }

    @Test
    void keepsEditorTablesAndControlsUsableAcrossTheMobileBreakpoint() throws Exception {
        String editorStyles = resource("/static/editor.css");
        String legacyStyles = resource("/static/legacy.css");
        int workspaceStart = editorStyles.indexOf("@media (max-width: 1199.98px)");
        int compactStart = editorStyles.indexOf("@media (max-width: 767.98px)");
        int phoneStart = editorStyles.indexOf("@media (max-width: 400px)", compactStart);
        assertTrue(workspaceStart >= 0);
        assertTrue(compactStart > workspaceStart);
        assertTrue(phoneStart > compactStart);
        String workspaceStyles = editorStyles.substring(workspaceStart, compactStart);
        String compactStyles = editorStyles.substring(compactStart, phoneStart);

        assertTrue(workspaceStyles.contains(".tool-table-panel { border-right: 0;"));
        assertTrue(workspaceStyles.contains(".tool-editor-panel { border-top: 1px solid var(--app-border);"));
        assertTrue(compactStyles.contains(".tool-table-header { display: none; }"));
        assertTrue(compactStyles.contains(".tool-row-select { grid-template-columns: minmax(0, 1fr) auto;"));
        assertTrue(compactStyles.contains(".parameter-row { grid-template-columns: 1fr; align-items: stretch; }"));
        assertTrue(compactStyles.contains(".validation-checklist { grid-template-columns: 1fr;"));
        assertTrue(compactStyles.contains(".progress-overview { grid-template-columns: 1fr;"));
        assertTrue(compactStyles.contains(".artifact-placeholder-list li { grid-template-columns: minmax(0, 1fr) auto;"));
        assertTrue(compactStyles.contains(".endpoint-toolbar__meta { align-items: flex-start;"));
        assertTrue(editorStyles.contains(".endpoint-toolbar {\n  position: relative;"));
        assertFalse(editorStyles.contains(".endpoint-toolbar {\n  position: sticky;"));
        assertTrue(editorStyles.contains("outline: 3px solid var(--app-primary);"));
        assertTrue(legacyStyles.contains("overflow-x: clip;"));
        assertFalse(legacyStyles.contains("overflow-x: hidden;"));
    }

    @Test
    void rendersProfileLabelsAndCompatibilityNoticesWithoutHtmlInjection() throws Exception {
        String app = resource("/static/app.js");

        assertTrue(app.contains("formatProfileLabel(profile)"));
        assertTrue(app.contains("return `${framework} · Java ${profile.javaVersion}"));
        assertTrue(app.contains("profile.mcpImplementations?.includes(implementation)"));
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
        assertTrue(index.contains("id=\"progress-overview\""));
        assertTrue(index.contains("class=\"progress-details-title\""));
        assertTrue(index.contains("progress-details-chevron"));
        assertTrue(index.contains("<details id=\"job-progress-details\""));
        assertFalse(index.contains("<summary>상세 보기</summary>"));

        assertTrue(progress.contains("export function renderProgress(snapshot)"));
        assertTrue(progress.contains("export function groupProgressStages(stages)"));
        assertTrue(progress.contains("준비"));
        assertTrue(progress.contains("프로젝트 생성"));
        assertTrue(progress.contains("컴파일 및 기동"));
        assertTrue(progress.contains("MCP 검증"));
        assertTrue(progress.contains("패키징"));
        assertTrue(progress.contains("OpenAPI 문서 분석"));
        assertTrue(progress.contains("Spring 컨텍스트 기동"));
        assertTrue(progress.contains("대표 Tool 호출 검증"));
        assertTrue(progress.contains("산출물 패키징"));
        assertTrue(progress.contains("'SKIPPED'"));
        assertFalse(progress.contains("document.querySelector('#job-progress-details').open"));
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
        // Completed artifacts share one compact list. Their visible action stays
        // concise while the accessible name retains the artifact identity.
        assertTrue(index.contains("id=\"artifact-status\" class=\"artifact-status\" hidden"));
        assertTrue(app.contains("ui['artifact-status'].hidden = downloads.length === 0;"));
        assertTrue(app.contains("label.textContent = '다운로드';"));
        assertTrue(app.contains("button.setAttribute('aria-label', `${artifactTitle(name)} 다운로드`);"));
        assertTrue(app.contains("name === 'archive' ? 'artifact-download-primary' : 'secondary'"));
        assertFalse(app.contains("생성 및 검증이 완료된 산출물입니다."));
        assertTrue(app.contains("프로젝트 아카이브"));
        assertTrue(app.contains("검증 리포트"));
        assertFalse(app.contains("`Download ${name}`"));
        assertTrue(progress.contains("if (status === 'SUCCESS') return 'bi bi-check-lg';"));
        assertTrue(styles.contains(".progress-fill"));
        assertTrue(styles.contains(".job-progress-hero .progress-overview { margin-top: var(--app-space-8); }"));
        assertTrue(styles.contains(".progress-overview li[data-status=\"SUCCESS\"]:not(:last-child)::after { border-top-color: rgba(var(--bs-primary-rgb), 0.68); }"));
        assertTrue(styles.contains(".progress-overview li[data-status=\"SUCCESS\"] .pipeline-marker {\n"
                + "  border-color: transparent;\n"
                + "  background: transparent;\n"
                + "  color: rgba(var(--bs-primary-rgb), 0.68);\n"
                + "}"));
        assertTrue(styles.contains(".artifact-list,\n.artifact-placeholder-list {"));
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
    void usesPrimaryProgressEmphasisAndDangerDeleteAction() throws Exception {
        String styles = resource("/static/editor.css");

        assertTrue(styles.contains(".progress-fill { background: var(--app-primary); }"));
        assertTrue(styles.contains("border-top-color: rgba(var(--bs-primary-rgb), 0.68);"));
        assertTrue(styles.contains("color: rgba(var(--bs-primary-rgb), 0.68);"));
        assertTrue(styles.contains("border-left-color: rgba(var(--bs-primary-rgb), 0.68);"));
        assertTrue(styles.contains("#delete-job-button.secondary {"));
        assertTrue(styles.contains("border-color: var(--app-danger);\n"
                + "  background: var(--app-surface);\n"
                + "  color: var(--app-danger);"));
        assertTrue(styles.contains("#delete-job-button.secondary:hover:not(:disabled) {\n"
                + "  border-color: var(--app-danger);\n"
                + "  background: var(--app-danger-soft);\n"
                + "  color: var(--app-danger);\n"
                + "}"));
    }

    @Test
    void presentsAdvancedToolPoliciesAsExpandableRows() throws Exception {
        String index = resource("/templates/editor.html");
        String styles = resource("/static/editor.css");

        assertTrue(index.contains("data-policy-section=\"retry\""));
        assertTrue(index.contains("id=\"policy-retry-toggle\" class=\"policy-section__toggle policy-section__toggle--icon\""));
        assertTrue(index.contains("aria-expanded=\"false\" aria-controls=\"policy-retry-panel\""));
        assertTrue(index.contains("class=\"bi bi-chevron-down policy-section__chevron\""));
        assertTrue(styles.contains(".policy-section__summary"));
        assertTrue(styles.contains(".policy-section__chevron"));
        assertTrue(styles.contains(".policy-section.is-open .policy-section__chevron"));
        assertTrue(index.contains("id=\"retry-enabled\" class=\"policy-switch\" type=\"checkbox\" role=\"switch\""));
        assertTrue(styles.contains(".policy-enable-checkbox"));
        assertTrue(styles.contains("width: 1.25rem;\n  height: 1.25rem;\n  min-height: 0;"));
    }

    @Test
    void rotatesOnlyRunningPipelineIconsAndRespectsReducedMotion() throws Exception {
        String styles = resource("/static/editor.css");
        String progress = resource("/static/progress.js");

        assertTrue(styles.contains("@keyframes pipeline-spin"));
        assertTrue(progress.contains("if (status === 'RUNNING') return 'bi bi-arrow-clockwise pipeline-spinner';"));
        assertTrue(styles.contains(".pipeline-spinner {"));
        assertTrue(styles.contains(".pipeline-spinner::before {\n"
                + "  content: '';\n"
                + "  display: block;\n"
                + "  width: 100%;\n"
                + "  height: 100%;\n"
                + "  background: currentColor;"));
        assertTrue(styles.contains("mask: url('/webjars/bootstrap-icons/1.13.1/icons/arrow-clockwise.svg') center / contain no-repeat;"));
        assertTrue(styles.contains("transform-origin: 50% 50%;"));
        assertTrue(styles.contains("animation: pipeline-spin 1s linear infinite;"));
        assertTrue(styles.contains("@media (prefers-reduced-motion: reduce)"));
        assertTrue(styles.contains(".pipeline-spinner::before { animation: none; }"));
        assertTrue(styles.contains("animation: none;"));
        assertFalse(styles.contains(".progress-list li[data-status=\"RUNNING\"] > i {"));
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
        String template = resource("/templates/editor.html");
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
        assertFalse(upload.contains("ANALYSIS_ADVANCE_DELAY_MILLIS"));
        assertFalse(upload.contains("pendingAdvance"));
        assertFalse(upload.contains("onReadyToAdvance"));
        assertTrue(upload.contains("dropzone.hidden = showCompleted;"));
        assertTrue(template.contains("id=\"step-next-1\" type=\"button\" disabled"));
        assertTrue(template.contains("id=\"step-hint-1\""));
        assertFalse(template.contains("id=\"analysis-transition-status\""));
        assertTrue(app.contains("item.className = 'analysis-summary__item';"));
        assertTrue(applicationStyles().contains("grid-template-columns: repeat(4, minmax(0, 1fr));"));
        assertTrue(app.contains("'전체 Endpoint'"));
        assertTrue(operations.contains("operation.issues"));
        assertTrue(operations.contains("operation.status"));
        assertTrue(operations.contains("operation.supported"));
        assertTrue(operations.contains("operation.endpointSelected"));
        assertTrue(operations.contains("selectable"));
        assertTrue(operations.contains("textContent"));
        assertTrue(operations.contains("operation-search"));
        assertTrue(operations.contains("select-all-operations"));
        assertTrue(operations.contains("preservedSelection"));
        assertFalse(editor.contains("document.createElement('details')"));
        assertTrue(editor.contains("기본값"));
        assertTrue(editor.contains("사용자 설정"));
        assertTrue(editor.contains("selected-tool-list"));
        assertTrue(editor.contains("checkbox.dataset.toolEnabledId"));
        assertTrue(editor.contains("filter(operation => operation.endpointSelected"));
        assertTrue(editor.contains("row.dataset.operationId"));
        assertTrue(editor.contains("row.setAttribute('aria-selected'"));
        assertTrue(editor.contains("document.querySelectorAll('.policy-section')"));
        assertFalse(editor.contains("openOperationId"));
        assertTrue(state.contains("resetSpecificationState"));
        assertTrue(state.contains("operation.supported"));
        assertTrue(state.contains("endpointSelected: operation.supported"));
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
        assertFalse(app.contains("onReadyToAdvance"));
        assertTrue(app.contains("initializeEditor(() => {\n  renderParameterSummary();\n  invalidatePreview();\n});"));
        assertTrue(app.contains("let previewRequestVersion = 0;"));
        assertTrue(app.contains("const requestVersion = ++previewRequestVersion;"));
        assertTrue(app.contains("if (requestVersion !== previewRequestVersion) return;"));
        assertTrue(app.contains("function resetSpecificationPresentation() {\n  ui['preview-button'].disabled = true;"));
        assertTrue(app.contains("renderGenerationSummary"));
        assertTrue(app.contains("operation => operation.endpointSelected"));
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
        assertTrue(index.contains("id=\"tool-description\" class=\"form-control\" maxlength=\"1024\""));
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
