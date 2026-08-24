# Wizard Flow Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved five-step wizard screens with one aligned frame, direct Tool selection, guided validation, and informative progress without changing backend contracts.

**Architecture:** Preserve the existing Thymeleaf and ECMAScript-module architecture. Update `editor.html` and `editor.css` for composition, keep state ownership in the current modules, replace Step 3 disclosure rows with a table-plus-persistent-editor controller, and derive Step 4/5 presentation from existing analysis, preview, and job snapshots.

**Implementation adjustment:** The final approved flow keeps Step 1 on the analyzed result and enables an explicit `다음: Endpoint 선택` action. This supersedes Task 2's originally planned 800ms automatic transition; the implemented contracts and final QA evidence are authoritative.

**Tech Stack:** Java 21, Spring Boot 3.5, Thymeleaf, Bootstrap 5.3.8, Bootstrap Icons 1.13.1, ECMAScript modules, JUnit 5, MockMvc, Codex in-app browser.

**Spec:** `docs/superpowers/specs/2026-08-24-wizard-flow-refinement-design.md`

## Global Constraints

- Keep the implementation as one consolidated feature commit after explicit user approval.
- Preserve all API, generation, validation, persistence, security, and hosted-mode contracts.
- Add no dependencies and load no Bootstrap JavaScript, CDN assets, remote fonts, or remote imagery.
- Use one CSS custom property for the shared editor-frame width.
- Keep all wizard action rows in normal document flow; no fixed or viewport-bottom navigation.
- Use direct row checkboxes for Step 2 endpoint selection and Step 3 Tool generation.
- Do not use `innerHTML`, inline SVG, CSS-drawn icons, emoji, nested cards, or a global sidebar.
- Preserve keyboard access, focus movement, live regions, reduced motion, and 400 × 900 support.
- Treat approved images under `docs/superpowers/specs/assets/wizard-flow-refinement/` as visual references only.

---

### Task 1: Align the shared frame, summary, and action rows

**Files:**
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/templates/fragments/ui.html`
- Modify: `apps/web/src/main/resources/static/editor.css`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`

**Interfaces:**
- Consumes: existing `.wizard-progress`, `.wizard-steps`, `.editor-main`, `#generation-summary`, and `.wizard-nav` hooks.
- Produces: `--editor-frame-width`, `.generation-summary__item`, `.generation-summary__icon`, and in-flow `.wizard-nav` layout used by all later tasks.

- [ ] **Step 1: Add failing rendered-markup and stylesheet contracts**

  Add assertions that rendered editor HTML contains four summary items with icon wrappers and that both stepper and main consume one frame token. Assert the rejected fixed dock declarations are absent:

  ```java
  assertTrue(editor.contains("generation-summary__icon"));
  assertTrue(editorStyles.contains("--editor-frame-width: 90rem"));
  assertTrue(editorStyles.contains("width: min(100%, var(--editor-frame-width))"));
  assertFalse(editorStyles.contains(".wizard-nav {\n  position: fixed;"));
  assertFalse(editorStyles.contains("--action-dock-clearance"));
  ```

- [ ] **Step 2: Run the focused test and capture RED**

  Run:

  ```bash
  mise exec -- ./gradlew :apps:web:test \
    --tests 'io.gen2spring.mcp.app.web.StaticAssetContractTest' \
    --tests 'io.gen2spring.mcp.app.web.WebMvcContractTest' \
    --no-daemon --non-interactive --rerun-tasks
  ```

  Expected: FAIL because the shared frame token and summary wrappers do not exist and `.wizard-nav` is fixed.

- [ ] **Step 3: Implement the shared frame and summary markup**

  Define:

  ```css
  :root { --editor-frame-width: 90rem; }

  .wizard-progress .wizard-steps,
  main.editor-main {
    width: min(100%, var(--editor-frame-width));
    margin-inline: auto;
    padding-inline: clamp(1rem, 4vw, 3rem);
  }

  .wizard-nav {
    position: static;
    width: 100%;
    transform: none;
  }
  ```

  Replace summary `<dt>` icon placement with a dedicated icon wrapper while retaining the existing summary value IDs. Remove action-dock body clearance and all fixed/pill dock styling.

- [ ] **Step 4: Run focused tests and inspect at 1440px**

  Run the Step 2 command again and verify PASS. In the browser, compare the stepper, summary, and panel left/right edges at 1440 × 1024 before continuing.

---

### Task 2: Complete Step 1 automatically and flatten Step 2 selection

**Files:**
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/upload.js`
- Modify: `apps/web/src/main/resources/static/app.js`
- Modify: `apps/web/src/main/resources/static/operations.js`
- Modify: `apps/web/src/main/resources/static/editor.css`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`

