import * as api from './api.js';
import {getState, updateState} from './state.js';
import {buildConfiguration, initializeEditor, renderOperations} from './editor.js';
import {initializeUpload} from './upload.js';
import {initializeOperations} from './operations.js';
import {initializeWizard} from './wizard.js';
import {clearProgress, renderProgress, stateLabel} from './progress.js';

const byId = id => document.querySelector(`#${id}`);
const ui = Object.fromEntries([
  'error-summary', 'error-message', 'analysis-summary', 'target-profile', 'profile-description',
  'mcp-implementation', 'mcp-implementation-description', 'mcp-protocol', 'mcp-protocol-description',
  'profile-help-button', 'profile-help', 'profile-notice-list',
  'preview-button', 'preview-status', 'preview-button-label', 'generate-button-label',
  'validation-action', 'validation-action-title', 'validation-overall',
  'validation-stage', 'generation-stage', 'validation-stage-label',
  'preview-output', 'generate-button', 'delete-job-button', 'job-status', 'downloads',
  'summary-version', 'summary-selected', 'summary-excluded',
  'summary-profile', 'validation-operation', 'validation-parameter-summary', 'validation-checklist',
  'artifact-placeholder-list', 'artifact-status', 'job-error-details', 'job-error-detail'
].map(id => [id, byId(id)]));
const TERMINAL_STATES = ['VALIDATED', 'UNVERIFIED', 'SUCCEEDED', 'FAILED', 'CANCELLED'];
let previewRequestVersion = 0;
let validationStatus = 'idle';
let generationPending = false;
let renderedImplementation = null;
const selectedProfileByImplementation = new Map();

const wizard = initializeWizard();
initializeEditor(() => {
  renderParameterSummary();
  invalidatePreview();
});
initializeOperations({
  onSelectionChange: () => {
    renderOperations();
    renderParameterSummary();
    invalidatePreview();
  }
});
const upload = initializeUpload({
  onAnalysis: analysis => {
    ui['preview-button'].disabled = false;
    renderAnalysis(analysis);
    renderOperations();
    renderParameterSummary();
    invalidatePreview();
  },
  onReset: resetSpecificationPresentation,
  onFailure: showFailure
});
ui['preview-button'].addEventListener('click', runPreview);
ui['generate-button'].addEventListener('click', event => {
  if (event.detail > 1) return;
  startGeneration();
});
ui['delete-job-button'].addEventListener('click', removeJob);
initializeProfileHelp();
document.querySelectorAll('[data-implementation]').forEach(button => {
  button.addEventListener('click', () => {
    if (ui['mcp-implementation'].value === button.dataset.implementation) return;
    ui['mcp-implementation'].value = button.dataset.implementation;
    ui['mcp-implementation'].dispatchEvent(new Event('change', {bubbles: true}));
  });
});
ui['mcp-implementation'].addEventListener('change', renderImplementationChoice);
if (api.hostedMode) ui['delete-job-button'].textContent = '작업 취소';
for (const id of ['group-id', 'artifact-id', 'package-name', 'provider-name', 'domain-name',
  'target-profile', 'mcp-implementation', 'mcp-protocol', 'mcp-features', 'validation-operation', 'validation-arguments']) {
  byId(id).addEventListener('input', invalidatePreview);
  byId(id).addEventListener('change', invalidatePreview);
}
ui['mcp-protocol'].addEventListener('change', () => {
  renderProtocolDescription();
  describeProfile();
});
ui['validation-operation'].addEventListener('change', renderParameterSummary);

loadProfiles();
resumeRetainedState().then(() => wizard.syncGate());
renderGenerationSummary();
renderParameterSummary();
renderValidationChecklist();

async function loadProfiles() {
  try {
    const payload = await api.profiles();
    updateState({
      profiles: payload.profiles,
      compatibilityNotices: payload.compatibilityNotices ?? []
    });
    renderCompatibilityNotices(payload.compatibilityNotices ?? []);
    renderImplementationProfiles();
    ui['mcp-implementation'].addEventListener('change', () => {
      renderImplementationProfiles();
      invalidatePreview();
      wizard.syncGate();
    });
    ui['target-profile'].addEventListener('change', () => {
      selectedProfileByImplementation.set(ui['mcp-implementation'].value, ui['target-profile'].value);
      describeProfile();
    });
  } catch (failure) {
    showFailure(failure);
  }
}

