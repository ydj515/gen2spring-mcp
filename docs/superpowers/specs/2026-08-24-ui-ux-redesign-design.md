# Gen2Spring MCP UI/UX Redesign Design

- Status: approved
- Date: 2026-08-24
- Supersedes the visual and layout sections of:
  - `docs/superpowers/specs/2026-08-16-stepwise-editor-redesign-design.md`
- Preserves the workflow, validation, security, API, and persistence contracts from the earlier editor and hosted-platform specifications.

## 1. Purpose

Redesign every Thymeleaf user screen around one compact, responsive application shell. The redesign must make long wizard steps usable without scrolling to find the next action, make endpoint support decisions scan like a data table, and bring the editor, hosted dashboard, and hosted job detail into one visual system.

The approved visual direction is the revised full-width endpoint workspace: no left sidebar, a slim top application bar, a thin connected five-step progress indicator, a horizontal analysis summary, a grouped endpoint table, and a persistent bottom action dock.

## 2. Problems

- Step 2 and Step 3 place navigation controls after long content, so users must scroll to the bottom before advancing.
- The current endpoint cards repeat borders, labels, and actions, making 38 operations expensive to scan.
- The summary aside consumes width and wraps long profile identifiers.
- The mobile stepper clips labels and the long steps push the primary action far below the viewport.
- `dashboard.html` and `job-detail.html` are functional but do not share the editor's hierarchy, language, density, or status presentation.
- The existing CSS tokens are incomplete and the visual responsibilities remain concentrated in one stylesheet.

## 3. Goals

- Apply one design system to `editor.html`, `dashboard.html`, and `job-detail.html`.
- Keep the current five-step client-side wizard and all existing gates.
- Keep previous and next actions visible while scrolling long steps.
- Present Step 2 as a grouped, dense endpoint table.
- Keep unsupported endpoints visible, disabled, and explained inline.
- Make all editor and hosted screens usable without horizontal overflow at 400 CSS pixels.
- Keep the packaged application independent from third-party CDNs.
- Meet WCAG AA contrast, visible focus, keyboard navigation, and live-region requirements.
- Preserve current API, database, authentication, job-state, generation, and artifact contracts.

## 4. Non-goals

- Adding new API endpoints, database columns, dashboard metrics, or authentication flows.
- Replacing Thymeleaf or the current ECMAScript modules with a client framework.
- Introducing a global left navigation menu.
- Adding a dark theme, remote web fonts, charts, or decorative imagery.
- Changing the OpenAPI support decision, generation configuration, or job lifecycle.

## 5. Application Shell

### 5.1 Shared structure

Every screen uses:

1. a slim white top application bar;
2. a page header with a clear title and one sentence of context;
3. a fluid content canvas capped for readable line length but wide enough for tables;
4. screen-specific content;
5. a persistent action dock only where the screen has sequential or destructive actions.

There is no left sidebar. In local mode the top bar shows only the product identity. In hosted mode it additionally shows `작업 목록`, `새 프로젝트`, and the existing account/sign-out affordance. Navigation must reuse existing routes only.

### 5.2 Reusable Thymeleaf fragments

`templates/fragments/ui.html` owns the shared head assets, top application bar, and wizard progress markup. Page templates keep screen-specific content so fragments do not become a general-purpose component framework.

## 6. Dependency and Asset Strategy

- Bootstrap `5.3.8` is packaged as `org.webjars:bootstrap`.
- Bootstrap Icons `1.13.1` is packaged as `org.webjars.npm:bootstrap-icons`.
- No CDN, remote font, or runtime asset fetch is permitted.
- Bootstrap CSS and Bootstrap Icons load before application CSS.
- Local and hosted CSP policies allow the packaged icon font with `font-src 'self'` while retaining the existing deny-by-default policy.
- Bootstrap JavaScript is not loaded. Existing modules and native elements such as `<details>` own interactions.
- Bootstrap provides reset, grid, forms, buttons, tables, utilities, and the icon font. Project CSS owns tokens, density, layout, and visual identity.

The versions above match the official Bootstrap release and the Maven Central WebJar artifacts available at the time of this design.

## 7. Design Tokens

Application tokens bridge to Bootstrap variables and remain the only source of application colors, spacing, radii, and shadows.

