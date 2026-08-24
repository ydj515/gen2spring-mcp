# Gen2Spring MCP Wizard Flow Refinement Design

- Status: approved and implemented
- Date: 2026-08-24
- Supersedes the editor-flow, wizard-navigation, and editor responsive-layout requirements in `2026-08-24-ui-ux-redesign-design.md`.
- Preserves the existing API, generation, validation, persistence, security, and hosted-mode contracts.

## 1. Purpose

Refine the existing five-step OpenAPI-to-MCP editor so the user can understand the next action without discovering hidden controls, overlapping navigation, or nested editing cards. The approved direction combines the first visual proposal's connected top stepper with the third proposal's large icon-based generation summary.

The implementation remains a Thymeleaf page with ECMAScript modules. This work changes presentation and client-side flow only; it does not add backend endpoints, job states, or generator options.

## 2. Approved Visual References

The following images are the visual source of truth for desktop composition:

1. [Step 1 — OpenAPI file](assets/wizard-flow-refinement/step-1-openapi-file.png)
2. [Step 2 — Endpoint selection](assets/wizard-flow-refinement/step-2-endpoint-selection.png)
3. [Step 3 — Generation settings](assets/wizard-flow-refinement/step-3-generation-settings.png)
4. [Step 4 — Preview and generation](assets/wizard-flow-refinement/step-4-preview-generation.png)
5. [Step 5 — Generation progress](assets/wizard-flow-refinement/step-5-generation-progress.png)

These files are design references, not runtime application assets. Existing Bootstrap Icons remain the runtime icon source.

The generated reference files are 1487 × 1058 pixels. Browser implementation and QA use the repository's established 1440 × 1024 desktop viewport; comparison normalizes the references to that viewport and judges shared edges, hierarchy, spacing, and interaction structure rather than raw pixel equality.

## 3. Goals

- Align the stepper, generation summary, error summary, and active step to one content frame.
- Keep successful OpenAPI analysis visible until the user explicitly advances to Step 2.
- Replace floating previous/next controls with normal in-flow actions.
- Keep Tool selection directly visible as table-row checkboxes.
- Replace Step 3 nested cards with a flat table and one persistent Tool editor.
- Explain Step 4 as a numbered validation sequence ending in one project-generation action.
- Make Step 5's current stage, progress, cancellation, recovery, and artifact availability immediately visible.
- Enlarge summary icons and pair every icon with a stable label and value.
- Preserve keyboard operation, live announcements, focus movement, and 400px responsive support.

## 4. Non-goals

- Changing OpenAPI analysis or endpoint support decisions.
- Adding generation profiles, advanced policy types, job stages, artifacts, or APIs.
- Adding a frontend framework, Bootstrap JavaScript, remote fonts, or CDN assets.
- Adding a global sidebar, dashboard metrics, dark mode, charts, or decorative imagery.
- Making generated mockup text authoritative where it conflicts with existing domain terminology or contracts.

## 5. Shared Editor Frame

### 5.1 Width and alignment

The editor defines one content-frame token and uses it for the inner wizard progress list and `main.editor-main`. At a 1440px desktop viewport, both use the same maximum width and the same responsive horizontal padding. The generation summary, error summary, and active panel fill that frame.

The implementation must not rely on independently repeated numeric widths. A single CSS custom property owns the maximum width so a later width change cannot recreate the mismatch.

### 5.2 Top stepper

The existing five connected numbered steps remain at the top. The active step uses a filled primary circle and primary label. Completed steps use a check mark or completed treatment; reachable prior steps remain clickable. Future steps stay neutral and disabled.

The stepper remains horizontally scrollable at narrow widths and moves the current step into view. It does not become a vertical sidebar.

### 5.3 Generation summary

Steps 2-5 show one four-cell horizontal summary directly under the stepper. Every cell has a 24-28px Bootstrap Icon inside an approximately 40px soft semantic circle, a short label, and a prominent value.

The four concepts are:

1. OpenAPI file and version;
2. selected Endpoint count;
3. generated Tool count;
4. active project/profile identifier.

The summary replaces the small decorative icon treatment. It is context, not navigation, and contains no interactive controls. At tablet widths it becomes two columns; at 400px it becomes a compact single-column or two-column list without horizontal overflow.

### 5.4 Navigation actions

Every step action area participates in normal document flow. No `.wizard-nav` uses fixed positioning, pill-shaped floating containers, viewport-bottom placement, backdrop blur, or body clearance padding.