function renderImplementationProfiles() {
  renderImplementationChoice();
  const implementation = ui['mcp-implementation'].value;
  const sdk = implementation === 'MCP_JAVA_SDK';
  const previous = ui['target-profile'].value;
  if (renderedImplementation && previous) {
    selectedProfileByImplementation.set(renderedImplementation, previous);
  }
  const profiles = getState().profiles.filter(profile => profile.mcpImplementations?.includes(implementation));
  ui['target-profile'].replaceChildren(...profiles.map(profile => {
    const option = document.createElement('option');
    option.value = profile.id;
    option.textContent = formatProfileLabel(profile);
    return option;
  }));
  const preferredId = sdk ? 'spring-ai-1.1-java21-mvc-streamable' : 'spring-ai-2.0-java21-mvc-streamable';
  const remembered = selectedProfileByImplementation.get(implementation);
  const selection = profiles.find(profile => profile.id === remembered)
    ?? profiles.find(profile => profile.id === previous)
    ?? profiles.find(profile => profile.id === preferredId) ?? profiles[0];
  if (selection) ui['target-profile'].value = selection.id;
  renderedImplementation = implementation;
  const supported = selection?.mcpProtocolVersionsByImplementation?.[implementation] ?? ['2025-03-26'];
  for (const option of ui['mcp-protocol'].options) {
    option.disabled = option.value !== 'LEGACY' && !supported.includes('2026-07-28');
  }
  if (ui['mcp-protocol'].selectedOptions[0]?.disabled) ui['mcp-protocol'].value = 'LEGACY';
  renderProtocolDescription();
  ui['mcp-implementation-description'].textContent = sdk
    ? 'Spring AI 의존성 없이 생성합니다. Spring Boot 3 · MVC를 지원합니다.'
    : '@McpTool 애노테이션과 Spring AI를 사용합니다.';
  renderCompatibilityNotices(sdk ? [] : getState().compatibilityNotices);
  describeProfile();
  renderGenerationSummary();
}

function selectedProtocolLabel() {
  return ui['mcp-protocol'].selectedOptions[0]?.textContent ?? '2025-03-26만 지원';
}

function renderProtocolDescription() {
  byId('mcp-features').disabled = ui['mcp-implementation'].value !== 'MCP_JAVA_SDK' || ui['mcp-protocol'].value === 'LEGACY';
  const hints = {
    LEGACY: '초기화 절차를 사용하는 기존 클라이언트용입니다.',
    MODERN: '초기화 없이 요청마다 버전을 전달합니다. 기존 버전 요청은 거부합니다.',
    DUAL: '두 버전을 같은 endpoint에서 제공합니다. 기존 클라이언트 이전에 사용할 수 있습니다.'
  };
  ui['mcp-protocol-description'].textContent = ui['mcp-implementation'].value === 'MCP_JAVA_SDK'
    ? hints[ui['mcp-protocol'].value]
    : 'Spring AI는 2025-03-26을 지원합니다. 새 버전이나 병행 지원은 MCP Java SDK를 선택하세요.';
}

function renderImplementationChoice() {
  document.querySelectorAll('[data-implementation]').forEach(button => {
    button.setAttribute('aria-pressed', String(button.dataset.implementation === ui['mcp-implementation'].value));
  });
}

async function resumeRetainedJob() {
  const jobId = getState().jobId;
  if (!jobId) return;
  ui['delete-job-button'].disabled = true;
  try {
    await followJob(jobId);
  } catch (failure) {
    if (failure?.code === 'JOB_NOT_FOUND') updateState({jobId: null, job: null});
    else ui['delete-job-button'].disabled = false;
    showFailure(failure);
  }
}

async function resumeRetainedState() {
  await resumeRetainedSpecification();
  await resumeRetainedJob();
}

async function resumeRetainedSpecification() {
  if (!api.hostedMode) return;
  const requested = new URLSearchParams(window.location.search).get('specification');
  const specificationId = requested || getState().specificationId;
  if (specificationId) await upload.load(specificationId);
}

