# Gen2Spring MCP UI/UX Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign every Thymeleaf user screen around the approved full-width, no-sidebar visual system while preserving all existing generator and hosted-platform behavior.

**Architecture:** Package Bootstrap CSS and Bootstrap Icons through WebJars, then layer application tokens and scoped styles over them. Reuse a small Thymeleaf fragment set for the shared shell, retain the current browser-state and API modules, and limit JavaScript changes to grouped endpoint presentation and layout state.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Thymeleaf, Bootstrap 5.3.8 WebJar, Bootstrap Icons 1.13.1 WebJar, ECMAScript modules, JUnit 5, MockMvc, Codex in-app browser.

**Spec:** `docs/superpowers/specs/2026-08-24-ui-ux-redesign-design.md`

## Global Constraints

- No left sidebar or left navigation menu.
- Preserve all API, database, authentication, generation, validation, job-state, and artifact contracts.
- Use Bootstrap `5.3.8` and Bootstrap Icons `1.13.1` from packaged WebJars; do not use a CDN or remote font.
- Do not load Bootstrap JavaScript; existing modules and native elements own interactions.
- Keep unsupported endpoints visible, disabled, and explained with server-owned issue messages.
- Keep every core action usable at `400 x 900` without horizontal overflow.
- Do not use `innerHTML`, custom inline SVG, CSS-drawn icons, emoji, or text glyphs as icons.
- Use `swagger-3.0.yml` and `swagger-3.1.yml` for the complete local browser flow.
- Do not change the hosted resource queries or introduce new dashboard metrics.

---

