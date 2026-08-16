# Stepwise Editor and Slate Indigo Design System Design

- Status: approved
- Date: 2026-08-16
- Related specifications:
  - `docs/superpowers/specs/2026-08-11-local-operation-editor-design.md`
  - `docs/superpowers/specs/2026-08-12-spring-boot-thymeleaf-web-migration-design.md`
  - `docs/superpowers/specs/2026-08-14-openapi-31-guided-editor-design.md`

## 1. Purpose

Convert the editor from one long scrolling page into a four-step wizard that shows exactly one step at a time, replace the flat eight-stage generation progress list with a progress bar that keeps the stage detail behind progressive disclosure, and restate the visual layer on the Slate and Indigo token set.

This design supersedes the single-page three-section information architecture in the OpenAPI 3.1 guided editor design. It does not supersede that design's canonical analysis contract, its server-owned support decision, or any generation, validation, security, or artifact contract.

## 2. Problem Statement

The guided editor collapsed five sections into three, but the third section absorbed most of the removed content. `STEP 3` now carries six project metadata fields, the selected Tool accordion, the per-Tool advanced policy editor, the representative call form, the preview trigger and its rendered output, the generation trigger, the progress list, and the download list. The page is therefore still long, and the three-step navigation at the top is a set of anchor links rather than a state machine: every step is visible at all times, so the step numbering communicates order without communicating position.

The generation progress renders each of the eight pipeline stages as one `ANALYZE: SUCCESS` list item. The stage identifiers are internal English constants, the list occupies vertical space proportional to stage count rather than to user interest, and there is no aggregate signal of how far the job has advanced.

The current palette is an ad hoc set of literal hex values. Roughly forty distinct colors appear inline across `styles.css` with no token indirection, which makes any coordinated visual change a find-and-replace exercise.

## 3. Goals

- Present the editor as four steps with one step visible at a time.
- Gate forward movement on the completion of the current step, while leaving backward movement unrestricted.
- Preserve every entered value when the user moves backward and forward.
- Survive a page reload by restoring both the retained specification and the current step.
- Replace the eight-item progress list with a progress bar, a Korean current-stage sentence, and a collapsed detail disclosure.
- Restate the palette as Slate and Indigo design tokens with separate surface and text tokens for every semantic color.
- Keep every interactive target at or above 44 px and every text color at or above WCAG AA contrast on its own background.
- Keep the 400 px viewport free of horizontal overflow.

## 4. Non-goals

- Changing the progress transport. Polling remains; no `SseEmitter` endpoint is introduced.
- Changing `GenerationProgress.STAGES`, the pipeline, the job manager, or any hosted job contract.
- Changing the analysis contract, the preview contract, or the generation configuration payload.
- Adding a dark theme. The token structure must accommodate one later without restructuring.
- Loading a web font. The strict Content Security Policy forbids it, as explained in section 8.3.
- Replacing Thymeleaf or the existing ECMAScript modules with a frontend framework.
- Redesigning the hosted dashboard (`dashboard.html`) or the job detail page.

## 5. Considered Approaches

### 5.1 Selected: client-side wizard in one document

`editor.html` keeps one document containing four panels. Only the active panel lacks the `hidden` attribute. A new `wizard.js` module owns step state, gating, and navigation.

This was selected because the entire in-progress configuration already lives in browser memory in `state.js`, while the server retains only a `specificationId`. Keeping all four panels in the DOM makes backward navigation lossless without any serialization work, because the form controls themselves hold the values.

### 5.2 Rejected: one server route per step

Separate Thymeleaf templates behind `/editor/step/{n}` would give each step a real URL. It was rejected because the configuration state would have to move into a server session, which introduces per-step form round trips, per-step CSRF handling, and a hosted-mode re-authentication path for a change that is presentational. It becomes the better option if steps exceed six or if a step ever requires server-side validation before advancing.

### 5.3 Rejected: CSS `:target` panel switching

Switching panels with `:target` selectors and anchor links would need no step state at all. It was rejected because gating is the point of the redesign, and `:target` cannot express "this step is not reachable yet."

## 6. Step Structure