async function runPreview() {
  if (validationStatus === 'validating' || generationPending) return;
  clearFailure();
  const requestVersion = ++previewRequestVersion;
  const initiatedFromButton = document.activeElement === ui['preview-button'];
  try {
    const configuration = buildConfiguration();
    updateState({preview: null});
    ui['preview-button'].disabled = true;
    ui['generate-button'].disabled = true;
    setValidationStatus('validating');
    renderValidationChecklist();
    renderPreviewEmpty('설정을 검증하고 있습니다.');
    const preview = await api.preview(getState().specificationId, configuration);
    if (requestVersion !== previewRequestVersion) return;
    renderPreview(preview);
    if (!preview.tools.some(tool => tool.operationId === ui['validation-operation'].value)) {
      setValidationStatus('failed', '대표 Tool이 생성 대상에 없습니다. Tool 선택을 확인한 뒤 다시 검증해 주세요.');
      return;
    }
    updateState({preview});
    setValidationStatus('ready');
    if (initiatedFromButton && document.activeElement === document.body) {
      ui['generate-button'].focus({preventScroll: true});
    }
  } catch (failure) {
    if (requestVersion !== previewRequestVersion) return;
    updateState({preview: null});
    const message = failure?.message === 'Representative arguments must be one valid JSON object.'
      ? '테스트 입력은 올바른 JSON 객체여야 합니다.' : failure?.message;
    setValidationStatus('failed', message);
    renderPreviewEmpty('설정 검증에 실패했습니다. 오류를 수정한 뒤 다시 검증해 주세요.');
    showFailure({...failure, message});
  } finally {
    if (requestVersion === previewRequestVersion) updatePreviewGate();
  }
}

async function startGeneration() {
  if (generationPending) return;
  clearFailure();
  if (!getState().preview) {
    showFailure({message: '프로젝트 생성 전에 설정 검증을 완료해 주세요.'});
    return;
  }
  try {
    const configuration = buildConfiguration();
    generationPending = true;
    setValidationStatus('generating');
    const accepted = await api.startJob(getState().specificationId, configuration);
    updateState({jobId: accepted.id, job: accepted});
    ui['delete-job-button'].disabled = !api.hostedMode;
    renderJob(accepted);
    wizard.goToStep(5);
    await followJob(accepted.id);
  } catch (failure) {
    showFailure(failure);
  } finally {
    generationPending = false;
    setValidationStatus(getState().preview ? 'ready' : 'changed');
    updatePreviewGate();
  }
}

const FIRST_EVENT_DEADLINE_MILLIS = 5000;
const STREAM_LIVENESS_DEADLINE_MILLIS = 35000;

// Prefers the event stream and hands off to polling when it cannot be
// established or cannot recover. Falling back is one-way for the life of a job,
// so a flapping stream cannot thrash between transports.
async function followJob(jobId) {
  const streamed = await new Promise(resolve => {
    let settled = false;
    let deadline;
    let stream;
    const settle = value => {
      if (settled) return;
      settled = true;
      clearTimeout(deadline);
      stream.close();
      resolve(value);
    };
    const refreshDeadline = timeout => {
      if (settled) return;
      clearTimeout(deadline);
      deadline = setTimeout(() => settle(false), timeout);
    };
    stream = api.jobEvents(jobId, {
      onSnapshot: snapshot => {
        // The job was deleted or replaced; stop without handing off.
        if (getState().jobId !== jobId) return settle(true);
        refreshDeadline(STREAM_LIVENESS_DEADLINE_MILLIS);
        updateState({job: snapshot});
        renderJob(snapshot);
        ui['delete-job-button'].disabled = TERMINAL_STATES.includes(snapshot.state)
          ? api.hostedMode : !api.hostedMode;
      },
      onHeartbeat: () => refreshDeadline(STREAM_LIVENESS_DEADLINE_MILLIS),
      onDone: () => settle(true),
      onFailure: () => settle(false)
    });
    refreshDeadline(FIRST_EVENT_DEADLINE_MILLIS);
  });
  if (streamed) return;
  await pollJob(jobId);
}

async function pollJob(jobId) {
  let delay = 500;
  while (getState().jobId === jobId) {
    const snapshot = await api.job(jobId);
    updateState({job: snapshot});
    renderJob(snapshot);
    if (TERMINAL_STATES.includes(snapshot.state)) {
      ui['delete-job-button'].disabled = api.hostedMode;
      return;
    }
    ui['delete-job-button'].disabled = !api.hostedMode;
    await new Promise(resolve => setTimeout(resolve, delay));
    delay = Math.min(2000, delay + 250);
  }
}