### Task 1: Package Bootstrap and establish the design-token foundation

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `apps/web/build.gradle.kts`
- Create: `apps/web/src/main/resources/static/design-tokens.css`
- Create: `apps/web/src/main/resources/static/app-shell.css`
- Create: `apps/web/src/main/resources/static/editor.css`
- Create: `apps/web/src/main/resources/static/hosted.css`
- Modify: `apps/web/src/main/resources/static/styles.css`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/security/WebSecurityConfiguration.java`
- Modify: `apps/web/src/main/java/io/gen2spring/mcp/app/web/security/HostedSecurityConfiguration.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`

**Interfaces:**
- Consumes: Spring Boot's default `/webjars/**` resource mapping.
- Produces: `libs.bootstrap`, `libs.bootstrap.icons`, and the CSS variables documented in the approved spec.

- [ ] **Step 1: Add failing dependency and token contracts**

  Extend `StaticAssetContractTest` to load the five application CSS resources and assert the fixed core variables and import order:

  ```java
  assertTrue(tokens.contains("--app-bg: #f6f8fc"));
  assertTrue(tokens.contains("--app-primary: #3568f4"));
  assertTrue(tokens.contains("--app-danger-soft: #fff0f1"));
  assertTrue(styles.contains("@import url('/design-tokens.css')"));
  assertTrue(styles.indexOf("design-tokens.css") < styles.indexOf("app-shell.css"));
  assertTrue(WebSecurityConfiguration.CONTENT_SECURITY_POLICY.contains("font-src 'self'"));
  ```

- [ ] **Step 2: Run the focused test and capture RED**

  Run:

  ```bash
  mise exec -- ./gradlew :apps:web:test \
    --tests 'io.gen2spring.mcp.app.web.StaticAssetContractTest' \
    --no-daemon --non-interactive --rerun-tasks
  ```

  Expected: FAIL because the WebJar catalog aliases and new CSS files do not exist.

- [ ] **Step 3: Add pinned WebJar dependencies**

  Add version-catalog entries with these exact coordinates:

  ```toml
  bootstrap = "5.3.8"
  bootstrap-icons = "1.13.1"

  bootstrap = { module = "org.webjars:bootstrap", version.ref = "bootstrap" }
  bootstrap-icons = { module = "org.webjars.npm:bootstrap-icons", version.ref = "bootstrap-icons" }
  ```

  Add `implementation(libs.bootstrap)` and `implementation(libs.bootstrap.icons)` to `apps/web/build.gradle.kts`.

  Extend both local and hosted deny-by-default CSP policies with `font-src 'self'` so the packaged Bootstrap Icons font can load. Do not widen any other directive.

- [ ] **Step 4: Add the CSS responsibility boundaries**

  Define the approved variables in `design-tokens.css`, bridge them to `--bs-*` variables, place shared page/surface/action patterns in `app-shell.css`, and reserve `editor.css` and `hosted.css` for their named screens. Keep `styles.css` as the ordered local entrypoint:

  ```css
  @import url('/design-tokens.css');
  @import url('/app-shell.css');
  @import url('/editor.css');
  @import url('/hosted.css');
  ```

  Move existing rules without changing behavior yet. Remove literal application colors as each rule moves; Bootstrap's own compiled values are out of this constraint.

- [ ] **Step 5: Run the focused test and capture GREEN**

  Run the Step 2 command. Expected: PASS with both WebJars resolved by Gradle and the application tokens present.

- [ ] **Step 6: Commit the foundation**

  ```bash
  git add gradle/libs.versions.toml apps/web/build.gradle.kts \
    apps/web/src/main/java/io/gen2spring/mcp/app/web/security \
    apps/web/src/main/resources/static apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java \
    docs/superpowers/specs/2026-08-24-ui-ux-redesign-design.md \
    docs/superpowers/plans/2026-08-24-ui-ux-redesign.md
  git commit -m "feat: establish web design system foundation"
  ```

### Task 2: Introduce the shared no-sidebar application shell

**Files:**
- Create: `apps/web/src/main/resources/templates/fragments/ui.html`
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/templates/dashboard.html`
- Modify: `apps/web/src/main/resources/templates/job-detail.html`
- Modify: `apps/web/src/main/resources/static/app-shell.css`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/HostedModeContractTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`

**Interfaces:**
- Consumes: Bootstrap and application stylesheet paths from Task 1; existing `appMode`, CSRF, specifications, jobs, events, and artifacts model values.
- Produces: Thymeleaf fragments `head-assets`, `app-header`, and `wizard-progress`.

- [ ] **Step 1: Add failing shell contracts**

  Assert that all templates use Korean document language, replace the shared fragment, contain no sidebar, and preserve hosted routes:

  ```java
  assertTrue(editor.contains("th:replace=\"~{fragments/ui :: head-assets"));
  assertTrue(fragment.contains("/webjars/bootstrap/5.3.8/css/bootstrap.min.css"));
  assertTrue(fragment.contains("/webjars/bootstrap-icons/1.13.1/font/bootstrap-icons.min.css"));
  assertFalse(fragment.contains("bootstrap.bundle"));
  assertTrue(dashboard.contains("th:replace=\"~{fragments/ui :: app-header"));
  assertTrue(jobDetail.contains("href=\"/\""));
  assertFalse(editor.contains("app-sidebar"));
  assertFalse(dashboard.contains("app-sidebar"));
  ```

  Extend the MVC editor assertion to require the Bootstrap and application CSS hrefs in the rendered response.

- [ ] **Step 2: Run the focused contracts and capture RED**

  ```bash
  mise exec -- ./gradlew :apps:web:test \
    --tests 'io.gen2spring.mcp.app.web.StaticAssetContractTest' \
    --tests 'io.gen2spring.mcp.app.web.HostedModeContractTest' \
    --tests 'io.gen2spring.mcp.app.web.WebMvcContractTest.rendersTheThreeStepThymeleafEditorAtBothLocalRoutes' \
    --no-daemon --non-interactive --rerun-tasks
  ```

  Expected: FAIL on missing fragments and shell markup.

- [ ] **Step 3: Implement the shared fragments**

  Add `head-assets` with favicon, Bootstrap CSS, Bootstrap Icons, and `/styles.css` in that order. Add `app-header(appMode)` with product identity always visible and hosted navigation guarded by `th:if="${appMode == 'hosted'}"`. Add `wizard-progress` using the existing five button identifiers and `data-step` values.

- [ ] **Step 4: Apply the shell to all three templates**

  Keep every existing form action, CSRF field, route, element identifier, and script source. Convert hosted-facing copy to Korean, add page headers and breadcrumb structure, and remove the old standalone headers.

- [ ] **Step 5: Style the shell and responsive header**

  Implement `.app-header`, `.app-header__nav`, `.page-header`, `.app-main`, `.surface`, `.status-badge`, `.empty-state`, and `.action-dock`. At 400px, wrap hosted navigation without horizontal overflow and preserve 44px action targets.

- [ ] **Step 6: Run the focused contracts and capture GREEN**

  Run the Step 2 command. Expected: PASS with identical routes and CSRF fields.

- [ ] **Step 7: Commit the shared shell**

  ```bash
  git add apps/web/src/main/resources/templates apps/web/src/main/resources/static/app-shell.css \
    apps/web/src/test/java/io/gen2spring/mcp/app/web
  git commit -m "feat: add shared Thymeleaf application shell"
  ```

### Task 3: Recompose the wizard progress, summary, and persistent actions

**Files:**
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/templates/fragments/ui.html`
- Modify: `apps/web/src/main/resources/static/editor.css`
- Modify: `apps/web/src/main/resources/static/wizard.js`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`

**Interfaces:**
- Consumes: existing `initializeWizard`, `canAdvance`, `blockingReason`, summary element identifiers, and the Task 2 `wizard-progress` fragment.
- Produces: thin connected step progress, horizontal `generation-summary`, and viewport-visible `.wizard-nav` docks.

- [ ] **Step 1: Add failing visual-structure contracts**

  Require connector markup/classes, horizontal summary semantics, sticky action rules, bottom clearance, and mobile step scrolling:

  ```java
  assertTrue(index.contains("class=\"wizard-step-connector\""));
  assertTrue(index.contains("class=\"generation-summary summary-strip\""));
  assertTrue(editorStyles.contains(".wizard-nav { position: sticky;"));
  assertTrue(editorStyles.contains("scroll-padding-inline"));
  assertTrue(editorStyles.contains("padding-bottom: var(--action-dock-clearance)"));
  ```

- [ ] **Step 2: Run `StaticAssetContractTest` and capture RED**

  Use the Task 1 focused test command. Expected: FAIL on the new structure.

- [ ] **Step 3: Recompose the editor shell**

  Move `#generation-summary` above the active panel region while retaining all six existing summary IDs. Render the five steps as numbered nodes separated by decorative connector spans with `aria-hidden="true"`. Keep each visible panel's existing back/next button IDs inside its `.wizard-nav`.

- [ ] **Step 4: Implement sticky and responsive behavior**

  Style the stepper as a thin connected row, the summary as a horizontal strip, and `.wizard-nav` as a viewport-bottom dock with a background and top border. At 400px, make the stepper horizontally scrollable, summary a two-column grid, and action buttons usable without obscuring content.

- [ ] **Step 5: Keep the current step visible on narrow screens**

  In `wizard.js`, after rendering the active chip, call `scrollIntoView({block: 'nearest', inline: 'center'})` only when the stepper overflows and either the step changes or a hash navigation occurs. Respect `prefers-reduced-motion` by using `behavior: 'auto'`.

- [ ] **Step 6: Run focused tests and capture GREEN**

  Run `StaticAssetContractTest`. Expected: PASS while all existing gate, focus, hash, and live-region assertions remain green.

- [ ] **Step 7: Commit the wizard shell**

  ```bash
  git add apps/web/src/main/resources/templates/editor.html apps/web/src/main/resources/templates/fragments/ui.html \
    apps/web/src/main/resources/static/editor.css apps/web/src/main/resources/static/wizard.js \
    apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java
  git commit -m "feat: keep wizard progress and actions visible"
  ```

### Task 4: Render Step 2 as a grouped endpoint table

**Files:**
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/operations.js`
- Modify: `apps/web/src/main/resources/static/editor.css`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`

**Interfaces:**
- Consumes: `state.operations`, `operation.sourceIndex`, `operation.supported`, `operation.status`, `operation.issues`, `onSelectionChange`, and `onEdit`.
- Produces: exported `groupOperations(operations)` and DOM group sections keyed by a deterministic path-derived resource key.

- [ ] **Step 1: Add failing grouped-renderer contracts**

  Require the pure grouping function, path labels, DOM-safe construction, issue rows, and preservation of selection semantics:

  ```java
  assertTrue(operations.contains("export function groupOperations(operations)"));
  assertTrue(operations.contains("'schema-contracts': '스키마 계약'"));
  assertTrue(operations.contains("operation.issues"));
  assertTrue(operations.contains("group.replaceChildren"));
  assertTrue(operations.contains("operation.sourceIndex"));
  assertFalse(operations.contains("innerHTML"));
  ```

- [ ] **Step 2: Run `StaticAssetContractTest` and capture RED**

  Use the Task 1 focused command. Expected: FAIL because operations still render independent cards.

- [ ] **Step 3: Implement deterministic grouping**

  Add a single-pass grouping function that strips an initial `api` segment, maps known first resource segments to Korean labels, and preserves first-seen order:

  ```javascript
  export function groupOperations(operations) {
    const groups = new Map();
    for (const operation of operations) {
      const group = operationGroup(operation.path);
      if (!groups.has(group.key)) groups.set(group.key, {...group, operations: []});
      groups.get(group.key).operations.push(operation);
    }
    return [...groups.values()];
  }
  ```

  Use stable fallback labels for unknown resource segments. Do not alter the analysis payload or domain model.

- [ ] **Step 4: Replace cards with grouped rows**

  Build each group and operation row with `document.createElement`, `textContent`, and the existing delegated `change`/`click` handlers. Show method, path, short summary, support text, chevron, and a full-width issue row. Unsupported checkboxes remain disabled and issues come directly from `operation.issues`.

- [ ] **Step 5: Style desktop and 400px layouts**

  At desktop widths use grid columns matching the approved visual. At 400px keep checkbox, method, path, summary, support, and chevron visible in a stacked row; the issue explanation spans the group width. The toolbar may wrap but must remain sticky above the list.

- [ ] **Step 6: Run focused and complete Web tests**

  ```bash
  mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
  ```

  Expected: PASS, including existing search, filter, select-all, support-state, and no-injection contracts.

- [ ] **Step 7: Commit endpoint presentation**

  ```bash
  git add apps/web/src/main/resources/templates/editor.html \
    apps/web/src/main/resources/static/operations.js apps/web/src/main/resources/static/editor.css \
    apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java
  git commit -m "feat: group endpoint selection into a dense table"
  ```

### Task 5: Redesign Steps 1, 3, 4, and 5 without changing behavior

**Files:**
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/editor.css`
- Modify: `apps/web/src/main/resources/static/progress.js`
- Modify: `apps/web/src/main/resources/static/editor.js`
- Modify: `apps/web/src/main/resources/static/app.js`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`

**Interfaces:**
- Consumes: all existing DOM IDs and the public functions `renderProgress`, `stateLabel`, `buildConfiguration`, and `renderGenerationSummary`.
- Produces: approved upload, configuration, preview, progress, artifact, and state layouts with unchanged payloads.

- [ ] **Step 1: Add failing contracts for the remaining steps**

  Require uploaded-file status, two-column settings, preview input/result regions, progress hero/pipeline, semantic artifact list, and retained IDs. Extend negative assertions to cover `innerHTML`, remote fonts, and inline SVG.

- [ ] **Step 2: Run focused tests and capture RED**

  ```bash
  mise exec -- ./gradlew :apps:web:test \
    --tests 'io.gen2spring.mcp.app.web.StaticAssetContractTest' \
    --tests 'io.gen2spring.mcp.app.web.WebMvcContractTest.rendersTheThreeStepThymeleafEditorAtBothLocalRoutes' \
    --no-daemon --non-interactive --rerun-tasks
  ```

  Expected: FAIL on the new regions while existing behavior assertions stay green.

- [ ] **Step 3: Recompose Step 1 and Step 3**

  Keep the upload state machine and all file controls. Place project fields in the responsive two-column settings grid and render selected Tool summaries as compact expandable rows. Continue moving the one existing `#operation-editor` into the open row so form values and event listeners remain authoritative.

- [ ] **Step 4: Recompose Step 4**

  Separate representative-call input from preview output with clear headings and semantic surfaces. Keep `#preview-button`, `#generate-button`, their disabled gates, and `#preview-status` unchanged. Give generation the only primary destructive-duration action weight.

- [ ] **Step 5: Recompose Step 5**

  Present the Korean state sentence, percentage/count, progress track, pipeline detail, and downloads in that order. Keep failure auto-expansion and all SSE/polling behavior. Use Bootstrap Icons via `<i class="bi ..." aria-hidden="true"></i>` only where the adjacent text supplies the accessible name.

- [ ] **Step 6: Run complete Web tests**

  ```bash
  mise run ui:test
  ```

  Expected: PASS with no API, job, preview, or artifact regression.

- [ ] **Step 7: Commit the remaining editor screens**

  ```bash
  git add apps/web/src/main/resources/templates/editor.html \
    apps/web/src/main/resources/static/editor.css apps/web/src/main/resources/static/progress.js \
    apps/web/src/main/resources/static/editor.js apps/web/src/main/resources/static/app.js \
    apps/web/src/test/java/io/gen2spring/mcp/app/web
  git commit -m "feat: redesign the complete generation workflow"
  ```

### Task 6: Redesign the hosted dashboard and job detail

**Files:**
- Modify: `apps/web/src/main/resources/templates/dashboard.html`
- Modify: `apps/web/src/main/resources/templates/job-detail.html`
- Modify: `apps/web/src/main/resources/static/hosted.css`
- Modify: `apps/web/src/main/resources/static/hosted.js`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/HostedModeContractTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`

**Interfaces:**
- Consumes: existing `specifications`, `jobs`, `job`, `events`, `artifacts`, CSRF, URL-import form, and hosted JavaScript.
- Produces: Korean responsive resource tables, consistent statuses, empty states, breadcrumb, and job timeline.

- [ ] **Step 1: Add failing hosted-screen contracts**

  Assert the Korean headings, current form IDs/actions, responsive table classes, empty-state copy, timeline, artifacts, and cancellation form. Preserve the negative assertions that prohibit duplicate upload/generation forms.

- [ ] **Step 2: Run hosted contracts and capture RED**

  ```bash
  mise exec -- ./gradlew :apps:web:test \
    --tests 'io.gen2spring.mcp.app.web.HostedModeContractTest' \
    --tests 'io.gen2spring.mcp.app.web.StaticAssetContractTest' \
    --no-daemon --non-interactive --rerun-tasks
  ```

  Expected: FAIL because current hosted templates are unstructured and partly English.

- [ ] **Step 3: Implement the dashboard hierarchy**

  Keep the URL-import form and `hosted.js` feedback target. Render specifications and jobs from their existing lists using responsive table/list markup and meaningful empty rows. Any displayed counts come from `#lists.size(...)`; do not add controller queries.

- [ ] **Step 4: Implement the job-detail hierarchy**

  Add dashboard breadcrumb, status summary, ordered event timeline, artifact downloads, and cancellation region while preserving current Thymeleaf expressions and form action. Do not infer a new cancellable-state rule in the browser; keep server behavior authoritative.

- [ ] **Step 5: Keep hosted feedback accessible**

  Update `hosted.js` only to assign semantic state data attributes and Korean safe feedback text through `textContent`. Preserve the import API, CSRF headers, and response handling.

- [ ] **Step 6: Run complete Web tests and capture GREEN**

  ```bash
  mise run ui:test
  ```

  Expected: PASS across local and hosted contracts.

- [ ] **Step 7: Commit hosted screens**

  ```bash
  git add apps/web/src/main/resources/templates/dashboard.html apps/web/src/main/resources/templates/job-detail.html \
    apps/web/src/main/resources/static/hosted.css apps/web/src/main/resources/static/hosted.js \
    apps/web/src/test/java/io/gen2spring/mcp/app/web
  git commit -m "feat: align hosted screens with the web design system"
  ```

### Task 7: Verify both OpenAPI versions, responsiveness, and visual fidelity

**Files:**
- Modify as findings require: `apps/web/src/main/resources/templates/**`
- Modify as findings require: `apps/web/src/main/resources/static/**`
- Create: `design-qa.md`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/WebMvcContractTest.java`
- Test: `apps/web/src/test/java/io/gen2spring/mcp/app/web/HostedModeContractTest.java`

**Interfaces:**
- Consumes: completed Tasks 1-6, the approved revised visual, `swagger-3.0.yml`, `swagger-3.1.yml`, `mise run dev`, and the Codex in-app browser.
- Produces: passing Gradle evidence, browser screenshots, console evidence, keyboard/responsive evidence, and `design-qa.md` with `final result: passed`.

- [ ] **Step 1: Run fast automated verification**

  ```bash
  mise run ui:test
  mise run generator:test
  git diff --check
  ```

  Expected: all commands exit `0` and `git diff --check` prints nothing.

- [ ] **Step 2: Run the local editor**

  Start `mise run dev` with an available `GEN2SPRING_UI_PORT` and keep it running. Open the reported local URL in the Codex in-app browser.

- [ ] **Step 3: Verify the complete `swagger-3.0.yml` flow**

  At `1440 x 1024`, upload the root fixture, verify 38 total/34 selected/4 unsupported, exercise search/filter/group expansion, visit Steps 3 and 4, start generation, observe Step 5, and verify downloads or the safe terminal result. Confirm that previous/next actions remain visible while the content scrolls.

- [ ] **Step 4: Verify OpenAPI 3.1 parity**

  Reset the editor, upload `swagger-3.1.yml`, and verify the same operation decisions and UI states with OpenAPI version `3.1.2`.

- [ ] **Step 5: Verify mobile and accessibility behavior**

  At `400 x 900`, repeat Steps 1-4 far enough to inspect upload, grouped endpoints, settings, and persistent actions. Assert `document.documentElement.scrollWidth <= window.innerWidth`, tab through controls, verify visible focus, and confirm the active wizard step remains visible. Check browser console errors.

- [ ] **Step 6: Verify hosted screens**

  Start the existing hosted runtime and capture dashboard and job detail at desktop and 400px. If the configured hosted dependencies cannot start, stop this task with `design-qa.md` set to `final result: blocked`; do not represent source-only inspection as browser verification or continue to handoff.

- [ ] **Step 7: Run the blocking visual comparison loop**

  Compare the approved revised visual and the Step 2 implementation at the same `1440 x 1024` viewport in one combined comparison input. Record typography, spacing, tokens, icons, copy, and responsive findings. Fix every P0/P1/P2 item, recapture, and repeat until `design-qa.md` ends exactly with:

  ```text
  final result: passed
  ```

- [ ] **Step 8: Run final verification after visual fixes**

  ```bash
  mise run ui:test
  mise run generator:test
  git diff --check
  git status --short
  ```

  Expected: tests pass, no whitespace errors, and only intended feature files are modified.

- [ ] **Step 9: Commit verification fixes and QA evidence**

  ```bash
  git add apps/web gradle/libs.versions.toml design-qa.md
  git commit -m "test: verify responsive web redesign"
  ```
