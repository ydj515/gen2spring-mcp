import {getState, subscribe, updateState} from './state.js';

const STEPS = [1, 2, 3, 4, 5];
const LAST_STEP = 5;
const TITLES = {
  1: '파일 가져오기',
  2: 'Endpoint 선택',
  3: '생성 설정',
  4: '검증 및 생성',
  5: '생성 진행'
};
const REQUIRED_PROJECT_FIELDS = [
  'group-id', 'artifact-id', 'package-name', 'provider-name', 'domain-name', 'target-profile', 'mcp-implementation'
];
const FIELD_LABELS = {
  'group-id': 'Group ID',
  'artifact-id': 'Artifact ID',
  'package-name': 'Package name',
  'provider-name': 'Provider',
  'domain-name': 'Domain',
  'target-profile': 'Compatibility profile',
  'mcp-implementation': 'MCP 구현 방식'
};

const filled = id => (document.querySelector(`#${id}`)?.value ?? '').trim() !== '';

export function blockingReason(step, state) {
  if (step === 1) return state.analysis ? '' : 'OpenAPI 파일을 먼저 분석해 주세요.';
  if (step === 2) {
    return state.operations.some(operation => operation.endpointSelected)
      ? '' : 'endpoint를 하나 이상 선택해 주세요.';
  }
  if (step === 3) {
    const missing = REQUIRED_PROJECT_FIELDS.filter(id => !filled(id));
    if (missing.length !== 0) return `${missing.map(id => FIELD_LABELS[id]).join(', ')} 항목을 채워 주세요.`;
    return state.operations.some(operation => operation.enabled)
      ? '' : '생성할 Tool을 하나 이상 선택해 주세요.';
  }
  if (step === 4) {
    return state.jobId ? '' : '프로젝트 생성을 시작하면 진행 상황을 볼 수 있습니다.';
  }
  return '';
}

// A completed chip names what the step produced, so the stepper doubles as a
// running summary instead of repeating "완료" five times.
export function completedStepDetail(step, state) {
  const operations = state.operations ?? [];
  if (step === 1) return state.analysis?.file?.name ?? '완료';
  if (step === 2) {
    const selected = operations.filter(operation => operation.endpointSelected).length;
    return `${selected} / ${operations.length} 선택`;
  }
  if (step === 3) return `Tool ${operations.filter(operation => operation.enabled).length}개`;
  if (step === 4) return state.jobId ? '생성 시작' : '검증 완료';
  return '완료';
}

// When nothing blocks a step, its action row says what moving on will carry.
export function readySummary(step, state) {
  const operations = state.operations ?? [];
  if (step === 1) {
    const supported = operations.filter(operation => operation.supported).length;
    return supported ? `생성 가능한 Endpoint ${supported}개가 다음 단계에서 기본 선택됩니다.` : '';
  }
  if (step === 2) return `Endpoint ${operations.filter(operation => operation.endpointSelected).length}개 선택`;
  if (step === 3) return `Tool ${operations.filter(operation => operation.enabled).length}개를 생성합니다.`;
  return '';
}

export function canAdvance(step, state) {
  // Gate on the in-memory analysis rather than the persisted specificationId.
  // Local mode has no analysis route, so after a reload sessionStorage still
  // holds an id whose operations were never re-fetched; trusting it would
  // strand the user on an empty step 2.
  if (step === 1) return Boolean(state.analysis);
  if (step === 2) return state.operations.some(operation => operation.endpointSelected);
  if (step === 3) return REQUIRED_PROJECT_FIELDS.every(filled)
    && state.operations.some(operation => operation.enabled);
  // Step 5 opens once a job exists. Starting generation carries the user there,
  // and this keeps the step reachable again after navigating back.
  if (step === 4) return Boolean(state.jobId);
  return false;
}