async function removeJob() {
  clearFailure();
  const jobId = getState().jobId;
  if (!jobId) return;
  try {
    await api.deleteJob(jobId);
    if (api.hostedMode) {
      ui['job-status'].textContent = '취소를 요청했습니다.';
      ui['delete-job-button'].disabled = true;
      return;
    }
    updateState({jobId: null, job: null});
    ui['job-status'].textContent = '생성 작업을 삭제했습니다.';
    clearProgress();
    renderArtifacts({id: jobId, downloads: []});
    ui['delete-job-button'].disabled = true;
    ui['generate-button'].disabled = !getState().preview;
    // Deleting the job closes the step 5 gate, so step 5 must stop being current.
    wizard.syncGate();
  } catch (failure) {
    showFailure(failure);
  }
}

function invalidatePreview() {
  const changed = validationStatus !== 'idle';
  previewRequestVersion += 1;
  updateState({preview: null});
  ui['generate-button'].disabled = true;
  setValidationStatus(changed ? 'changed' : 'idle');
  renderValidationChecklist();
  renderPreviewEmpty();
  renderGenerationSummary();
  updatePreviewGate();
  wizard.syncGate();
}

function setValidationStatus(status, errorMessage) {
  validationStatus = status;
  const ready = status === 'ready' || status === 'generating';
  const messages = {
    idle: ['검증 전', '프로젝트 생성 전, 설정 검증이 필요합니다.', '검증을 완료하면 프로젝트를 생성할 수 있습니다.', '설정 검증하기'],
    validating: ['검증 중', '설정을 검증하고 있습니다.', '분석 결과와 Tool 구성을 확인하고 있습니다.', '설정 검증 중…'],
    ready: ['검증 완료', '설정 검증 완료. 프로젝트를 생성할 수 있습니다.', `${getState().preview?.tools.length ?? 0}개 Tool을 포함한 프로젝트를 생성합니다.`, '설정 검증하기'],
    failed: ['수정 필요', '설정 검증에 실패했습니다.', errorMessage || '오류를 수정한 뒤 다시 검증해 주세요.', '다시 검증하기'],
    changed: ['재검증 필요', '설정이 변경되어 다시 검증해야 합니다.', '변경한 설정을 검증하면 프로젝트를 생성할 수 있습니다.', '변경한 설정 검증하기'],
    generating: ['검증 완료', '프로젝트 생성을 시작하고 있습니다.', '생성 요청을 처리하고 있습니다.', '설정 검증하기']
  };
  const [badge, title, detail, action] = messages[status];
  const transferFocus = ready && document.activeElement === ui['preview-button'];
  ui['validation-overall'].textContent = badge;
  ui['validation-overall'].dataset.status = status;
  ui['validation-action'].dataset.status = status;
  ui['validation-action-title'].textContent = title;
  ui['preview-status'].textContent = detail;
  ui['preview-button-label'].textContent = ready ? '다시 검증' : action;
  ui['preview-button'].hidden = false;
  ui['preview-button'].className = ready ? 'secondary' : '';
  ui['generate-button'].hidden = !ready;
  ui['generate-button'].disabled = !ready || generationPending;
  ui['generate-button-label'].textContent = generationPending ? '생성 요청 중…' : '프로젝트 생성';
  ui['validation-stage-label'].textContent = ready ? '설정 검증 완료' : '설정 검증';
  ui['validation-stage'].dataset.complete = String(ready);
  ui['validation-stage'].toggleAttribute('aria-current', !ready);
  ui['generation-stage'].toggleAttribute('aria-current', ready);
  (ready ? ui['generation-stage'] : ui['validation-stage']).setAttribute('aria-current', 'step');
  ui['preview-output'].setAttribute('aria-busy', String(status === 'validating'));
  if (transferFocus) ui['generate-button'].focus({preventScroll: true});
}

function describeProfile() {
  const profile = getState().profiles.find(candidate => candidate.id === ui['target-profile'].value);
  ui['profile-description'].textContent = profile
    ? `${formatProfileLabel(profile)} · Spring Boot ${profile.springBootVersion} · MCP ${selectedProtocolLabel()}`
    : '생성 프로필을 선택해 주세요.';
  renderGenerationSummary();
}