**Interfaces:**
- Consumes: `initializeUpload({onAnalysis, onReset, onFailure})`, `wizard.goToStep(2)`, `operation.sourceIndex`, and existing selection state.
- Produces: `ANALYSIS_ADVANCE_DELAY_MILLIS = 800`, cancelable pending transition, direct endpoint table rows without per-row settings actions, and normal Step 2 footer navigation.

- [ ] **Step 1: Add failing contracts for the approved Step 1 and Step 2 structure**

  Assert the Step 1 manual next button is absent, Step 1 exposes the transition status, Step 2 rows no longer create `.endpoint-edit`, and direct row checkbox construction remains:

  ```java
  assertFalse(editor.contains("id=\"step-next-1\""));
  assertTrue(editor.contains("id=\"analysis-transition-status\""));
  assertTrue(upload.contains("ANALYSIS_ADVANCE_DELAY_MILLIS = 800"));
  assertTrue(operations.contains("checkbox.dataset.operationIndex"));
  assertFalse(operations.contains("edit.className = 'endpoint-edit'"));
  ```

- [ ] **Step 2: Run the focused test and capture RED**

  Run:

  ```bash
  mise exec -- ./gradlew :apps:web:test \
    --tests 'io.gen2spring.mcp.app.web.StaticAssetContractTest' \
    --no-daemon --non-interactive --rerun-tasks
  ```

  Expected: FAIL on the existing Step 1 next button, immediate transition, and Step 2 settings control.

- [ ] **Step 3: Implement the cancelable Step 1 transition**

  `upload.js` owns a pending timer and exposes completion only after state/render work. `reset()` and every new `analyze()`/`load()` request cancel the prior timer. `app.js` receives an `onReadyToAdvance` callback and calls `wizard.goToStep(2)` only when the current step is still Step 1.

  Use the exact delay:

  ```javascript
  export const ANALYSIS_ADVANCE_DELAY_MILLIS = 800;
  ```

  Keep retained hosted state on its restored step and announce the pending transition through `#analysis-transition-status`.

- [ ] **Step 4: Remove Step 2's settings action and retain direct selection**

  Remove the fifth endpoint-column control and `.endpoint-edit` button from `operations.js`. Keep checkboxes, filtering, group expansion, unsupported reasons, source order, and select-all semantics unchanged. Remove the obsolete `onEdit` callback from `initializeOperations` and `app.js`.

- [ ] **Step 5: Run focused tests and browser-check both upload outcomes**

  Verify PASS, then check successful analysis advances after 800ms, failed analysis remains on Step 1, and replacing/removing during the delay prevents stale navigation.

---

### Task 3: Replace Step 3 nested disclosures with a Tool table and persistent editor

**Files:**
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/editor.js`
- Modify: `apps/web/src/main/resources/static/editor.css`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`

**Interfaces:**
- Consumes: `state.operations`, `state.selectedOperationId`, `selectOperation(operationId)`, `saveSelectedOperation()`, and the existing `#operation-editor` field bindings.
- Produces: `.tool-workspace`, `.tool-table`, row buttons keyed by `data-operation-id`, direct generation checkboxes keyed by `data-tool-enabled-id`, and one fixed DOM home for `#operation-editor`.

- [ ] **Step 1: Add failing Step 3 composition contracts**

  Assert the new table and persistent editor slots exist and disclosure rows are gone:

  ```java
  assertTrue(editor.contains("class=\"tool-workspace\""));
  assertTrue(editor.contains("class=\"tool-editor-panel\""));
  assertTrue(editorJs.contains("dataToolEnabledId"));
  assertFalse(editorJs.contains("document.createElement('details')"));
  assertFalse(editorStyles.contains(".selected-tool-editor"));
  ```

- [ ] **Step 2: Run the focused test and capture RED**

  Run the Task 2 focused test. Expected: FAIL because Step 3 still renders expandable details and moves the editor into each row.

- [ ] **Step 3: Render flat Tool rows and one editor target**

  Replace `toolRow()` with a semantic row containing:

  ```javascript
  checkbox.dataset.toolEnabledId = operation.operationId;
  row.dataset.operationId = operation.operationId;
  row.setAttribute('aria-selected', String(operation.operationId === selectedOperationId));
  ```

  Row selection saves the previous Tool, updates `selectedOperationId`, and renders fields in the persistent editor. Checkbox changes toggle only generation state and preserve a valid selected row. No editor DOM node moves between rows.

- [ ] **Step 4: Flatten and gate advanced policies**

  Convert advanced policies into one accordion group. Opening one section closes the others. Retry and pagination dependent fields are hidden when disabled while their state remains in `state.operations`. Parameter and response-normalization sections remain available because they do not have master enable flags.

- [ ] **Step 5: Run tests and exercise state preservation**

  Verify PASS. In the browser, edit Tool A, select Tool B, return to Tool A, and confirm values persist. Toggle Tool generation directly from the table and confirm Step 4's representative-operation list updates.

---

### Task 4: Guide Step 4 and make Step 5 progress actionable

