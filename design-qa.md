# UI redesign design QA

## Evidence

- Source visual truth: the approved full-width mock recorded by `docs/superpowers/specs/2026-08-24-ui-ux-redesign-design.md`; the original task attachment is intentionally not committed.
- Desktop implementation capture: `gen2spring-ui-step2-1440-final-v2.png` (current-task QA artifact, not committed)
- Tablet implementation capture: `gen2spring-ui-step2-768.png` (current-task QA artifact, not committed)
- Mobile implementation capture: `gen2spring-ui-step2-400-final.png` (current-task QA artifact, not committed)
- Step 2 interaction refinement capture: `gen2spring-ui-step2-settings-final.png` (current-task QA artifact, not committed)
- Step 5 always-visible detail capture: `gen2spring-ui-step5-details-final.png` (current-task QA artifact, not committed)
- Source pixels: `1487 x 1058`
- Desktop implementation pixels: `1404 x 1024`, captured from a `1440 x 1024` in-app browser viewport
- Tablet viewport: `768 x 900`; mobile viewport and pixels: `400 x 900`
- Device pixel ratio: `1`
- Normalization: the source and desktop implementation were compared together as full-page desktop captures. The source uses fixture rows while the implementation uses the repository's real 38-endpoint OpenAPI fixture, so fidelity was judged by hierarchy, density, tokens, and interaction structure rather than identical row positions.
- State: wizard step 2 after analysis, with supported and unsupported endpoint states populated.

## Full-view comparison

The approved no-sidebar direction is preserved: a slim product header, connected five-step progress, horizontal summary, grouped endpoint table, and persistent previous/next dock. Typography uses a restrained system stack with a clear heading/body hierarchy. Spacing, one-pixel borders, low-elevation shadows, neutral surfaces, primary blue, and semantic success/error colors follow the supplied product references without decorative gradients or unrelated imagery.

The implementation intentionally omits the mock's work-list and account controls because those routes and account interactions are not part of the current local editor. The real fixture adds two summary facts and longer endpoint descriptions, but keeps the same scan order and density.

## Focused-region comparison

Focused review covered the connected stepper, summary strip, endpoint search/filter controls, grouped rows, status badges, unsupported-reason panel, and fixed action dock. This was necessary because those controls and dense table states are the primary fidelity surfaces. Bootstrap Icons supply the visible icons; no handmade SVG, CSS drawing, emoji, or placeholder imagery is used.

## Required fidelity surfaces

- Fonts and typography: heading, body, label, code-path, and badge weights remain distinct at desktop and 400 px. Long paths wrap without horizontal overflow.
- Spacing and layout rhythm: desktop, 768 px, and 400 px captures keep section hierarchy and persistent actions visible. Mobile summary changes to two columns and form controls stack vertically.
- Colors and visual tokens: primary, muted, surface, border, success, warning, and danger roles come from shared tokens. The previously mismatched success treatment is replaced by a quieter semantic green.
- Image quality and asset fidelity: the flow does not require raster imagery. All visible interface icons use the packaged Bootstrap Icons font and render locally.
- Copy and content: step labels, calls to action, support states, and unsupported reasons are explicit. Dynamic OpenAPI descriptions remain source-derived.
- Accessibility and interaction: semantic labels, disabled states, live regions, reduced-motion handling, and focus behavior are present. Programmatically focused headings no longer retain a decorative outline; interactive controls retain the shared focus treatment.

## Primary interactions tested

- Upload and analyze `swagger-3.1.yml`: OpenAPI `3.1.2`, 38 total, 34 selected, 4 unsupported.
- Filter unsupported endpoints and inspect the reason for each disabled row.
- Move through steps 2, 3, and 4 with the persistent action dock.
- Run representative-call preview for all 34 selected tools.
- Generate the project and observe all 8 stages reach 100%, including three downloadable artifacts.
- Confirm the Step 5 stage list is a static region with all 8 stages visible and no disclosure control.
- Confirm endpoint settings use a 44 px transparent sliders control and only the icon color changes on hover.
- Confirm inactive wizard-step hover changes only text color while its background and number mark remain unchanged.
- Upload and analyze `swagger-3.0.yml`: OpenAPI `3.0.4`, 38 total, 34 selected, 4 unsupported.
- Check 1440 px, 768 px, and 400 px layouts for horizontal overflow and visible persistent actions.
- Check browser warning and error logs: none.
- Verify hosted dashboard and job-detail structure, empty states, responsive rules, and rendered MVC contracts through automated tests. Interactive hosted-browser validation remains an environment test gap because the repository has no private `deploy/hosted/.env`, OIDC client, TLS material, or hosted secrets.

## Comparison history

1. First comparison found two P2 issues: Bootstrap Icons rendered as missing glyph boxes because cache-busting font URLs were rejected by the local-only query-string policy, and `position: sticky` did not keep the action dock visible at the top of long steps. The implementation now serves a query-free local font URL and uses a centered fixed dock.
2. Second comparison found two P2 polish issues: resource groups derived from `admin`, `auth`, and `customers` remained English, and programmatic heading focus showed a large outline. The implementation now localizes the groups and suppresses the non-interactive heading outline.
3. The interaction-detail comparison found that Step 5 still used a disclosure control, endpoint settings looked like a secondary navigation button, and wizard hover inherited a filled button background. The implementation now keeps progress detail visible, uses a transparent sliders control, and limits wizard hover to text color.
4. The final desktop comparison and responsive captures show no remaining actionable P0, P1, or P2 findings.

## Findings

- No actionable P0, P1, or P2 findings remain.
- P3: hosted mode still needs a browser pass in a fully configured private deployment environment; automated MVC and responsive contracts cover it in this repository.

## Implementation checklist

- [x] Shared design tokens and no-sidebar application shell
- [x] Connected wizard progress and horizontal summary
- [x] Grouped endpoint table with explicit unsupported reasons
- [x] Persistent previous/next actions across long steps
- [x] Editor steps 1 through 5, preview, progress, and result states
- [x] Always-visible generation stages and lightweight endpoint settings control
- [x] Hosted dashboard and job-detail redesign contracts
- [x] Desktop, tablet, and 400 px responsive checks
- [x] OpenAPI 3.0 and 3.1 regression flows

final result: passed