function formatProfileLabel(profile) {
  const springAi = String(profile.springAiVersion).split('.').slice(0, 2).join('.');
  const buildTool = profile.buildTool?.type === 'MAVEN' ? 'Maven' : 'Gradle';
  const runtime = profile.webStack === 'WEBFLUX'
    ? `WebFlux ${profile.programmingModel === 'ASYNC' ? 'Async' : profile.programmingModel}`
    : 'MVC';
  const framework = ui['mcp-implementation'].value === 'MCP_JAVA_SDK'
    ? 'MCP Java SDK' : `Spring AI ${springAi}`;
  return `${framework} · Java ${profile.javaVersion} · ${buildTool} · ${runtime}`;
}

function initializeProfileHelp() {
  const anchor = ui['profile-help-button'].closest('.profile-help-anchor');
  if (!anchor) return;

  anchor.addEventListener('mouseenter', () => {
    setProfileHelpOpen(true);
  });
  anchor.addEventListener('mouseleave', () => {
    setProfileHelpOpen(false);
  });

  anchor.addEventListener('focusin', () => setProfileHelpOpen(true));
  anchor.addEventListener('focusout', event => {
    if (!anchor.contains(event.relatedTarget)) setProfileHelpOpen(false);
  });
  ui['profile-help-button'].addEventListener('click', () => {
    if (window.matchMedia('(hover: none)').matches) setProfileHelpOpen(true);
  });

  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') setProfileHelpOpen(false);
  });
}

function setProfileHelpOpen(open) {
  ui['profile-help-button'].setAttribute('aria-expanded', String(open));
  ui['profile-help'].hidden = !open;
}

function renderCompatibilityNotices(notices) {
  ui['profile-notice-list'].replaceChildren(...notices.map(notice => {
    const item = document.createElement('li');
    const summary = document.createElement('strong');
    summary.textContent = notice.summary;
    const reason = document.createElement('p');
    reason.textContent = notice.reason;
    item.append(summary, reason);
    const reference = safeReferenceUrl(notice.referenceUrl);
    if (reference) {
      const link = document.createElement('a');
      link.href = reference;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.textContent = 'Upstream issue 보기';
      item.append(link);
    }
    return item;
  }));
}

function safeReferenceUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' ? url.toString() : null;
  } catch {
    return null;
  }
}

function renderAnalysis(analysis) {
  const values = [
    ['전체 Endpoint', String(analysis.counts.total)],
    ['선택 가능', String(analysis.counts.supported + analysis.counts.supportedWithWarning)],
    ['경고', String(analysis.counts.supportedWithWarning)],
    ['지원하지 않음', String(analysis.counts.unsupported)]
  ];
  ui['analysis-summary'].replaceChildren(...values.map(([term, description]) => {
    const item = document.createElement('div');
    item.className = 'analysis-summary__item';
    const dt = document.createElement('dt');
    dt.textContent = term;
    const dd = document.createElement('dd');
    dd.textContent = description;
    item.append(dt, dd);
    return item;
  }));
  renderGenerationSummary();
}

function resetSpecificationPresentation() {
  ui['preview-button'].disabled = true;
  validationStatus = 'idle';
  clearFailure();
  ui['analysis-summary'].replaceChildren();
  renderPreviewEmpty();
  renderValidationChecklist();
  renderParameterSummary();
  clearProgress();
  renderArtifacts({id: '', downloads: []});
  const jobPanel = byId('generation-job-step');
  if (jobPanel) jobPanel.dataset.result = 'pending';
  const jobHeading = byId('generation-job-title');
  if (jobHeading) jobHeading.textContent = '생성 진행';
  ui['job-status'].textContent = '아직 생성 작업을 시작하지 않았습니다.';
  ui['delete-job-button'].disabled = true;
  invalidatePreview();
  renderOperations();
  renderGenerationSummary();
}

function renderGenerationSummary() {
  const state = getState();
  const selectedEndpoints = state.operations.filter(operation => operation.endpointSelected);
  const generatedTools = state.operations.filter(operation => operation.enabled);
  const fileName = state.analysis?.file?.name;
  const version = state.analysis?.openApiVersion;
  ui['summary-version'].textContent = fileName && version ? `${fileName} · ${version}` : version ?? '—';
  ui['summary-selected'].textContent = String(selectedEndpoints.length);
  ui['summary-excluded'].textContent = String(generatedTools.length);
  const artifactId = byId('artifact-id').value.trim();
  const selectedProfile = getState().profiles.find(profile => profile.id === ui['target-profile'].value);
  const profile = selectedProfile ? formatProfileLabel(selectedProfile) : '';
  ui['summary-profile'].textContent = artifactId && profile ? `${artifactId} · ${profile}` : profile || artifactId || '—';
  const projectName = byId('project-settings-name');
  const projectProfile = byId('project-settings-profile');
  if (projectName) projectName.textContent = artifactId || '프로젝트 설정';
  if (projectProfile) projectProfile.textContent = profile ? ` · ${profile}` : '';
}