| Token | Value | Role |
| --- | --- | --- |
| `--app-bg` | `#f6f8fc` | Page canvas |
| `--app-surface` | `#ffffff` | Main surface |
| `--app-surface-subtle` | `#f8fafd` | Toolbar and grouped-row background |
| `--app-text` | `#17233a` | Primary text |
| `--app-muted` | `#617089` | Secondary text |
| `--app-border` | `#d9e2ef` | Default divider and border |
| `--app-primary` | `#3568f4` | Active step and primary action |
| `--app-primary-hover` | `#2554d9` | Primary hover and active state |
| `--app-primary-soft` | `#eef3ff` | Selected and focus-adjacent surface |
| `--app-success` | `#168a69` | Supported state text/icon |
| `--app-success-soft` | `#e7f7f1` | Supported state background |
| `--app-warning` | `#b7791f` | Warning state text/icon |
| `--app-warning-soft` | `#fff6e5` | Warning state background |
| `--app-danger` | `#d63b44` | Unsupported/error text/icon |
| `--app-danger-soft` | `#fff0f1` | Unsupported/error background |

Spacing uses `4, 8, 12, 16, 24, 32, 48px`. Controls use an `8px` radius, data surfaces `12px`, and large containers `16px`. Shadows are restricted to the top bar and persistent action dock. Typography uses Bootstrap's system font stack with a 14-16px body baseline; no remote text font is introduced. The packaged Bootstrap Icons font is declared locally with a query-free URL because local mode rejects query strings by policy.

## 8. Editor

### 8.1 Wizard progress

The progress indicator is an ordered list of five small numbered circles and labels connected by thin neutral lines. Step 2 and the active label use the primary color; future steps stay neutral. It preserves `aria-current="step"`, disabled unreachable steps, hash navigation, focus movement, and the hidden live announcement.

Desktop presents all five labels in one row. Mobile uses horizontal overflow with the current step scrolled into view; it does not truncate or wrap the complete workflow into a narrow stack.

### 8.2 Analysis summary

The vertical `generation-summary` aside becomes a horizontal summary strip visible during Steps 2-5. It shows OpenAPI version, selected Tool count, excluded endpoint count, warning count, active profile, and representative validation. The same existing element identifiers remain so rendering code and state contracts do not change.

On mobile the strip becomes a compact two-column grid. Long profile identifiers wrap without expanding the viewport.

### 8.3 Persistent action dock

Each visible wizard panel retains its existing back/next identifiers, but its `.wizard-nav` renders as a fixed viewport-bottom action dock. The active panel supplies the controls, blocking hint, and button state. The editor reserves bottom padding equal to the dock height so the final content row is never obscured.

Step 4 keeps `설정 검증 및 미리보기` as a secondary in-content action and `프로젝트 생성` as the primary generation action. Step 5 has no next action and keeps only contextual back/delete/download controls.

### 8.4 Step 1: OpenAPI file

The drop zone remains the primary object. Idle, drag-over, analyzing, loaded, and error states use the shared semantic tokens. The analyzed file and analysis summary appear immediately below it. The persistent dock explains why `다음: Endpoint 선택` is unavailable.

### 8.5 Step 2: endpoint selection

Step 2 uses one grouped data surface:

- a sticky toolbar containing search, support filter, select-all, and live counts;
- group rows derived deterministically from the first resource segment of the path;
- dense operation rows containing checkbox, method, path, short summary, support badge, and details chevron;
- an inline issue row beneath operations with warnings or unsupported reasons.

Grouping is presentation-only. `/api/users` maps to `사용자`, `/api/orders` to `주문`, `/api/payments` to `결제`, `/schema-contracts` to `스키마 계약`, and unknown resources use a sanitized title derived from the path. Operation source order and `sourceIndex` remain authoritative.

Unsupported operations remain visible, cannot be enabled, and expose every server-owned issue message. Rendering continues to use DOM construction and `textContent`; no `innerHTML` is introduced.

Search and status filters run before grouping. A group is omitted when none of its operations match. Select-all continues to affect every selectable operation, not only the currently filtered operations, matching the current contract.

The grouping pass is `O(n)` time and `O(n)` presentation space for `n` operations.

### 8.6 Step 3: generation settings

Project fields form a responsive two-column grid. The profile selector and its help remain adjacent. Selected Tools render as a compact grouped expandable list; expanding a row moves the existing `operation-editor` into that row without losing entered state.

### 8.7 Step 4: preview and generation

Representative operation and arguments form the input region. Preview status and generated output form the result region. On wide screens they share the available space; on narrow screens they stack. Validation failures use the shared error presentation and move focus to the error summary.

