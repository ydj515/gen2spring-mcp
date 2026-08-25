# Upload and Policy UX Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refine Step 1 upload completion and Step 3 policy configuration so state, progressive disclosure, and help are immediately understandable.

**Architecture:** Keep Thymeleaf as the rendered structure, `upload.js` as upload-state owner, and `editor.js` as Tool-policy state owner. Replace the policy-native disclosures with accessible button-controlled sections so accordion expansion and help popovers are independent, without changing persisted configuration or API payloads.

**Tech Stack:** Java 21, Spring Boot 3.5, Thymeleaf, Bootstrap 5.3.8, Bootstrap Icons 1.13.1, ECMAScript modules, JUnit 5, MockMvc, Codex in-app browser.

**Spec:** `docs/superpowers/specs/2026-08-25-upload-policy-ux-refinement-design.md`

## Global Constraints

- Add no dependency and make no backend/API contract change.
- Preserve entered Retry and Pagination values while their fields are hidden and disabled.
- Use Bootstrap Icons for every new visible icon; do not add SVG, CSS drawings, emoji, or `innerHTML`.
- Keep help controls keyboard accessible and independent from accordion controls.
- Keep the approved implementation and QA evidence together in one consolidated feature commit when the user explicitly requests it.

---

### Task 1: Lock the rendered UX contracts

**Files:**
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`

**Interfaces:**
- Consumes: rendered `/editor` HTML and packaged static assets.
- Produces: regression contracts for title copy, upload completion presentation, policy controls, status summaries, and policy help semantics.

- [x] **Step 1: Write failing contracts**

  Assert that all five rendered headings omit numeric prefixes, the upload status has a separate icon and text node, each policy has a toggle/help pair with `aria-controls`, Retry and Pagination have status nodes and dependent field containers, and policy help is hidden by default.

- [x] **Step 2: Verify RED**

  Run:

  ```bash
  ./gradlew :apps:web:test --tests 'io.gen2spring.mcp.app.web.StaticAssetContractTest' --tests 'io.gen2spring.mcp.app.web.WebMvcContractTest' --no-daemon --rerun-tasks
  ```

  Expected: FAIL because the current headings are numbered, the live status has no icon node, and policy headers do not expose independent help controls or status summaries.

### Task 2: Implement upload replacement and unnumbered headings

**Files:**
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/upload.js`
- Modify: `apps/web/src/main/resources/static/editor.css`

**Interfaces:**
- Consumes: `setUploadState(state)`, `#upload-dropzone`, `#uploaded-file`, and `#upload-live-status`.
- Produces: `#upload-status-icon`, `#upload-status-text`, and a completed state where the file card replaces the dropzone.

- [x] **Step 1: Implement minimal upload state behavior**

  `setUploadState('completed')` hides the dropzone, shows the file card, and reveals the success icon. Dragging over a completed upload keeps the compact card visible; leaving restores `completed` instead of resetting presentation to `idle`.

- [x] **Step 2: Remove numeric prefixes**

  Render the exact panel headings `OpenAPI 파일`, `API endpoint 선택`, `생성 설정`, `미리보기와 생성`, and `생성 진행`, keeping their existing IDs and focus behavior.

- [x] **Step 3: Run focused tests**

  Run the Task 1 command. The title and upload contracts should pass while the policy contracts remain RED.

### Task 3: Implement progressive policy controls and contextual help

**Files:**
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/editor.js`
- Modify: `apps/web/src/main/resources/static/editor.css`

**Interfaces:**
- Consumes: `saveSelectedOperation()`, `renderSelectedOperation()`, `setPolicyControls(prefix, enabled)`, and the current operation model.
- Produces: `.policy-section__toggle`, `.policy-help-button`, `.policy-help`, `setPolicySectionOpen(section, open)`, `setPolicyHelpOpen(button, open)`, and `updatePolicySummaries(operation)`.

- [x] **Step 1: Build independent section and help controls**

  Each policy header contains one accordion button and one information button. Accordion buttons update `aria-expanded`, hide/show their controlled body, and close other bodies. Help buttons update `aria-expanded`, hide/show their controlled tooltip, close other help, close on outside click or Escape, and never change accordion state.

- [x] **Step 2: Improve enabled-policy hierarchy**

  Retry and Pagination bodies begin with a full-width enable row. When off, dependent fields are hidden and disabled; when on, the same state-backed fields appear below a divider. Toggling retains field values.

- [x] **Step 3: Render policy summaries**

  Retry and Pagination show `사용 중` or `사용 안 함`. Parameter sources show total parameters and server-secret count. Response normalization shows `기본값` or `사용자 설정` based on non-empty normalization fields.

- [x] **Step 4: Verify GREEN**

  Run the Task 1 command and confirm both test classes pass.

### Task 4: Full verification and visual QA

**Files:**
- Modify: `design-qa.md`
- Create: `docs/superpowers/specs/assets/upload-policy-ux-refinement/qa/*.png`

**Interfaces:**
- Consumes: the running local editor and the two user screenshots.
- Produces: same-state desktop/mobile evidence and a passing `design-qa.md`.

- [x] **Step 1: Run the full Web test suite**

  ```bash
  ./gradlew :apps:web:test --no-daemon --rerun-tasks
  ```

- [x] **Step 2: Exercise primary interactions**

  In the in-app browser, verify upload success replacement, file replacement entry point, Retry/Pagination off/on disclosure, all four help popovers, outside-click/Escape dismissal, one-open-section behavior, and preservation across Tool selection.

- [x] **Step 3: Capture and compare**

  Capture matching desktop states and a 400px-wide responsive state. Compare source and implementation together, fix every P0/P1/P2 issue, and record dimensions, states, findings, iterations, and `final result: passed` in `design-qa.md`.