function updatePreviewGate() {
  const required = ['group-id', 'artifact-id', 'package-name', 'provider-name', 'domain-name', 'target-profile', 'mcp-implementation'];
  const state = getState();
  ui['preview-button'].disabled = validationStatus === 'validating' || generationPending || !state.specificationId
    || !state.operations.some(operation => operation.enabled)
    || !ui['validation-operation'].value
    || required.some(id => !byId(id).value.trim());
}

function renderParameterSummary() {
  const operation = getState().operations.find(
    candidate => candidate.operationId === ui['validation-operation'].value);
  if (!operation) {
    const empty = document.createElement('p');
    empty.className = 'empty-state';
    empty.textContent = '대표 Tool을 선택하면 입력 항목을 확인할 수 있습니다.';
    ui['validation-parameter-summary'].replaceChildren(empty);
    return;
  }
  const parameters = operation.parameters.filter(parameter => parameter.source === 'USER_INPUT');
  if (parameters.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'validation-parameter-empty';
    empty.textContent = '사용자가 입력할 파라미터가 없습니다.';
    ui['validation-parameter-summary'].replaceChildren(empty);
    return;
  }
  const list = document.createElement('ul');
  parameters.forEach(parameter => {
    const item = document.createElement('li');
    const name = document.createElement('strong');
    name.textContent = parameter.name;
    const meta = document.createElement('span');
    const location = {QUERY: '쿼리', PATH: '경로', HEADER: '헤더', COOKIE: '쿠키', BODY: '본문'}[parameter.location] ?? parameter.location;
    const schemaType = parameter.schema?.type;
    const type = Array.isArray(schemaType) ? schemaType.join(' / ') : schemaType;
    meta.textContent = `${location} · ${type || '타입 정보 없음'}${parameter.required ? ' · 필수' : ' · 선택'}`;
    item.append(name, meta);
    list.append(item);
  });
  ui['validation-parameter-summary'].replaceChildren(list);
}

function renderValidationChecklist(preview = null) {
  const state = getState();
  const enabledCount = state.operations.filter(operation => operation.enabled).length;
  const previewCount = preview?.tools?.length ?? 0;
  const representativeId = ui['validation-operation'].value;
  const representativeIncluded = Boolean(preview?.tools?.some(tool => tool.operationId === representativeId));
  const checks = [
    {
      label: 'OpenAPI 분석',
      detail: state.analysis ? `OpenAPI ${state.analysis.openApiVersion} 분석 완료` : '분석 대기',
      status: state.analysis ? 'SUCCESS' : 'PENDING'
    },
    {
      label: '생성 대상 Tool',
      detail: enabledCount > 0 ? `${enabledCount}개 Tool 선택` : 'Tool 선택 대기',
      status: enabledCount > 0 ? 'SUCCESS' : 'PENDING'
    },
    {
      label: 'Tool 구성 검증',
      detail: preview ? `${previewCount}개 Tool 확인` : '설정 검증 대기',
      status: preview ? 'SUCCESS' : 'PENDING'
    },
    {
      label: '대표 Tool 포함 여부',
      detail: preview ? (representativeIncluded ? representativeId : '대표 Tool 누락') : '설정 검증 대기',
      status: preview ? (representativeIncluded ? 'SUCCESS' : 'FAILED') : 'PENDING'
    }
  ];
  ui['validation-checklist'].replaceChildren(...checks.map(check => {
    const item = document.createElement('li');
    item.dataset.status = check.status;
    const icon = document.createElement('i');
    icon.className = check.status === 'SUCCESS' ? 'bi bi-check-circle-fill'
      : check.status === 'FAILED' ? 'bi bi-x-circle-fill' : 'bi bi-circle';
    icon.setAttribute('aria-hidden', 'true');
    const content = document.createElement('span');
    const label = document.createElement('strong');
    label.textContent = check.label;
    const detail = document.createElement('small');
    detail.textContent = check.detail;
    content.append(label, detail);
    const badge = document.createElement('span');
    badge.className = 'validation-badge';
    badge.textContent = check.status === 'SUCCESS' ? '완료' : check.status === 'FAILED' ? '수정 필요' : '검증 대기';
    item.append(icon, content, badge);
    return item;
  }));
}