Previous actions are secondary and left-aligned. The one action that advances the user's current task is primary and right-aligned. Step 1 enables `다음: Endpoint 선택` after successful analysis. Step 4 advances by starting generation. Step 5 has no next-step action.

## 6. Step 1 — OpenAPI File

The upload drop zone is the primary object. It supports idle, drag-over, analyzing, completed, and error states. During analysis, the file controls are disabled and the live region announces progress.

On success:

- the analyzed file name, size, and OpenAPI version are visible;
- total, selectable, warning, and unsupported counts are visible;
- a success message confirms analysis completion;
- the live region announces that analysis is complete;
- `다음: Endpoint 선택` becomes enabled without moving the user unexpectedly.

The wizard advances only when the user selects the enabled next action. A retained hosted specification restores its prior reachable step. Replacing or removing the file disables the next action until a new analysis succeeds.

Failure remains on Step 1, keeps the file replace/remove actions available, and moves focus to the existing error summary.

## 7. Step 2 — Endpoint Selection

Step 2 is one grouped flat table. The toolbar contains search, support-status filter, a secondary select-all checkbox, and live counts. Selection never requires a modal, header disclosure, or row settings action.

Each operation row shows:

- a directly visible checkbox;
- HTTP method;
- endpoint path;
- short description;
- textual support status.

Unsupported operations remain visible with a disabled checkbox and an inline reason. Resource groups remain presentation-only and preserve source order and `sourceIndex` identity.

The in-flow footer contains `이전: OpenAPI 파일` and `다음: 생성 설정`. The next action is enabled when at least one supported operation is enabled.

Filtering and grouping remain `O(n)` time and `O(n)` presentation space for `n` operations. Select-all continues to target all selectable operations, not only filtered rows, preserving the current contract.

## 8. Step 3 — Generation Settings

### 8.1 Project settings

Project fields render as one flat responsive row or grid. Spacing, field labels, and a single section divider create grouping; the fields are not wrapped in an additional tinted card.

Required fields remain Group ID, Artifact ID, package name, provider name, domain name, and generation profile. Existing validation and compatibility help behavior remain authoritative.

### 8.2 Tool table and editor

The lower workspace has two regions separated by a divider:

- a Tool table using approximately 58% of desktop width;
- one persistent Tool editor using approximately 42%.

Every Tool row has a directly visible generation checkbox. Selecting a row changes the editor target; expanding a card is not required. The table displays method, endpoint, operation ID, and Tool name. The selected row has a quiet primary background treatment.

The editor contains basic Tool name, description, and output mode fields followed by flat advanced-policy sections:

- retry;
- pagination;
- parameter sources;
- response normalization.

Each policy section has a clear enable control. Disabled policies hide their dependent fields while preserving entered state. At most one advanced-policy section is expanded at a time. Dividers and spacing, rather than nested bordered cards, separate the policy groups.

At narrow widths the Tool table and editor stack. The selected Tool editor follows the table and retains state while selection changes.

## 9. Step 4 — Preview and Generation

Step 4 is a guided three-part task:

1. select a representative Tool;
2. review test input;
3. validate settings.

The representative Tool is selected automatically when possible. The test input region first shows a readable parameter summary. Raw JSON editing is optional behind `테스트 입력 수정`; it is not the dominant empty-state control.

`설정 검증하기` runs the existing preview request. While running, the action is disabled and a live region announces progress. On success, the result region shows:

- a clear validation-success heading;
- validation checks derived only from existing state: analysis availability, enabled Tool count, successful preview Tool count, and representative-operation inclusion;
- representative Tool name, endpoint, description, inputs, and output type;
- schema and full policy details behind lightweight disclosures.

After validation succeeds, `프로젝트 생성` becomes the single primary action. Starting generation creates the existing job and moves directly to Step 5. There is no separate `다음: 생성 진행` button.

Validation failure keeps the user on Step 4, preserves input, disables project generation, and focuses the existing error summary.

## 10. Step 5 — Generation Progress

Step 5 leads with job state, percentage, and the current stage. The five visual pipeline groups map the existing server stages without introducing backend states:

1. `준비`: `ANALYZE`;
2. `프로젝트 생성`: `GENERATE`;
3. `컴파일 및 기동`: `COMPILE`, `APPLICATION_CONTEXT`;
4. `MCP 검증`: `MCP_INITIALIZE`, `MCP_TOOLS_LIST`, `MCP_TOOL_CALL`;
5. `패키징`: `PACKAGE`.