| Step | Title | Content |
| --- | --- | --- |
| 1 | OpenAPI 파일 | Drop zone, upload states, analysis summary |
| 2 | Endpoint 선택 | Search, status filter, select-all, operation list |
| 3 | 생성 설정 | Six project metadata fields, profile selection, selected Tool list and per-Tool editor |
| 4 | 생성 및 결과 | Representative call, preview, generation trigger, progress, downloads |

Steps 1 and 2 keep their current content unchanged. The existing `STEP 3` splits at the `preview-section` boundary: everything above it becomes step 3, and `preview-section` becomes step 4.

The `generation-summary` aside moves out of step 3 and up to the wizard shell. It is hidden during step 1, where no analyzed data exists, and visible during steps 2, 3, and 4. On viewports at or below 720 px it stops being sticky and renders below the active panel, matching the existing responsive rule.

## 7. Navigation and Gating

### 7.1 Step state

`state.js` gains `currentStep`, an integer from 1 to 4, persisted to `sessionStorage` under `gen2spring.currentStep` alongside the existing specification and job keys. On load the restored step is clamped to the highest step the restored state actually satisfies, so a reload can never land on an unreachable step.

Clamping must run against in-memory state, not persisted keys. Local mode exposes no analysis route — `/api/specifications/{id}/analysis` is registered only by the hosted controller — so `resumeRetainedSpecification()` returns early when `api.hostedMode` is false and the retained `specificationId` names a specification whose operations were never re-fetched. A gate that trusted that id would place the user on an empty step 2 with no way forward. Gate 1 therefore reads `state.analysis`, which only a completed analysis populates. In local mode a reload consequently returns to step 1, matching the idle upload surface the user actually sees.

### 7.2 Advance conditions

| Transition | Condition |
| --- | --- |
| 1 to 2 | `state.analysis` is populated |
| 2 to 3 | At least one operation has `enabled === true` |
| 3 to 4 | All of `group-id`, `artifact-id`, `package-name`, `provider-name`, `domain-name`, `target-profile` are non-empty |

Step 4 has no outgoing transition. Its internal gate is unchanged: `generate-button` stays disabled until a preview succeeds.

The gate predicate is isolated in one exported function, `canAdvance(step, state)`, in `wizard.js`. Isolating it keeps the navigation wiring free of validation detail and makes the rule set testable as a unit.

### 7.3 Backward movement and invalidation

Backward movement is always permitted. The stepper chips at the top are buttons; a chip is enabled when its step index is at most the highest step currently satisfied, so users may jump back freely but cannot skip ahead past an unmet gate.

When an edit on an earlier step breaks a later step's precondition, `currentStep` is clamped down to the highest satisfied step. This hooks into the existing `invalidatePreview()` path in `app.js`, which already runs on every field change and every selection change.

### 7.4 URL and history

`wizard.js` writes `#step-{n}` to the location hash on each transition and listens for `hashchange`, so the browser back button moves between steps rather than leaving the editor. A hash naming a step that fails its gate is clamped in the same way as a restored step.

### 7.5 Accessibility

- The stepper is an `<ol>`; the active chip carries `aria-current="step"`, and unreachable chips are `disabled`.
- Inactive panels keep the `hidden` attribute, which removes them from the accessibility tree while preserving their form values in the DOM.
- On each transition, focus moves to the newly activated panel's `<h2>`, which carries `tabindex="-1"`.
- A visually hidden live region announces the new step, for example `4단계 중 3단계, 생성 설정`.
- Next and previous controls are `<button type="button">` at the documented 44 px minimum height.

## 8. Visual Design

### 8.1 Token set

Tokens replace the literal hex values currently spread through `styles.css`. Surface tokens and text tokens are separate for every semantic color, because the Slate and Indigo semantic hues are calibrated for fills rather than for glyphs.