function renderPreviewEmpty(message = '설정을 검증하면 결과가 표시됩니다. 아래 설정 검증하기 버튼을 눌러 주세요.') {
  const empty = document.createElement('p');
  empty.className = 'empty-state';
  empty.textContent = message;
  ui['preview-output'].replaceChildren(empty);
}

function renderPreview(preview) {
  renderValidationChecklist(preview);
  const representativeId = ui['validation-operation'].value;
  const tool = preview.tools.find(candidate => candidate.operationId === representativeId);
  const operation = getState().operations.find(candidate => candidate.operationId === representativeId);
  if (!tool || !operation) {
    renderPreviewEmpty('대표 Tool을 설정 검증 결과에서 확인하지 못했습니다.');
    return;
  }
  const result = document.createElement('article');
  result.className = 'representative-tool-result';
  const heading = document.createElement('div');
  heading.className = 'representative-tool-heading';
  const icon = document.createElement('i');
  icon.className = 'bi bi-check-circle-fill';
  icon.setAttribute('aria-hidden', 'true');
  const title = document.createElement('div');
  const kicker = document.createElement('span');
  kicker.textContent = '대표 Tool 확인 결과';
  const name = document.createElement('h3');
  name.textContent = tool.name;
  title.append(kicker, name);
  heading.append(icon, title);
  const description = document.createElement('p');
  description.textContent = tool.description;
  const facts = document.createElement('dl');
  facts.className = 'representative-tool-facts';
  for (const [term, value] of [
    ['Endpoint', `${operation.method} ${operation.path}`],
    ['Operation ID', operation.operationId],
    ['Output', tool.output?.mode ?? 'GENERIC_JSON']
  ]) {
    const dt = document.createElement('dt');
    dt.textContent = term;
    const dd = document.createElement('dd');
    dd.textContent = value;
    facts.append(dt, dd);
  }
  const inputsTitle = document.createElement('h4');
  inputsTitle.textContent = '입력 파라미터';
  const inputs = document.createElement('ul');
  inputs.className = 'representative-input-list';
  const properties = Object.entries(tool.inputSchema?.properties ?? {});
  if (properties.length === 0) {
    const item = document.createElement('li');
    item.textContent = '사용자 입력 없음';
    inputs.append(item);
  } else {
    properties.forEach(([propertyName, definition]) => {
      const item = document.createElement('li');
      item.textContent = `${propertyName} · ${definition.type ?? 'value'}`;
      inputs.append(item);
    });
  }
  const disclosure = document.createElement('details');
  disclosure.className = 'preview-disclosure';
  const disclosureTitle = document.createElement('summary');
  disclosureTitle.textContent = 'Schema 및 정책 전체 보기';
  const schema = document.createElement('pre');
  schema.textContent = JSON.stringify({
    inputSchema: tool.inputSchema,
    output: tool.output,
    ...(tool.retry ? {retry: tool.retry} : {}),
    ...(tool.pagination ? {pagination: tool.pagination} : {}),
    ...(tool.responseNormalization ? {responseNormalization: tool.responseNormalization} : {})
  }, null, 2);
  disclosure.append(disclosureTitle, schema);
  const included = document.createElement('p');
  included.className = 'representative-tool-included';
  included.textContent = '생성 대상에 포함되어 있습니다.';
  result.append(heading, included, description, facts, inputsTitle, inputs, disclosure);
  ui['preview-output'].replaceChildren(result);
}

