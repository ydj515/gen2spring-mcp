import {getState, subscribe, updateState} from './state.js';

const STEPS = [1, 2, 3, 4];
const LAST_STEP = 4;
const TITLES = {1: 'OpenAPI 파일', 2: 'Endpoint 선택', 3: '생성 설정', 4: '생성 및 결과'};
const REQUIRED_PROJECT_FIELDS = [
  'group-id', 'artifact-id', 'package-name', 'provider-name', 'domain-name', 'target-profile'
];
const FIELD_LABELS = {
  'group-id': 'Group ID',
  'artifact-id': 'Artifact ID',
  'package-name': 'Package name',
  'provider-name': 'Provider',
  'domain-name': 'Domain',
  'target-profile': 'Compatibility profile'
};

const filled = id => (document.querySelector(`#${id}`)?.value ?? '').trim() !== '';

export function blockingReason(step, state) {
  if (step === 1) return state.analysis ? '' : 'OpenAPI 파일을 먼저 분석해 주세요.';
  if (step === 2) {
    return state.operations.some(operation => operation.enabled)
      ? '' : 'endpoint를 하나 이상 선택해 주세요.';
  }
  if (step === 3) {
    const missing = REQUIRED_PROJECT_FIELDS.filter(id => !filled(id));
    return missing.length === 0
      ? '' : `${missing.map(id => FIELD_LABELS[id]).join(', ')} 항목을 채워 주세요.`;
  }
  return '';
}

export function canAdvance(step, state) {
  // Gate on the in-memory analysis rather than the persisted specificationId.
  // Local mode has no analysis route, so after a reload sessionStorage still
  // holds an id whose operations were never re-fetched; trusting it would
  // strand the user on an empty step 2.
  if (step === 1) return Boolean(state.analysis);
  if (step === 2) return state.operations.some(operation => operation.enabled);
  if (step === 3) return REQUIRED_PROJECT_FIELDS.every(filled);
  return false;
}

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
      if (step === LAST_STEP || !canAdvance(step, getState())) break;
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
      const next = document.querySelector(`#step-next-${step}`);
      if (next) next.disabled = !canAdvance(step, getState());
      const hint = document.querySelector(`#step-hint-${step}`);
      if (hint) hint.textContent = step === current ? blockingReason(step, getState()) : '';
    }
    summary.hidden = current === 1;
    // Name the offending fields so a disabled button is never unexplained.
    // Only mark them on the step the user is actually looking at.
    for (const id of REQUIRED_PROJECT_FIELDS) {
      const field = document.querySelector(`#${id}`);
      if (!field) continue;
      if (current === 3 && !filled(id)) field.setAttribute('aria-invalid', 'true');
      else field.removeAttribute('aria-invalid');
    }
  };

  const goToStep = (step, {focus = true, announce = true, clamp = true} = {}) => {
    const bounded = Math.min(Math.max(step, 1), LAST_STEP);
    const target = clamp ? Math.min(bounded, highestReachable()) : bounded;
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
  // The step 3 gate reads form values rather than state, so typing must re-render.
  // 'change' alone fires only on blur and would leave the next button stale.
  panels.get(3).addEventListener('input', render);
  window.addEventListener('hashchange', () => {
    const match = window.location.hash.match(/^#step-([1-4])$/);
    if (match && Number(match[1]) !== getState().currentStep) goToStep(Number(match[1]));
  });

  subscribe(render);
  // The restored step must not steal focus on page load, and must not be clamped
  // before the retained specification finishes loading. app.js calls syncGate()
  // once the resume settles.
  goToStep(getState().currentStep, {focus: false, announce: false, clamp: false});
  return {goToStep, syncGate};
}
