# Stepwise Editor and Slate Indigo Design System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the single-page editor into a four-step wizard, replace the eight-item generation progress list with a progress bar plus collapsed detail, and restate the visual layer on Slate and Indigo design tokens.

**Architecture:** One Thymeleaf document holds four panels; only the active panel lacks `hidden`, so every entered value survives backward and forward navigation without serialization. A new `wizard.js` owns step state, the gate predicate, hash synchronization, and focus management; a new `progress.js` maps the eight pipeline stage constants to Korean labels and renders the bar. `state.js` gains `currentStep`. No server code, no job contract, and no progress transport changes.

**Tech Stack:** Thymeleaf, ECMAScript modules (no framework, no bundler), CSS custom properties, Spring Boot MVC, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-16-stepwise-editor-redesign-design.md`

## Global Constraints

- Implement the approved design in `docs/superpowers/specs/2026-08-16-stepwise-editor-redesign-design.md`. Do not broaden it.
- Progress transport stays polling. Do not add `SseEmitter`, an `EventSource`, or any new job endpoint.
- Do not change `GenerationProgress.STAGES`, `GenerationJobManager`, `GenerationPipeline`, the hosted job contract, the analysis contract, the preview contract, or the generation configuration payload.
- Keep the current stack. No frontend framework, no bundler, no browser-side OpenAPI parsing.
- No external network assets. `WebSecurityConfiguration.CONTENT_SECURITY_POLICY` is `default-src 'none'` with no `font-src`; the font stack stays `Inter, ui-sans-serif, system-ui, sans-serif` with no `@font-face` and no stylesheet link.
- Every color in `styles.css` resolves through a token. No literal hex outside the `:root` block. This is what makes a later dark theme one added block rather than an audit.
- Semantic fill tokens (`--success`, `--warning`, `--danger`) must never be assigned to a text color. Use `--success-text`, `--warning-text`, `--danger-text` for glyphs.
- Every interactive target keeps `min-height: 44px`. Every text color keeps WCAG AA contrast on its own background.
- The 400 px viewport must show no horizontal overflow.
- UI copy stays Korean. Identifiers, CSS class names, `data-*` values, logs, and commit messages stay English.
- No `innerHTML` anywhere in `static/*.js`. Build DOM with `createElement` and `textContent`, matching the existing modules and the standing negative assertions in `StaticAssetContractTest`.
- Complete each task with focused RED/GREEN evidence and one scoped Conventional Commit. Do not push unless the user explicitly requests it.

## Testing Reality

This repository has no JavaScript test runner and no headless browser. The established frontend test is `StaticAssetContractTest`, which reads the template and script sources as text and asserts on their content. That is the RED/GREEN cycle used below. It pins structure and the accessibility decisions, but it cannot prove behavior — so every task also carries explicit manual browser verification against the running instance, and Task 4 records the result in `design-qa.md`, matching the convention set by the previous editor change.

Test command used throughout:

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

## File Structure

| File | Responsibility | Task |
| --- | --- | --- |
| `apps/web/src/main/resources/static/styles.css` | Token set and all visual rules | 1, 2, 3, 4 |
| `apps/web/src/main/resources/static/state.js` | Application state including `currentStep` | 2 |
| `apps/web/src/main/resources/static/wizard.js` | Step transitions, gate predicate, stepper, hash, focus | 2 |
| `apps/web/src/main/resources/static/progress.js` | Stage labels and progress rendering | 3 |
| `apps/web/src/main/resources/templates/editor.html` | Wizard shell, four panels, progress region | 2, 3 |
| `apps/web/src/main/resources/static/app.js` | Wiring, clamp on invalidation, progress delegation | 2, 3 |
| `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java` | Markup and stylesheet contracts | 1, 2, 3 |
| `design-qa.md` | Manual visual and accessibility QA record | 4 |

`upload.js`, `operations.js`, and `editor.js` keep their current responsibilities. `operations.js` is untouched; `editor.js` is untouched. `dashboard.html` and `job-detail.html` inherit the new tokens without markup changes.

---

## Task 1: Slate and Indigo token layer

Recolor the existing single-page editor before restructuring it. Doing tokens first means Task 2 reviews as a pure structural diff instead of a structural diff tangled with forty color changes.

**Files:**

- Modify: `apps/web/src/main/resources/static/styles.css`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java:48-62`

**Interfaces:**

- Produces: the token names listed below, consumed by every later task's CSS.
- Consumes: nothing.

**Steps:**

- [ ] **Step 1: Write the failing assertions**

Replace the stylesheet assertion block in `StaticAssetContractTest.exposesTheAccessibleThreeStepEndpointEditor` (the run beginning `assertTrue(styles.contains("--success: #148f77"));`) with:

```java
assertTrue(styles.contains("--primary: #4f46e5"));
assertTrue(styles.contains("--surface: #ffffff"));
assertTrue(styles.contains("--line: #e2e8f0"));
assertTrue(styles.contains("--text: #0f172a"));
assertTrue(styles.contains("--muted: #475569"));
assertTrue(styles.contains("--success: #10b981"));
assertTrue(styles.contains("--success-text: #047857"));
assertTrue(styles.contains("--warning-text: #92400e"));
assertTrue(styles.contains("--danger-text: #b91c1c"));
assertTrue(styles.contains("color: var(--success-text)"));
assertFalse(styles.contains("#148f77"));
assertFalse(styles.contains("#2455a6"));
```

Keep every other assertion in that method unchanged.

The `--success-text` and `color: var(--success-text)` pair is the point of this task: it locks the decision that success glyphs use the 5.5:1 color rather than the 2.5:1 fill. Do not weaken it later.

- [ ] **Step 2: Run the test to verify it fails**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks --tests '*StaticAssetContractTest'
```

Expected: FAIL on `assertTrue(styles.contains("--primary: #4f46e5"))`.

- [ ] **Step 3: Replace the `:root` block**

Replace lines 1-14 of `styles.css` with:

```css
:root {
  --bg: #f8fafc;
  --surface: #ffffff;
  --surface-2: #f1f5f9;
  --surface-3: #e2e8f0;
  --line: #e2e8f0;
  --line-strong: #94a3b8;
  --text: #0f172a;
  --muted: #475569;
  --soft: #64748b;
  --primary: #4f46e5;
  --primary-dark: #3730a3;
  --primary-light: rgba(79, 70, 229, 0.08);
  --success: #10b981;
  --success-text: #047857;
  --warning: #f59e0b;
  --warning-text: #92400e;
  --danger: #ef4444;
  --danger-text: #b91c1c;
  --radius: 12px;
  --shadow: 0 4px 6px -1px rgb(15 23 42 / 5%), 0 2px 4px -2px rgb(15 23 42 / 5%), 0 0 0 1px rgb(15 23 42 / 2%);
  --dot-color: rgb(79 70 229 / 5%);
  color-scheme: light;
  font-family: Inter, ui-sans-serif, system-ui, sans-serif;
  background: var(--bg);
  color: var(--text);
  line-height: 1.5;
}

body {
  background-image: radial-gradient(var(--dot-color) 1.5px, transparent 1.5px);
  background-size: 20px 20px;
}
```

- [ ] **Step 4: Replace every literal hex in the rest of the file**

Work top to bottom through `styles.css` and substitute tokens. The mapping for the values currently in the file:

| Current literal | Replacement | Rationale |
| --- | --- | --- |
| `#2455a6`, `#1b3f80`, `#173f82`, `#193762`, `#174b9b`, `#3158a4` | `var(--primary)` or `var(--primary-dark)` | Primary family collapses to two tokens |
| `#e4ebf8`, `#edf4ff`, `#edf2fb`, `#f9fbfe`, `#f8faff` | `var(--primary-light)` or `var(--surface-2)` | Tints collapse to two tokens |
| `#c7d1e2`, `#d6deeb`, `#c7cdd7` | `var(--line)` | Default borders |
| `#8ca3c8`, `#8796ad`, `#7d9ce1` | `var(--line-strong)` | Emphasized borders |
| `#152035` | `var(--text)` | Body text |
| `#4a5870`, `#5e6878` | `var(--muted)` | Secondary text |
| `#148f77` | `var(--success)` for borders and backgrounds, `var(--success-text)` for text | The split this task exists for |
| `#f2fbf8`, `#e9f7f3`, `#dff4ed` | `color-mix(in srgb, var(--success) 12%, white)` | Success tints |
| `#72510e` | `var(--warning-text)` | Warning glyphs |
| `#fff0ca` | `color-mix(in srgb, var(--warning) 22%, white)` | Warning fills |
| `#b3261e`, `#9c1c16`, `#8f211b` | `var(--danger-text)` | Danger glyphs |
| `#fff4f2`, `#fbe2df` | `color-mix(in srgb, var(--danger) 12%, white)` | Danger tints |
| `#f3f6fb`, `#f5f7fa` | `var(--bg)` or `var(--surface-2)` | Recessed surfaces |
| `#ffbf47` (focus ring) | `var(--warning)` | Focus ring keeps its high-visibility amber |

Two rules need specific attention:

```css
.progress-list li[data-status="FAILED"] { color: var(--danger-text); font-weight: 700; }
.progress-list li[data-status="SUCCESS"] { color: var(--success-text); }
```

Also update the radius literals: `section, .error-summary`, `.upload-dropzone`, and `.endpoint-card` move from `.85rem`/`.75rem` to `var(--radius)`, and `section, .error-summary` picks up `box-shadow: var(--shadow);` in place of its current literal shadow.

- [ ] **Step 5: Verify no literal hex survives outside `:root`**

```bash
sed '/^:root {/,/^}/d' apps/web/src/main/resources/static/styles.css | grep -n '#[0-9a-fA-F]\{3,6\}'
```

Expected: no output. If a line appears, tokenize it before continuing.

- [ ] **Step 6: Run the test to verify it passes**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS, zero failures across the whole `:apps:web:test` suite.

- [ ] **Step 7: Verify in the browser**

Start the app with `mise run ui` if it is not already running, open `/editor`, and confirm: the page reads as slate and indigo, the dot pattern is visible but faint, the drop zone and buttons are legible, and the hosted dashboard at `/` inherits the tokens without looking broken.

- [ ] **Step 8: Commit**

```bash
git add apps/web/src/main/resources/static/styles.css apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java
git commit -m "style(web): adopt slate and indigo design tokens"
```

---

## Task 2: Four-step wizard

Markup and behavior ship together. Four panels with no controller is a broken page, so splitting them would leave an unreviewable intermediate state.

**Files:**

- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/state.js`
- Modify: `apps/web/src/main/resources/static/app.js`
- Modify: `apps/web/src/main/resources/static/styles.css`
- Create: `apps/web/src/main/resources/static/wizard.js`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`

**Interfaces:**

- Consumes: the token names from Task 1.
- Produces, from `wizard.js`:
  - `initializeWizard() -> {goToStep, syncGate}` — `goToStep(step: number, options?: {focus?: boolean, announce?: boolean})` navigates if the gate allows; `syncGate()` recomputes reachability and clamps `currentStep` down if a gate now fails.
  - `canAdvance(step: number, state: object) -> boolean` — exported for the contract test and for `syncGate`.
- Produces, from `state.js`: `currentStep: number` in the state object, persisted under `gen2spring.currentStep`.

**Steps:**

- [ ] **Step 1: Write the failing assertions**

Rename `exposesTheAccessibleThreeStepEndpointEditor` to `exposesTheAccessibleFourStepEndpointEditor`, keep its existing body, and apply these changes:

```java
// the fourth step now exists; the guard against a fifth remains
assertTrue(index.contains("<h2 id=\"generation-run-title\">4. 생성 및 결과</h2>"));
assertFalse(index.contains("<h2>5."));

// wizard shell
assertTrue(index.contains("class=\"wizard-steps\""));
assertTrue(index.contains("aria-current=\"step\""));
assertTrue(index.contains("data-step=\"1\""));
assertTrue(index.contains("data-step=\"4\""));
assertTrue(index.contains("class=\"wizard-panel\""));
assertTrue(index.contains("id=\"wizard-live\""));
assertTrue(index.contains("id=\"step-back-3\""));
assertTrue(index.contains("id=\"step-next-3\""));
assertTrue(index.contains("tabindex=\"-1\""));
```

Delete the now-false `assertFalse(index.contains("<h2>4."));` line.

Add a new test method:

```java
@Test
void ownsStepNavigationInADedicatedWizardModule() throws Exception {
    String wizard = resource("/static/wizard.js");
    String state = resource("/static/state.js");
    String app = resource("/static/app.js");

    assertTrue(wizard.contains("export function canAdvance(step, state)"));
    assertTrue(wizard.contains("export function initializeWizard"));
    assertTrue(wizard.contains("hashchange"));
    assertTrue(wizard.contains("aria-current"));
    assertTrue(wizard.contains("focus()"));
    assertTrue(state.contains("currentStep"));
    assertTrue(state.contains("gen2spring.currentStep"));
    assertTrue(app.contains("initializeWizard"));
    assertTrue(app.contains("syncGate"));
    assertFalse(wizard.contains("innerHTML"));
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks --tests '*StaticAssetContractTest'
```

Expected: FAIL — `ownsStepNavigationInADedicatedWizardModule` cannot find `/static/wizard.js` (the helper returns `""` for a missing resource, so the first `assertTrue` fails).

- [ ] **Step 3: Add `currentStep` to `state.js`**

Add the storage key beside the existing two, seed it from `sessionStorage`, and persist it in `updateState`:

```javascript
const STEP_KEY = 'gen2spring.currentStep';

// in the initial state object
currentStep: Number(sessionStorage.getItem(STEP_KEY)) || 1,

// in updateState, beside the specificationId and jobId branches
if (Object.hasOwn(patch, 'currentStep')) {
  sessionStorage.setItem(STEP_KEY, String(patch.currentStep));
}
```

In `resetSpecificationState()`, add `currentStep: 1` so removing the file returns the user to step 1.

- [ ] **Step 4: Restructure `editor.html`**

Replace the anchor-link `<nav>` (lines 22-28) with the stepper, wrap each `<section>` as a wizard panel, promote the summary aside, and add the live region. The shell:

```html
<nav aria-label="생성 단계">
  <ol class="wizard-steps" id="wizard-steps">
    <li><button type="button" class="wizard-chip" data-step="1" aria-current="step"><span class="wizard-chip-mark">1</span> OpenAPI 파일</button></li>
    <li><button type="button" class="wizard-chip" data-step="2" disabled><span class="wizard-chip-mark">2</span> Endpoint 선택</button></li>
    <li><button type="button" class="wizard-chip" data-step="3" disabled><span class="wizard-chip-mark">3</span> 생성 설정</button></li>
    <li><button type="button" class="wizard-chip" data-step="4" disabled><span class="wizard-chip-mark">4</span> 생성 및 결과</button></li>
  </ol>
</nav>
<p id="wizard-live" class="visually-hidden" role="status" aria-live="polite"></p>
```

Each of the four sections becomes `class="workflow-step wizard-panel" data-step="N"`, its `<h2>` gains `tabindex="-1"`, and it ends with a navigation row. For example, step 3:

```html
<div class="wizard-nav">
  <button id="step-back-3" class="secondary" type="button">이전</button>
  <button id="step-next-3" type="button">다음: 생성 및 결과</button>
</div>
```

Step 1 has no back button; step 4 has a back button and no next button, because its existing `preview-button` and `generate-button` are the terminal actions. Keep every existing element id exactly as it is — `upload.js`, `operations.js`, `editor.js`, and `app.js` all query by id and none of them are being modified.

Split the current `generation-step` section at the `preview-section` boundary: everything above becomes step 3 (`id="generation-step"`), and `preview-section` becomes a new step 4 section (`id="generation-run-step"`, heading `id="generation-run-title"`). Move `<aside id="generation-summary">` out of `generation-layout` to sit directly under `<main>` as a sibling of the panels.

- [ ] **Step 5: Write `wizard.js`**

Create the module with everything except the gate predicate, which is the next step:

```javascript
import {getState, subscribe, updateState} from './state.js';

const STEPS = [1, 2, 3, 4];
const TITLES = {1: 'OpenAPI 파일', 2: 'Endpoint 선택', 3: '생성 설정', 4: '생성 및 결과'};

export function initializeWizard() {
  const panels = new Map(STEPS.map(step =>
    [step, document.querySelector(`.wizard-panel[data-step="${step}"]`)]));
  const chips = new Map(STEPS.map(step =>
    [step, document.querySelector(`.wizard-chip[data-step="${step}"]`)]));
  const live = document.querySelector('#wizard-live');
  const summary = document.querySelector('#generation-summary');

  const highestReachable = () => {
    let reachable = 1;
    for (const step of STEPS) {
      if (step === 4 || !canAdvance(step, getState())) break;
      reachable = step + 1;
    }
    return reachable;
  };

  const render = () => {
    const current = getState().currentStep;
    const reachable = highestReachable();
    for (const step of STEPS) {
      panels.get(step).hidden = step !== current;
      const chip = chips.get(step);
      chip.disabled = step > reachable;
      if (step === current) chip.setAttribute('aria-current', 'step');
      else chip.removeAttribute('aria-current');
    }
    summary.hidden = current === 1;
    for (const step of STEPS.filter(candidate => candidate < 4)) {
      const next = document.querySelector(`#step-next-${step}`);
      if (next) next.disabled = !canAdvance(step, getState());
    }
  };

  const goToStep = (step, {focus = true, announce = true} = {}) => {
    const target = Math.min(Math.max(step, 1), highestReachable());
    if (target !== getState().currentStep) updateState({currentStep: target});
    else render();
    // Assigning the hash re-enters through hashchange; skip the write when it
    // already matches so the announcement and focus move do not run twice.
    if (window.location.hash !== `#step-${target}`) window.location.hash = `#step-${target}`;
    if (announce) live.textContent = `4단계 중 ${target}단계, ${TITLES[target]}`;
    if (focus) panels.get(target).querySelector('h2').focus();
  };

  const syncGate = () => {
    const reachable = highestReachable();
    if (getState().currentStep > reachable) goToStep(reachable);
    else render();
  };

  document.querySelector('#wizard-steps').addEventListener('click', event => {
    const chip = event.target.closest('.wizard-chip');
    if (chip && !chip.disabled) goToStep(Number(chip.dataset.step));
  });
  for (const step of STEPS) {
    document.querySelector(`#step-next-${step}`)?.addEventListener('click', () => goToStep(step + 1));
    document.querySelector(`#step-back-${step}`)?.addEventListener('click', () => goToStep(step - 1));
  }
  window.addEventListener('hashchange', () => {
    const match = window.location.hash.match(/^#step-([1-4])$/);
    if (match && Number(match[1]) !== getState().currentStep) goToStep(Number(match[1]));
  });

  subscribe(render);
  // The restored step must not steal focus on page load.
  goToStep(getState().currentStep, {focus: false, announce: false});
  return {goToStep, syncGate};
}
```

- [ ] **Step 6: Implement the gate predicate**

> **This step is reserved for the user.** The plan author prepared the module and the call sites; the rule itself is a product decision, not a mechanical one.
>
> Add `canAdvance(step, state)` to `wizard.js`. The spec's table in §7.2 is the baseline:
>
> | Transition | Condition |
> | --- | --- |
> | 1 to 2 | `state.specificationId` is set |
> | 2 to 3 | at least one operation has `enabled === true` |
> | 3 to 4 | the six required fields are non-empty |
>
> The six field ids are `group-id`, `artifact-id`, `package-name`, `provider-name`, `domain-name`, `target-profile`.
>
> The open question is what a failed gate should *do*. Hard-blocking the next button is unambiguous but gives no explanation, and a user staring at a disabled button with six fields on screen may not see which one is empty. Letting the user advance and surfacing a warning on arrival keeps momentum but permits an invalid step 4 where the preview call would fail anyway. A middle option is to block but mark the offending field with `aria-invalid` and name it in the live region.
>
> Signature to fill in:
>
> ```javascript
> export function canAdvance(step, state) {
>   // TODO(user): return true when `step` may advance to `step + 1`.
> }
> ```
>
> If you would rather the implementer choose, say so and the baseline hard-block will be used.

- [ ] **Step 7: Wire `app.js`**

Import and initialize the wizard, and call `syncGate()` wherever state changes could break a gate:

```javascript
import {initializeWizard} from './wizard.js';

const wizard = initializeWizard();
```

Add `wizard.syncGate();` to the end of `invalidatePreview()` and to the end of `resetSpecificationPresentation()`. In the `initializeUpload({onAnalysis: ...})` callback, add `wizard.goToStep(2);` after `renderOperations();` so a completed analysis advances automatically.

Change the `initializeOperations` edit handler so the step 2 "Tool 설정" button also navigates:

```javascript
onEdit: operationId => {
  wizard.goToStep(3);
  selectOperation(operationId);
}
```

Without this, that button silently mutates step 3 while the user is still looking at step 2.

- [ ] **Step 8: Add wizard styles**

Append to `styles.css`, replacing the existing `.step-nav` rules:

```css
.wizard-steps { display: flex; align-items: center; gap: .5rem; margin: 0; padding: 0; list-style: none; overflow-x: auto; }
.wizard-steps li { display: flex; align-items: center; flex: 1; min-width: 0; }
.wizard-chip {
  display: flex; align-items: center; gap: .5rem;
  width: 100%; min-height: 44px; padding: .55rem .9rem;
  border: 1px solid var(--line); border-radius: 999px;
  background: var(--surface); color: var(--muted);
  font-weight: 650; white-space: nowrap;
}
.wizard-chip-mark {
  display: grid; place-items: center;
  width: 1.5rem; height: 1.5rem; border-radius: 50%;
  background: var(--surface-3); color: var(--soft);
  font-size: .78rem; font-weight: 800;
}
.wizard-chip[aria-current="step"] { border-color: var(--primary); background: var(--primary-light); color: var(--text); }
.wizard-chip[aria-current="step"] .wizard-chip-mark { background: var(--primary); color: var(--surface); }
.wizard-chip:disabled { opacity: .5; cursor: not-allowed; }
.wizard-panel[hidden] { display: none; }
.wizard-panel h2:focus-visible { outline: 3px solid var(--warning); outline-offset: 4px; }
.wizard-nav { display: flex; justify-content: space-between; gap: .75rem; margin-top: 1.5rem; padding-top: 1.25rem; border-top: 1px solid var(--line); }
.wizard-nav button { min-width: 8rem; }
.wizard-nav :only-child { margin-left: auto; }
@media (max-width: 720px) {
  .wizard-steps { gap: .35rem; }
  .wizard-chip { padding: .55rem .7rem; font-size: .9rem; }
  .wizard-nav { flex-direction: column-reverse; }
  .wizard-nav button { width: 100%; }
}
```

Since `generation-summary` left `generation-layout`, replace `.generation-layout` with a `main`-level grid so the aside sits beside the active panel:

```css
main { display: grid; grid-template-columns: minmax(0, 1fr) minmax(14rem, 18rem); gap: 1.25rem; align-items: start; }
main > nav, main > #error-summary, main > #wizard-live { grid-column: 1 / -1; }
#generation-summary[hidden] { display: none; }
@media (max-width: 720px) { main { grid-template-columns: minmax(0, 1fr); } }
```

- [ ] **Step 9: Run the test to verify it passes**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS, zero failures.

- [ ] **Step 10: Verify in the browser**

Against the running instance at `/editor`, confirm each of these by hand:

1. Only step 1 is visible on first load; chips 2 through 4 are disabled.
2. Uploading `swagger-3.0.yml` advances to step 2 and enables chip 2.
3. Step 2 next is disabled with nothing selected and enables after one selection.
4. "Tool 설정" on a step 2 card jumps to step 3 with that Tool's accordion open.
5. Clearing `artifact-id` on step 3 disables next; refilling re-enables it.
6. Reaching step 4, then going back to step 3 and clearing `artifact-id`, clamps back to step 3.
7. Reloading on step 3 restores step 3 with the specification and every entered value intact.
8. The browser back button moves one step back rather than leaving the editor.
9. Focus lands on the step heading after every transition; tab order starts inside the new panel.

- [ ] **Step 11: Commit**

```bash
git add apps/web/src/main/resources/templates/editor.html apps/web/src/main/resources/static/wizard.js apps/web/src/main/resources/static/state.js apps/web/src/main/resources/static/app.js apps/web/src/main/resources/static/styles.css apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java
git commit -m "feat(web): split the editor into a four-step wizard"
```

---

## Task 3: Progress bar with collapsed stage detail

**Files:**

- Create: `apps/web/src/main/resources/static/progress.js`
- Modify: `apps/web/src/main/resources/templates/editor.html`
- Modify: `apps/web/src/main/resources/static/app.js:261-279`
- Modify: `apps/web/src/main/resources/static/styles.css`
- Modify: `apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java`

**Interfaces:**

- Consumes: the token names from Task 1; the step 4 panel from Task 2.
- Produces: `renderProgress(snapshot) -> void` from `progress.js`, called by `app.js` inside `renderJob()`. `snapshot` is the existing job snapshot: `{state, currentStage, stages: [{stage, status}], error, downloads}`.

**Steps:**

- [ ] **Step 1: Write the failing assertions**

Add a new test method:

```java
@Test
void summarizesGenerationProgressWithoutLosingStageDetail() throws Exception {
    String index = resource("/templates/editor.html");
    String progress = resource("/static/progress.js");
    String app = resource("/static/app.js");
    String styles = resource("/static/styles.css");

    assertTrue(index.contains("id=\"job-progress\""));
    assertTrue(index.contains("id=\"job-stage-label\""));
    assertTrue(index.contains("id=\"job-progress-value\""));
    assertTrue(index.contains("id=\"job-progress-fill\""));
    assertTrue(index.contains("id=\"job-progress-details\""));
    assertTrue(index.contains("id=\"progress-list\""));
    assertTrue(index.contains("<summary>상세 보기</summary>"));

    assertTrue(progress.contains("export function renderProgress(snapshot)"));
    assertTrue(progress.contains("OpenAPI 문서 분석"));
    assertTrue(progress.contains("Spring 컨텍스트 기동"));
    assertTrue(progress.contains("대표 Tool 호출 검증"));
    assertTrue(progress.contains("산출물 패키징"));
    assertTrue(progress.contains("'SKIPPED'"));
    assertTrue(app.contains("renderProgress(snapshot)"));
    assertTrue(styles.contains(".progress-fill"));

    assertFalse(progress.contains("innerHTML"));
    assertFalse(progress.contains("EventSource"));
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks --tests '*StaticAssetContractTest'
```

Expected: FAIL on `assertTrue(index.contains("id=\"job-progress\""))`.

- [ ] **Step 3: Replace the progress markup**

In the step 4 panel of `editor.html`, replace `<ol id="progress-list" class="progress-list"></ol>` with:

```html
<div id="job-progress" class="job-progress" hidden>
  <div class="progress-head">
    <p id="job-stage-label" class="progress-stage"></p>
    <span id="job-progress-value" class="progress-count"></span>
  </div>
  <div class="progress-track"><div id="job-progress-fill" class="progress-fill"></div></div>
  <details id="job-progress-details" class="progress-details">
    <summary>상세 보기</summary>
    <ol id="progress-list" class="progress-list"></ol>
  </details>
</div>
```

- [ ] **Step 4: Write `progress.js`**

```javascript
const STAGE_LABELS = {
  ANALYZE: 'OpenAPI 문서 분석',
  GENERATE: '프로젝트 코드 생성',
  COMPILE: 'Gradle 컴파일',
  APPLICATION_CONTEXT: 'Spring 컨텍스트 기동',
  MCP_INITIALIZE: 'MCP 서버 초기화',
  MCP_TOOLS_LIST: 'Tool 목록 검증',
  MCP_TOOL_CALL: '대표 Tool 호출 검증',
  PACKAGE: '산출물 패키징'
};
const SETTLED = ['SUCCESS', 'SKIPPED'];

const region = () => document.querySelector('#job-progress');

export function label(stage) {
  return STAGE_LABELS[stage] ?? stage;
}

export function clearProgress() {
  region().hidden = true;
  document.querySelector('#progress-list').replaceChildren();
  document.querySelector('#job-progress-fill').style.width = '0%';
  document.querySelector('#job-progress-details').open = false;
}

export function renderProgress(snapshot) {
  const stages = snapshot.stages ?? [];
  if (stages.length === 0) {
    clearProgress();
    return;
  }
  region().hidden = false;

  const settled = stages.filter(entry => SETTLED.includes(entry.status)).length;
  const failed = stages.find(entry => entry.status === 'FAILED');
  const running = stages.find(entry => entry.status === 'RUNNING');

  document.querySelector('#job-progress-fill').style.width =
    `${Math.round((settled / stages.length) * 100)}%`;
  document.querySelector('#job-progress-value').textContent = `${settled} / ${stages.length}`;
  document.querySelector('#job-stage-label').textContent = failed
    ? `${label(failed.stage)} 단계에서 실패했습니다.`
    : running ? `${label(running.stage)} 진행 중입니다.`
    : settled === stages.length ? '모든 단계를 완료했습니다.'
    : '생성 작업을 준비하고 있습니다.';

  if (failed) document.querySelector('#job-progress-details').open = true;

  document.querySelector('#progress-list').replaceChildren(...stages.map(entry => {
    const item = document.createElement('li');
    item.dataset.status = entry.status;
    item.textContent = `${label(entry.stage)} — ${entry.status}`;
    return item;
  }));
}
```

`label()` falls back to the raw constant, so a future ninth stage degrades to today's behavior rather than rendering `undefined`.

- [ ] **Step 5: Delegate from `app.js`**

Add `import {clearProgress, renderProgress} from './progress.js';` and replace the `ui['progress-list'].replaceChildren(...)` block inside `renderJob()` with `renderProgress(snapshot);`. Replace the three existing `ui['progress-list'].replaceChildren();` calls in `removeJob()` and `resetSpecificationPresentation()` with `clearProgress();`. Remove `'progress-list'` from the `ui` id list, since `progress.js` now owns that element.

- [ ] **Step 6: Add progress styles**

```css
.job-progress { display: grid; gap: .6rem; margin-top: 1rem; padding: 1rem; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface-2); }
.job-progress[hidden] { display: none; }
.progress-head { display: flex; align-items: baseline; justify-content: space-between; gap: .75rem; }
.progress-stage { margin: 0; font-weight: 650; }
.progress-count { color: var(--muted); font-variant-numeric: tabular-nums; font-weight: 700; }
.progress-track { height: 8px; border: 1px solid var(--line); border-radius: 999px; background: var(--surface-3); overflow: hidden; }
.progress-fill { width: 0; height: 100%; border-radius: 999px; background: var(--primary); transition: width .4s ease; }
.progress-details > summary { min-height: 44px; display: flex; align-items: center; color: var(--muted); font-weight: 650; cursor: pointer; }
.progress-list { display: grid; gap: .4rem; margin: 0; padding-left: 1.5rem; }
.progress-list li[data-status="FAILED"] { color: var(--danger-text); font-weight: 700; }
.progress-list li[data-status="SUCCESS"] { color: var(--success-text); }
.progress-list li[data-status="SKIPPED"] { color: var(--soft); }
@media (prefers-reduced-motion: reduce) { .progress-fill { transition: none; } }
```

- [ ] **Step 7: Run the test to verify it passes**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS, zero failures.

- [ ] **Step 8: Verify in the browser**

Run a real generation from step 4 with `swagger-3.0.yml` and confirm: the region appears only after generation starts, the bar advances, the counter reads `n / 8`, the stage sentence is Korean and changes as stages run, `상세 보기` stays collapsed on a clean run, and the downloads appear on completion. Then force a failure — a `provider-name` of `x` with an unreachable server is enough to fail a validation stage — and confirm the disclosure opens by itself with the failed row in red.

- [ ] **Step 9: Commit**

```bash
git add apps/web/src/main/resources/static/progress.js apps/web/src/main/resources/templates/editor.html apps/web/src/main/resources/static/app.js apps/web/src/main/resources/static/styles.css apps/web/src/test/java/io/gen2spring/mcp/app/web/StaticAssetContractTest.java
git commit -m "feat(web): summarize generation progress with a progress bar"
```

---

## Task 4: Responsive and accessibility verification

The previous editor change recorded its manual QA in `design-qa.md`. This task repeats that pass against the wizard and rewrites that record, because its current contents describe the superseded single-page layout.

**Files:**

- Modify: `apps/web/src/main/resources/static/styles.css`
- Modify: `design-qa.md`

**Interfaces:**

- Consumes: everything from Tasks 1 through 3.
- Produces: nothing consumed by later work.

**Steps:**

- [ ] **Step 1: Check the 400 px viewport**

Resize to 400 px and walk all four steps. Confirm no horizontal scrollbar on `<body>`, the chip row scrolls horizontally within itself, `wizard-nav` buttons stack full width, and `generation-summary` renders below the panel rather than beside it. Fix any overflow in `styles.css` at the `@media (max-width: 400px)` block; do not introduce a new breakpoint.

- [ ] **Step 2: Check the 720 px viewport**

Confirm the summary aside drops below the panel at the existing 720 px breakpoint and the chip labels stay readable.

- [ ] **Step 3: Keyboard-only pass**

With the mouse unused: tab from the stepper into the active panel, advance with the next button, confirm focus lands on the new step's heading, confirm disabled chips are skipped by tab, and confirm the drop zone still activates with Enter and Space.

- [ ] **Step 4: Verify contrast**

Sample the rendered colors and confirm against the spec's §8.2 table: `--soft` text at 4.8:1, `--success-text` at 5.5:1, `--danger-text` at 6.5:1, white on `--primary` at 6.3:1. Confirm no glyph anywhere renders in `--success`, `--warning`, or `--danger`.

- [ ] **Step 5: Rewrite `design-qa.md`**

Replace the file with a record following the existing format: a `# Stepwise Editor Design QA` heading, `Date: 2026-08-16`, a `## Scope` list of what was exercised, a `## Visual observations` section, and a `final result:` line. State the four-step flow, the paired `swagger-3.0.yml` and `swagger-3.1.yml` uploads, the reload and clamp behavior, the progress bar and its disclosure, and the 400 px result.

- [ ] **Step 6: Run the full suite**

```bash
mise exec -- ./gradlew :apps:web:test --no-daemon --non-interactive --rerun-tasks
```

Expected: PASS, zero failures.

- [ ] **Step 7: Commit**

```bash
git add apps/web/src/main/resources/static/styles.css design-qa.md
git commit -m "docs: record stepwise editor design QA"
```

---

## Self-Review

**Spec coverage.** §6 step structure → Task 2 Step 4. §7.1 step state → Task 2 Step 3. §7.2 gates → Task 2 Step 6. §7.3 clamp → Task 2 Steps 5 and 7. §7.4 hash → Task 2 Step 5. §7.5 accessibility → Task 2 Steps 4, 5, 8 and Task 4 Step 3. §8.1 tokens → Task 1 Step 3. §8.2 contrast → Task 1 Steps 1 and 4, Task 4 Step 4. §8.3 typography → Task 1 Step 3. §8.4 dot pattern → Task 1 Step 3. §8.5 dark readiness → Task 1 Step 5 (the grep gate). §9 progress → Task 3. §10 module boundaries → Task 2 Step 5 and Task 3 Step 4, both leaf-ward. §11 changed files → the File Structure table. §12 contract tests → Tasks 1, 2, 3 Step 1. §13 verification → Tasks 2, 3 browser steps and Task 4. §14 risks → the clamp risk is exercised by Task 2 Step 10 item 6.

**Type consistency.** `canAdvance(step, state)` is declared in Task 2 Interfaces, asserted in Task 2 Step 1, used in Task 2 Step 5, and defined in Task 2 Step 6 — one signature throughout. `initializeWizard()` returns `{goToStep, syncGate}` and both are used in Task 2 Step 7. `renderProgress(snapshot)` and `clearProgress()` are declared in Task 3 Interfaces, defined in Step 4, and called in Step 5. `#job-progress-details` is the id in the markup, the assertions, and the module.

**Three defects found and fixed during this review.** `initializeWizard` was declared taking `{onStepChange}` in the Interfaces block but written taking no arguments; the declaration now matches the code. `goToStep` assigned the location hash and then announced and moved focus, but the assignment re-enters through `hashchange`, so both ran twice per transition; the handler now compares against `currentStep` before re-entering. The initial `goToStep` on load called `focus()`, stealing focus from the document on every page load; the load call now passes `{focus: false, announce: false}`.

**One deliberate deviation from the plan template.** Task 2 Step 6 is written as a request rather than as finished code. That is intentional and flagged in the step itself; if the executing agent is not the user, the step states the fallback to apply.