### 8.8 Step 5: progress and artifacts

The current state and percentage lead the page, followed by the pipeline and progressive-disclosure detail. Completed artifacts become a clear download list. Failure opens the detail automatically. Existing SSE, polling fallback, status translation, deletion, and artifact APIs remain unchanged.

## 9. Hosted Dashboard

The dashboard shares the top bar and page header. It keeps its current two resources and URL-import contract:

- URL import is the primary action panel with a clear pending/success/error region.
- Specifications render as a responsive table/list with label, source type, identifier, and open-in-editor action.
- Jobs render as a responsive table/list with kind, status, created time, and detail link.
- Empty states occupy the corresponding table surface rather than appearing as loose text.
- Counts may be derived from the already-loaded lists, but no new metric or backend query is introduced.

All hosted copy is converted to Korean except identifiers and protocol terms.

## 10. Hosted Job Detail

The job detail shares the top bar and adds a breadcrumb back to the dashboard. It presents:

1. job status, kind, and attempt summary;
2. chronological event timeline;
3. artifact download list;
4. cancellation only when the existing form is relevant.

The page does not add state transitions or API calls. Empty timelines and artifact lists use the shared empty state.

## 11. Responsive Behavior

- Desktop target: `1440 x 1024`.
- Mobile verification target: `400 x 900`.
- At tablet widths the summary strip wraps and form grids reduce to one column where needed.
- At 400px the endpoint table becomes a compact stacked row while preserving method, path, summary, status, checkbox, chevron, and issue reason.
- Sticky toolbar and action dock must not overlap one another or hide focused controls.
- `document.documentElement.scrollWidth` must not exceed `window.innerWidth` at 400px.

## 12. Accessibility and States

- Preserve semantic headings, ordered wizard navigation, field labels, fieldsets, and live regions.
- Every interactive target has a minimum 44px hit area without inflating data-row height unnecessarily; the row may provide the hit area around a smaller icon.
- Every control has a visible `:focus-visible` treatment.
- Color is never the only support-state signal; every state has text and, where useful, a Bootstrap Icon.
- Reduced-motion mode removes non-essential transitions.
- Loading, empty, warning, failure, and success states use one semantic vocabulary across local and hosted screens.
- The persistent action dock must not capture focus when disabled and must expose the current blocking reason.

## 13. Module Boundaries

| File or area | Responsibility |
| --- | --- |
| `templates/fragments/ui.html` | Shared head assets, top bar, wizard progress |
| `static/design-tokens.css` | Project and Bootstrap variable bridge |
| `static/app-shell.css` | Page shell, top bar, shared surfaces, status patterns, action dock |
| `static/editor.css` | Five-step editor, grouped endpoint surface, forms, preview, progress |
| `static/hosted.css` | Dashboard and job detail |
| `static/styles.css` | Ordered local stylesheet entrypoint |
| `static/operations.js` | Filter, grouping, selection, endpoint row rendering |
| Existing JS modules | Existing upload, wizard, preview, generation, SSE, polling, and state behavior |

## 14. Verification

- Focused contract tests: `StaticAssetContractTest`, `HostedModeContractTest`, and editor rendering assertions in `WebMvcContractTest`.
- Complete Web tests: `mise run ui:test`.
- Generator compatibility smoke: `mise run generator:test`.
- Local browser flow with both `swagger-3.0.yml` and `swagger-3.1.yml` through Steps 1-5.
- Hosted dashboard and job detail browser checks against the existing hosted runtime and fixtures.
- Visual comparison against the approved revised full-width mock at `1440 x 1024` and responsive verification at `400 x 900`.
- Console error check and keyboard-only pass.
- `design-qa.md` records the source visual, implementation captures, comparison iterations, and ends with `final result: passed` before handoff.

## 15. Risks and Tradeoffs

- Constraint: path-derived groups are a UI heuristic because the analysis response carries no OpenAPI tags. Unknown resources must degrade to a stable path-derived label.
- Risk: a sticky toolbar and sticky action dock can reduce usable height. The implementation must reserve space and validate short viewports.
- Risk: Bootstrap defaults can leak into existing controls. Application tokens and scoped styles must load after Bootstrap.
- Exception: local mode has no dashboard, so hosted-only navigation remains absent there.
- Tradeoff: keeping interactions in existing modules avoids a frontend runtime but requires careful DOM rendering tests and browser verification.