| Token | Value | Role |
| --- | --- | --- |
| `--bg` | `#f8fafc` | Page background |
| `--surface` | `#ffffff` | Card background |
| `--surface-2` | `#f1f5f9` | Recessed background, card header |
| `--surface-3` | `#e2e8f0` | Track background, inactive step circle |
| `--line` | `#e2e8f0` | Default border |
| `--line-strong` | `#94a3b8` | Emphasized border, dashed drop zone |
| `--text` | `#0f172a` | Body text |
| `--muted` | `#475569` | Secondary text |
| `--soft` | `#64748b` | Tertiary text, labels |
| `--primary` | `#4f46e5` | Primary action fill, active step |
| `--primary-dark` | `#3730a3` | Primary hover |
| `--primary-light` | `rgba(79, 70, 229, 0.08)` | Primary tint background |
| `--success` | `#10b981` | Success fill and border |
| `--success-text` | `#047857` | Success glyphs |
| `--warning` | `#f59e0b` | Warning fill and border |
| `--warning-text` | `#92400e` | Warning glyphs |
| `--danger` | `#ef4444` | Danger fill and border |
| `--danger-text` | `#b91c1c` | Danger glyphs |
| `--radius` | `12px` | Card radius |
| `--shadow` | Layered slate shadow | Card elevation |
| `--dot-color` | `rgba(79, 70, 229, 0.05)` | Page dot pattern |

### 8.2 Contrast verification

Measured against `--surface` `#ffffff`:

| Color | Ratio | Verdict |
| --- | --- | --- |
| `--text` `#0f172a` | 17.9:1 | Body text, passes AAA |
| `--muted` `#475569` | 7.6:1 | Secondary text, passes AAA |
| `--soft` `#64748b` | 4.8:1 | Tertiary text, passes AA |
| `--primary` `#4f46e5` | 6.3:1 | Link and glyph text, passes AA |
| `--success` `#10b981` | 2.5:1 | Fill only, must not carry text |
| `--success-text` `#047857` | 5.5:1 | Passes AA |
| `--warning` `#f59e0b` | 2.1:1 | Fill only, must not carry text |
| `--warning-text` `#92400e` | 7.1:1 | Passes AA |
| `--danger` `#ef4444` | 3.8:1 | Fill only, must not carry text |
| `--danger-text` `#b91c1c` | 6.5:1 | Passes AA |

White text on a `--primary` fill measures 6.3:1 and passes AA, which covers primary buttons and the active step circle.

The existing rule `.progress-list li[data-status="SUCCESS"] { color: var(--success); }` must change to `--success-text`. Assigning `--success` to that rule would drop the ratio from the current 4.0:1 to 2.5:1.

### 8.3 Typography

The font stack stays `Inter, ui-sans-serif, system-ui, sans-serif` with no `@font-face` and no external stylesheet link. `WebSecurityConfiguration.CONTENT_SECURITY_POLICY` declares `default-src 'none'` and defines no `font-src`, so any remote font request is blocked. Inter renders when the operating system already provides it and the stack falls through otherwise. Relaxing the policy for a typeface is not justified here.

### 8.4 Page treatment

The body gains the reference theme's dot pattern: `radial-gradient(var(--dot-color) 1.5px, transparent 1.5px)` at `20px 20px`. It costs no request and no script.

### 8.5 Dark theme readiness

No `[data-theme="dark"]` block ships. Every color in `styles.css` must resolve through a token so that adding one later is a single additional block rather than an audit. This is a structural constraint on the implementation, not a deliverable.

## 9. Progress Presentation

### 9.1 Markup

A new `#job-progress` region replaces the bare `#progress-list` in step 4:

- a status line holding the Korean label for the running stage and a `3 / 8` counter;
- a progress track and fill;
- a `<details>` disclosure labeled `상세 보기` wrapping the existing `#progress-list`.

`#progress-list` keeps its `data-status` attributes so its per-stage styling survives.

### 9.2 Stage labels

A new `progress.js` module owns the mapping from the eight stage constants to Korean labels:

| Stage | Label |
| --- | --- |
| `ANALYZE` | OpenAPI 문서 분석 |
| `GENERATE` | 프로젝트 코드 생성 |
| `COMPILE` | Gradle 컴파일 |
| `APPLICATION_CONTEXT` | Spring 컨텍스트 기동 |
| `MCP_INITIALIZE` | MCP 서버 초기화 |
| `MCP_TOOLS_LIST` | Tool 목록 검증 |
| `MCP_TOOL_CALL` | 대표 Tool 호출 검증 |
| `PACKAGE` | 산출물 패키징 |

An unrecognized stage identifier falls back to the raw constant rather than throwing, so a future pipeline stage degrades to the current behavior instead of breaking the panel.

### 9.3 Behavior