**Files:**
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/app.js`
- Modify: `apps/web/src/main/resources/static/progress.js`
- Modify: `apps/web/src/main/resources/static/editor.css`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`

**Interfaces:**
- Consumes: existing preview response, `startGeneration()`, `wizard.goToStep(5)`, job snapshots, SSE/polling fallback, and artifact downloads.
- Produces: `#validation-arguments-details`, `.validation-checklist`, direct Step 4 generation transition, `groupProgressStages(stages)`, grouped five-stage pipeline, and disabled artifact placeholders while running.

- [ ] **Step 1: Add failing Step 4/5 contracts**

  Assert numbered validation tasks, optional argument disclosure, no Step 4 next button, five progress groups, and disabled artifact placeholders:

  ```java
  assertTrue(editor.contains("id=\"validation-arguments-details\""));
  assertTrue(editor.contains("class=\"validation-checklist\""));
  assertFalse(editor.contains("id=\"step-next-4\""));
  assertTrue(progress.contains("export function groupProgressStages"));
  assertTrue(editor.contains("id=\"artifact-placeholder-list\""));
  ```

- [ ] **Step 2: Run the focused test and capture RED**

  Run the Task 2 focused test. Expected: FAIL because Step 4 still exposes raw JSON and a separate next button, and Step 5 has only the raw stage list.

- [ ] **Step 3: Implement Step 4's guided validation flow**

  Keep `#validation-arguments` inside native `<details id="validation-arguments-details">`. Render a readable parameter summary from the selected operation. Before preview, show pending validation facts; after preview, derive facts only from existing analysis, enabled Tool count, preview Tool count, and representative-operation inclusion. `프로젝트 생성` remains disabled until preview succeeds and calls the existing `startGeneration()` directly.

- [ ] **Step 4: Implement five visual progress groups without losing raw stages**

  Export a pure grouping function using the exact mapping in the spec. `renderProgress()` renders grouped status in the hero pipeline and keeps all eight server stages in the detailed list. A group is failed if any member fails, running if any member runs, complete only when every observed member settles successfully, and pending otherwise.

- [ ] **Step 5: Add running artifact placeholders and recovery actions**

  Render three disabled rows while no downloads exist. Replace them with existing artifact buttons when downloads arrive. Keep hosted cancellation and local deletion semantics mode-specific. `설정으로 돌아가기` navigates to Step 4 without clearing configuration.

- [ ] **Step 6: Run focused tests and exercise success/failure states**

  Verify PASS. Run preview, start generation, observe grouped and raw stage updates, confirm artifacts replace placeholders on completion, and confirm failure keeps its raw stage identity.

---

### Task 5: Responsive polish and complete verification

**Files:**
- Modify: `apps/web/src/main/resources/static/editor.css`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`
- Modify: `design-qa.md`

**Interfaces:**
- Consumes: completed Step 1-5 markup and approved reference images.
- Produces: desktop/tablet/mobile layouts and final evidence for automated, keyboard, console, and visual checks.

- [ ] **Step 1: Add failing responsive contracts**

  Require Step 3 stacking, Step 4 stacking, Step 5 vertical stages, and no action-dock clearance at compact breakpoints:

  ```java
  assertTrue(editorStyles.contains(".tool-workspace { grid-template-columns: 1fr;"));
  assertTrue(editorStyles.contains(".preview-workspace { grid-template-columns: 1fr;"));
  assertTrue(editorStyles.contains(".progress-overview { grid-template-columns: 1fr;"));
  assertFalse(editorStyles.contains("padding-bottom: var(--action-dock-clearance)"));
  ```

- [ ] **Step 2: Run the focused test and capture RED**

  Run the Task 2 focused test. Expected: FAIL until the new responsive selectors are present.

- [ ] **Step 3: Implement compact layouts**

  At 760px stack Step 3 and Step 4 workspaces and convert progress groups to a vertical timeline. At 400px stack summary items without overflow, preserve 44px targets, and keep in-flow actions full width where necessary.

- [ ] **Step 4: Run complete automated verification**

  Run:

  ```bash
  mise run ui:test
  mise run generator:test
  mise run ui:build
  ```

  Expected: every command exits 0 with no failed tests.

- [ ] **Step 5: Run browser verification against both fixtures**

  Start `mise run dev`, then execute Steps 1-5 with `swagger-3.0.yml` and `swagger-3.1.yml` at 1440 × 1024 and 400 × 900. Check keyboard-only operation, focus order, live announcements, reduced motion, console warnings/errors, and `document.documentElement.scrollWidth <= window.innerWidth`.

- [ ] **Step 6: Compare and record final design QA**

  Capture each implemented step at 1440 × 1024, compare it with the corresponding approved reference from the spec, fix P0-P2 mismatches, and update `design-qa.md` with accepted screenshots, comparison notes, known limits, and the final result.
