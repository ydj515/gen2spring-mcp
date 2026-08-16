# Stepwise Editor Design QA

Date: 2026-08-16

## Scope

- Compared the implemented four-step wizard with the approved design in `docs/superpowers/specs/2026-08-16-stepwise-editor-redesign-design.md`.
- Exercised the editor against a dedicated instance started on a fixed port, because the Thymeleaf template cache in an already-running instance keeps serving the previous markup.
- Walked all four steps with synthetic OpenAPI 3.0 documents of 1, 4, and 26 operations, the last mixing selectable and unsupported endpoints.
- Verified the gate rules: analysis completes before step 2, at least one endpoint selected before step 3, six project fields filled before step 4.
- Verified a blocked step names the offending fields. Clearing `artifact-id` and `domain-name` produced `Artifact ID, Domain 항목을 채워 주세요.` and marked exactly those two inputs `aria-invalid`.
- Verified the clamp: breaking the step 3 gate while sitting on step 4 returns the user to step 3 and disables chip 4. Restoring the fields reopens the gate without dragging the user forward.
- Verified values survive navigation. A value typed on step 3 and a value typed on step 4 both persisted across a step 4 to step 3 to step 4 round trip.
- Verified the browser back button moves one step back and stays on `/editor`.
- Verified focus moves to the newly activated panel heading on every transition, and that the initial page load does not steal focus.
- Ran one real generation to completion and observed the progress bar advance through all eight stages to `VALIDATED` with three downloads offered.
- Verified the failure presentation by rendering a snapshot whose `COMPILE` stage failed.
- Verified 400 px, 720 px, and 1280 px viewports on every step.
- Verified the paired root documents are covered server-side rather than re-checking them by hand: `OpenApiVersionPairAcceptanceTest` and `WebMvcContractTest` assert the 3.0 and 3.1 analysis equivalence, and both run in `:apps:web:test`.

## Measurements

Page height with 26 operations, measured by isolating each panel and then revealing all four at once:

| View | Height |
| --- | --- |
| Single page equivalent, all four panels visible | 9545 px |
| Step 1, OpenAPI 파일 | 900 px |
| Step 2, Endpoint 선택 | 5331 px |
| Step 3, 생성 설정 | 1780 px |
| Step 4, 생성 및 결과 | 948 px |

The tallest single step is 44 percent shorter than the single page, and every step other than the endpoint list is more than 80 percent shorter. Step 2 remains long because a 26 item endpoint list is inherently long; splitting steps does not address list length.

Contrast measured in the browser against `--surface`, matching the design's section 8.2 table within 0.05:

| Token | Measured | Use |
| --- | --- | --- |
| `--text` `#0f172a` | 17.85:1 | Body text |
| `--muted` `#475569` | 7.58:1 | Secondary text |
| `--soft` `#64748b` | 4.76:1 | Tertiary text |
| `--primary` `#4f46e5` | 6.29:1 | Links, active step |
| `--success-text` `#047857` | 5.48:1 | Success glyphs |
| `--warning-text` `#92400e` | 7.09:1 | Warning glyphs |
| `--danger-text` `#b91c1c` | 6.47:1 | Danger glyphs |
| White on `--primary` | 6.29:1 | Primary buttons |

The fill-only tokens measure `--success` 2.54:1, `--warning` 2.15:1, and `--danger` 3.76:1. A sweep of every leaf element under `main` and `nav` found no glyph rendering in any of them.

## Visual observations

- The stepper communicates position rather than merely order. Unreached steps are visibly disabled, so the flow reads as a sequence with a current location instead of a list of anchors.
- Keeping all four panels in the DOM and toggling only `hidden` makes backward navigation lossless without any serialization code. Form controls hold their own values.
- The progress bar carries the common case in one line, and the eight stage rows stay behind `상세 보기` until a failure opens them.
- The summary aside is suppressed on step 1, where it would have nothing to report, and the `main` grid collapses to one column there rather than leaving an empty gutter.
- At 400 px no step overflows horizontally, the chip row scrolls within itself, the navigation buttons stack, and the summary drops below the active panel.

## Defects found and fixed during QA

- Gate 1 originally read the persisted `specificationId`. Local mode registers no analysis route, so after a reload `sessionStorage` still named a specification whose operations were never re-fetched, and the wizard placed the user on an empty step 2 with no way forward. Gate 1 now reads the in-memory `state.analysis`, so a local reload returns to step 1, matching the idle upload surface the user actually sees.
- The progress ratio originally counted every `SUCCESS` and `SKIPPED` stage. A failure marks all later stages `SKIPPED`, so a build that died at `COMPILE` rendered as `7 / 8` and 88 percent. On failure the ratio now counts only the stages before the failed one, showing `2 / 8` and 25 percent, and the fill switches to the danger token.
- `goToStep` announced the new step and moved focus, then assigned the location hash, which re-entered through `hashchange` and repeated both. The handler now compares against the current step before re-entering.
- The initial `goToStep` on page load called `focus()`, stealing focus from the document on every load. The load path now passes `{focus: false, announce: false}`.

## Not covered

- Hosted mode was not exercised. `dashboard.html` and `job-detail.html` inherit the new tokens without markup changes, but the hosted resume path, which does re-fetch the analysis, was verified only by reading the code.
- No automated browser test covers the wizard. This repository has no JavaScript test runner and no headless browser; `StaticAssetContractTest` pins structure and the accessibility decisions as source text, and everything above was checked by hand.

final result: passed