- Completion ratio is `(SUCCESS + SKIPPED) / STAGES.length`, counted over the whole list on a clean run.
- On a failure the ratio counts only the stages **before** the failed one. A failure marks every later stage `SKIPPED`, so counting the full list would render a build that died at stage 3 as 88% complete. The bar must not overstate a failed run.
- The status line names the first `RUNNING` stage, or the terminal state when none is running.
- On a `FAILED` stage the disclosure opens automatically, the failed row renders in `--danger-text`, and the bar fill switches to `--danger` via `data-state="failed"`.
- The region stays `hidden` until a job starts and is cleared by the existing reset paths.

`app.js` delegates to `progress.js` from `renderJob()`. `pollJob()`, `TERMINAL_STATES`, and every job API call are untouched.

## 10. Module Boundaries

| Module | Owns | Depends on |
| --- | --- | --- |
| `state.js` | Application state including `currentStep`, session persistence | none |
| `wizard.js` | Step transitions, `canAdvance`, stepper rendering, hash sync, focus | `state.js` |
| `progress.js` | Stage labels, progress rendering | none |
| `upload.js` | Step 1 upload states | `state.js`, `api.js` |
| `operations.js` | Step 2 selection | `state.js` |
| `editor.js` | Step 3 Tool configuration, `buildConfiguration` | `state.js` |
| `app.js` | Wiring, preview and generation orchestration | all of the above |

`wizard.js` and `progress.js` are both leaf-ward: neither imports `app.js`, and `progress.js` imports nothing, taking the job snapshot as an argument. This keeps the existing one-directional dependency shape.

## 11. Changed Files

| File | Change |
| --- | --- |
| `apps/web/src/main/resources/templates/editor.html` | Wizard shell, four panels, stepper, progress region |
| `apps/web/src/main/resources/static/styles.css` | Token set, wizard, stepper, and progress styles |
| `apps/web/src/main/resources/static/wizard.js` | New |
| `apps/web/src/main/resources/static/progress.js` | New |
| `apps/web/src/main/resources/static/state.js` | `currentStep` field and persistence |
| `apps/web/src/main/resources/static/app.js` | Wizard initialization, progress delegation |
| `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java` | Markup and stylesheet contract updates |

`LocalOperationEditorIntegrationTest` exercises the HTTP API rather than the DOM and needs no change. `dashboard.html` and `job-detail.html` are out of scope; they share `styles.css` and inherit the new tokens without markup changes.

## 12. Contract Test Updates

`StaticAssetContractTest` currently asserts the single-page structure. The following assertions become false and must be restated rather than deleted:

- `--success: #148f77` becomes the new token block, asserted through `--success-text: #047857` so the accessibility decision in section 8.2 is locked by a test.
- The three `<h2 id="...-title">` assertions extend to a fourth step heading.
- `assertFalse(index.contains("<h2>4."))` is removed, because a fourth step now exists. The guard against a fifth step is retained.
- New assertions cover the stepper list, the panel `hidden` mechanism, `aria-current`, the progress region identifiers, and `canAdvance` in `wizard.js`.

The existing negative assertions on `innerHTML`, `Authorization`, `js-yaml`, `SwaggerParser`, and `X-Gen2Spring-Token` extend to cover `wizard.js` and `progress.js`.

## 13. Verification

- `./gradlew :apps:web:test` for the contract and MVC tests.
- Manual pass on the running local instance: upload `swagger-3.0.yml` and `swagger-3.1.yml`, walk all four steps, generate, and confirm the progress bar, the collapsed detail, and the downloads.
- Reload mid-flow on step 3 and confirm the step and the analyzed specification both restore.
- Break a step 3 required field and confirm the wizard clamps back rather than stranding the user on step 4.
- Browser back button moves one step back rather than leaving the editor.
- 400 px viewport shows no horizontal overflow and the summary aside renders below the active panel.
- Keyboard-only pass: tab order, focus movement on transition, and the live region announcement.

## 14. Risks

- Constraint: all four panels stay in the DOM, so document size grows and every module must scope its queries to its own panel.
- Risk: clamping `currentStep` on invalidation can move users backward unexpectedly if the clamp is too eager. The clamp must run only when a gate genuinely fails, not on every state write.
- Exception: `sessionStorage` restores the step but not scroll position within a panel; a reload lands at the top of the restored step.