Completed, active, pending, failed, and skipped states use distinct icons and text; color is not the only signal. The detailed list retains all eight raw server stages, so grouping never hides failure identity.

An always-visible detail list shows each stage and status. `상세 로그 보기` is hidden when no existing error/detail text is present; otherwise it reveals only that server-owned text and never implies a new log-stream API.

During execution:

- the current work description updates from SSE or polling snapshots;
- available hosted cancellation remains visible as `작업 취소`;
- local-mode deletion keeps its existing semantics and copy;
- artifact rows are visibly unavailable and explain that generation must finish first.

On successful completion, the progress headline becomes a success state and artifact actions become prominent. On failure, the failed stage and server-owned error are visible and `설정으로 돌아가기` takes the user to Step 4 with prior configuration intact.

## 11. State and Data Flow

- `upload.js` owns upload state and analysis completion.
- `wizard.js` owns reachability, focus, URL hash, and step transitions.
- `operations.js` owns Step 2 filtering, grouping, and selection.
- `editor.js` owns the selected Tool and operation policy field bindings.
- `app.js` owns preview, generation, generation summary, and artifact actions.
- `progress.js` maps job snapshots to progress presentation.

Existing server responses remain authoritative. Rendering continues to use DOM construction, `replaceChildren`, and `textContent`; no `innerHTML` is introduced.

## 12. Accessibility and Responsive Behavior

- The active step retains `aria-current="step"` and unreachable steps remain disabled.
- Programmatic step changes move focus to the active heading and announce the new step once.
- Upload, preview, generation, and progress status use existing live regions.
- Direct row checkboxes have endpoint-specific accessible names and at least a 44px hit area.
- Every interactive control retains a visible `:focus-visible` treatment.
- Disabled policy fields and artifact actions expose both visual and semantic disabled states.
- At 400 × 900, no horizontal page overflow is permitted.
- Step 2 rows become compact stacked rows; Step 3 becomes one column; Step 4 stacks task and result regions; Step 5 stages become a vertical timeline.
- Reduced-motion mode removes step scrolling animation and progress-spinner animation.

## 13. Implementation Boundaries

Expected changes stay within:

- `templates/editor.html` and the existing wizard fragment;
- `static/editor.css` and shared frame tokens where necessary;
- `static/upload.js`, `static/wizard.js`, `static/operations.js`, `static/editor.js`, `static/app.js`, and `static/progress.js` only where behavior changes require them;
- existing Web static/MVC contract tests and browser-flow verification.

No dependency additions are required. Existing Bootstrap and Bootstrap Icons packages remain sufficient.

## 14. Verification

- Add a failing contract test for the shared frame token and removal of fixed `.wizard-nav` positioning.
- Add a failing behavior test for enabling Step 1 navigation only after successful state update.
- Add a failing contract/behavior test for direct Step 3 Tool checkboxes and one persistent editor target.
- Add a failing flow test for Step 4 preview success enabling generation and generation moving directly to Step 5.
- Preserve focused tests for operation identity, unsupported reasons, preview invalidation, job progress, and artifact actions.
- Run the complete Web test suite and generator compatibility smoke.
- Verify the local browser flow with `swagger-3.0.yml` and `swagger-3.1.yml` at 1440 × 1024 and 400 × 900.
- Compare each implemented step against its approved visual reference at the same viewport.
- Check keyboard-only operation, focus order, live announcements, reduced motion, console errors, and horizontal overflow.

## 15. Risks and Tradeoffs

- Constraint: generated mockups illustrate hierarchy and composition; existing domain copy and API data remain authoritative.
- Risk: Step 3's two-region workspace can become cramped below tablet width, so it must stack before controls lose readable width.
- Tradeoff: manual Step 1 navigation adds one click but keeps analysis confirmation visible and prevents unexpected context changes.
- Risk: Step 5's visual stage grouping may not map one-to-one to every server stage; presentation grouping must not lose failure identity or raw stage detail.
- Exception: hosted cancellation and local deletion have different semantics and must retain mode-specific behavior.
- Tradeoff: removing fixed navigation means long Step 2 content may require scrolling to its footer, but it eliminates content overlap and the detached floating-control experience rejected in review.