export function initializeWizard() {
  const panels = new Map(STEPS.map(step =>
    [step, document.querySelector(`.wizard-panel[data-step="${step}"]`)]));
  const chips = new Map(STEPS.map(step =>
    [step, document.querySelector(`.wizard-chip[data-step="${step}"]`)]));
  const live = document.querySelector('#wizard-live');
  const summary = document.querySelector('#generation-summary');
  const stepper = document.querySelector('#wizard-steps');
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  let visibleStep;
  let furthestVisited = getState().currentStep;

  const highestReachable = () => {
    let reachable = 1;
    for (const step of STEPS) {
      if (step === LAST_STEP || !canAdvance(step, getState())) break;
      reachable = step + 1;
    }
    return reachable;
  };

  const isReachable = (step, state) => {
    // A retained local job can be restored without its analysis because local
    // mode has no analysis read route. Keep only the result step independently
    // reachable instead of unlocking the empty configuration steps in between.
    if (step === 5 && state.jobId) return true;
    return step <= highestReachable();
  };

  const keepActiveStepVisible = (current, force = false) => {
    if (stepper.scrollWidth <= stepper.clientWidth || (!force && visibleStep === current)) return;
    chips.get(current).scrollIntoView({
      behavior: reducedMotion.matches ? 'auto' : 'smooth',
      block: 'nearest',
      inline: 'center'
    });
    visibleStep = current;
  };

  const render = (forceStepVisibility = false) => {
    const current = getState().currentStep;
    if (!getState().analysis && !getState().jobId) furthestVisited = 1;
    for (const step of STEPS) {
      panels.get(step).hidden = step !== current;
      const chip = chips.get(step);
      chip.disabled = !isReachable(step, getState());
      if (step === current) chip.setAttribute('aria-current', 'step');
      else chip.removeAttribute('aria-current');
      const complete = step < Math.min(furthestVisited, highestReachable()) && step !== current;
      chip.dataset.complete = String(complete);
      const status = chip.querySelector('.wizard-chip-status');
      if (status) {
        status.textContent = step === current ? '현재 단계'
          : complete ? completedStepDetail(step, getState()) : '대기';
      }
      const next = document.querySelector(`#step-next-${step}`);
      if (next) next.disabled = !canAdvance(step, getState());
      const hint = document.querySelector(`#step-hint-${step}`);
      if (hint) {
        const reason = step === current ? blockingReason(step, getState()) : '';
        hint.textContent = step !== current ? '' : reason || readySummary(step, getState());
        hint.dataset.tone = reason ? 'blocking' : 'info';
      }
    }
    summary.hidden = current === 1;
    keepActiveStepVisible(current, forceStepVisibility);
    // Name the offending fields so a disabled button is never unexplained.
    // Only mark them on the step the user is actually looking at.
    for (const id of REQUIRED_PROJECT_FIELDS) {
      const field = document.querySelector(`#${id}`);
      if (!field) continue;
      if (current === 3 && !filled(id)) {
        field.setAttribute('aria-invalid', 'true');
        const settings = field.closest('details');
        if (settings && getState().analysis) settings.open = true;
      }
      else field.removeAttribute('aria-invalid');
    }
  };

  const goToStep = (step, {
    focus = true,
    announce = true,
    clamp = true,
    forceStepVisibility = false
  } = {}) => {
    const bounded = Math.min(Math.max(step, 1), LAST_STEP);
    const target = clamp && !isReachable(bounded, getState()) ? highestReachable() : bounded;
    furthestVisited = Math.max(furthestVisited, target);
    const changed = target !== getState().currentStep;
    if (changed) {
      updateState({currentStep: target});
      if (!reducedMotion.matches) panels.get(target).animate?.([
        {opacity: 0.5, transform: 'translateY(6px)'},
        {opacity: 1, transform: 'translateY(0)'}
      ], {duration: 180, easing: 'ease-out'});
    }
    else render(forceStepVisibility);
    // Assigning the hash re-enters through hashchange; skip the write when it
    // already matches so the announcement and focus move do not run twice.
    if (window.location.hash !== `#step-${target}`) window.location.hash = `#step-${target}`;
    if (announce) live.textContent = `${LAST_STEP}단계 중 ${target}단계, ${TITLES[target]}`;
    if (focus) panels.get(target).querySelector('h2').focus();
    // A long step leaves the page scrolled to its bottom navigation. The next
    // step must open at its heading, not at the old scroll offset.
    if (changed && focus) window.scrollTo?.({top: 0, behavior: 'instant'});
  };

  const syncGate = () => {
    const reachable = highestReachable();
    if (!isReachable(getState().currentStep, getState())) goToStep(reachable);
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
    const match = window.location.hash.match(/^#step-([1-5])$/);
    if (!match) return;
    const target = Number(match[1]);
    if (target === getState().currentStep) {
      keepActiveStepVisible(target, true);
      return;
    }
    goToStep(target, {forceStepVisibility: true});
  });

  subscribe(() => render());
  // The restored step must not steal focus on page load, and must not be clamped
  // before the retained specification finishes loading. app.js calls syncGate()
  // once the resume settles.
  goToStep(getState().currentStep, {focus: false, announce: false, clamp: false});
  return {goToStep, syncGate};
}
