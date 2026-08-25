# Upload and Policy UX Refinement Design

- Status: approved and implemented
- Date: 2026-08-25

## Goal

Remove duplicated upload UI after analysis, make Step 3 policy configuration progressively disclosed and self-explanatory, and remove redundant numeric prefixes from the five panel headings.

## Approved behavior

- Step 1 shows the large dropzone only before a file is successfully analyzed. The completed state replaces it with the compact file card and keeps `파일 교체` and `제거` available.
- The analysis-complete live region includes a Bootstrap Icons success mark before the completion text.
- Step headings keep the existing `STEP 1` through `STEP 5` kickers and wizard stepper, but the five panel `h2` labels omit `1.` through `5.`.
- Retry and Pagination use explicit enable controls. Their dependent fields remain hidden and disabled until enabled, while previously entered state is preserved.
- Parameter sources and Response normalization remain always available because their current model has no master enable flag. Their summaries expose parameter/source counts and default/custom normalization state.
- Each policy header has an independent information control. It opens concise Korean help that explains when the policy is useful and what its main fields mean. Help closes with its control, outside click, or Escape and does not toggle the accordion.
- At most one policy configuration panel is open at a time. Opening and closing a panel never changes policy values.

## Boundaries

- Preserve backend payloads, editor state ownership, validation, upload APIs, and hosted-mode restoration.
- Use the existing Bootstrap and Bootstrap Icons dependencies only.
- Do not add routes, dependencies, inline SVG, `innerHTML`, or backend states.
- Preserve keyboard operation, live announcements, visible focus, and the existing narrow-screen stack.

## Verification

- Focused MVC/static contracts cover rendered headings, upload-state hooks, policy controls, help semantics, and policy summaries.
- Browser QA covers completed upload replacement, analysis success icon, Retry/Pagination off/on disclosure, independent help popovers, accordion behavior, and narrow-screen layout.