function renderJob(snapshot) {
  // The stage belongs to the progress bar, which names it in Korean. Repeating
  // it here produced a second, untranslated line reading RUNNING — COMPILE.
  ui['job-status'].textContent = snapshot.error
    ? `${stateLabel(snapshot.state)} ${snapshot.error.message}`
    : stateLabel(snapshot.state);
  renderProgress(snapshot);
  const hasArchive = (snapshot.downloads ?? []).some(artifact =>
    (typeof artifact === 'string' ? artifact : artifact.name) === 'archive');
  const panel = byId('generation-job-step');
  if (panel) panel.dataset.result = hasArchive ? 'available' : 'pending';
  const heading = byId('generation-job-title');
  if (heading) heading.textContent = hasArchive ? '프로젝트가 준비되었습니다.' : '생성 진행';
  const subtitle = byId('generation-job-description');
  if (subtitle) subtitle.textContent = hasArchive
    ? '프로젝트 소스와 실행 가이드, 검증 결과를 확인하세요.'
    : '생성과 검증이 끝나면 산출물을 내려받을 수 있습니다.';
  // Keep keyboard and screen-reader order aligned with the result-first layout.
  if (panel?.insertBefore) {
    if (hasArchive) panel.insertBefore(byId('artifact-section'), byId('job-progress'));
    else panel.insertBefore(byId('job-progress'), byId('artifact-section'));
  }
  const history = byId('job-progress-details');
  if (history && snapshot.error) history.open = true;
  const detail = snapshot.error?.message ?? '';
  ui['job-error-details'].hidden = detail === '';
  ui['job-error-detail'].textContent = detail;
  renderArtifacts(snapshot);
}

function renderArtifacts(snapshot) {
  const downloads = snapshot.downloads ?? [];
  ui['artifact-placeholder-list'].hidden = downloads.length > 0;
  ui['artifact-status'].hidden = downloads.length === 0;
  const verified = ['VALIDATED', 'SUCCEEDED'].includes(snapshot.state);
  ui['artifact-status'].textContent = verified ? '검증 완료' : '산출물 제공';
  const description = byId('artifact-description');
  if (description) description.textContent = downloads.length
    ? '프로젝트 소스와 검증 결과를 내려받으세요.'
    : '생성과 검증이 완료되면 다운로드할 수 있습니다.';
  ui['downloads'].hidden = downloads.length === 0;
  ui['downloads'].replaceChildren(...downloads.map(artifact => {
    const name = typeof artifact === 'string' ? artifact : artifact.name;
    const item = document.createElement('li');
    item.dataset.artifact = name;
    const metadata = document.createElement('span');
    metadata.className = 'artifact-meta';
    const artifactIcon = document.createElement('i');
    artifactIcon.className = 'bi bi-file-earmark-zip';
    artifactIcon.setAttribute('aria-hidden', 'true');
    const artifactName = document.createElement('strong');
    artifactName.textContent = artifactTitle(name);
    metadata.append(artifactIcon, artifactName);
    const button = document.createElement('button');
    button.type = 'button';
    button.className = name === 'archive' ? 'artifact-download-primary' : 'secondary';
    const icon = document.createElement('i');
    icon.className = 'bi bi-download';
    icon.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span');
    label.textContent = '다운로드';
    button.setAttribute('aria-label', `${artifactTitle(name)} 다운로드`);
    button.append(icon, label);
    button.addEventListener('click', async () => {
      button.disabled = true;
      label.textContent = '다운로드 중…';
      const success = await downloadArtifact(snapshot.id, artifact);
      button.disabled = false;
      label.textContent = '다운로드';
      const status = byId('download-status');
      if (status) status.textContent = success ? `${artifactTitle(name)} 다운로드를 시작했습니다.` : '다운로드하지 못했습니다. 다시 시도해 주세요.';
    });
    item.append(metadata, button);
    return item;
  }));
}

const ARTIFACT_LABELS = {
  archive: '프로젝트 아카이브',
  manifest: '매니페스트',
  report: '검증 리포트'
};

function artifactTitle(name) {
  return ARTIFACT_LABELS[name] ?? name;
}

async function downloadArtifact(jobId, artifact) {
  try {
    const name = typeof artifact === 'string' ? artifact : artifact.name;
    const response = await api.download(jobId, artifact);
    const blobUrl = URL.createObjectURL(await response.blob());
    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = filename(response, `${jobId}-${name}`);
    link.click();
    URL.revokeObjectURL(blobUrl);
    return true;
  } catch (failure) {
    showFailure(failure);
    return false;
  }
}

function filename(response, fallback) {
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = disposition.match(/filename="([A-Za-z0-9._-]+)"/);
  return match?.[1] ?? fallback;
}

function showFailure(failure) {
  ui['error-message'].textContent = failure?.message || 'The local operation failed safely.';
  ui['error-summary'].hidden = false;
  ui['error-summary'].focus();
}

function clearFailure() {
  ui['error-summary'].hidden = true;
  ui['error-message'].textContent = '';
}
