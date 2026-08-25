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
  'profile-help-button', 'profile-help', 'profile-notice-list',
  'preview-button', 'preview-status',
  'preview-output', 'generate-button', 'delete-job-button', 'job-status', 'downloads',
  'summary-version', 'summary-selected', 'summary-excluded',
  'summary-profile', 'validation-operation', 'validation-parameter-summary', 'validation-checklist',
  'artifact-placeholder-list', 'artifact-status', 'job-error-details', 'job-error-detail'
].map(id => [id, byId(id)]));
const TERMINAL_STATES = ['VALIDATED', 'UNVERIFIED', 'SUCCEEDED', 'FAILED', 'CANCELLED'];
let previewRequestVersion = 0;

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
ui['generate-button'].addEventListener('click', startGeneration);
ui['delete-job-button'].addEventListener('click', removeJob);
initializeProfileHelp();
if (api.hostedMode) ui['delete-job-button'].textContent = '작업 취소';
for (const id of ['group-id', 'artifact-id', 'package-name', 'provider-name', 'domain-name',
  'target-profile', 'validation-operation', 'validation-arguments']) {
  byId(id).addEventListener('change', invalidatePreview);
}
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
    ui['target-profile'].replaceChildren(...payload.profiles.map(profile => {
      const option = document.createElement('option');
      option.value = profile.id;
      option.textContent = formatProfileLabel(profile);
      return option;
    }));
    renderCompatibilityNotices(payload.compatibilityNotices ?? []);
    const preferred = payload.profiles.find(profile => profile.id === 'spring-ai-2.0-java21-mvc-streamable');
    if (preferred) ui['target-profile'].value = preferred.id;
    describeProfile();
    renderGenerationSummary();
    ui['target-profile'].addEventListener('change', describeProfile);
  } catch (failure) {
    showFailure(failure);
  }
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
  clearFailure();
  const requestVersion = ++previewRequestVersion;
  try {
    const configuration = buildConfiguration();
    updateState({preview: null});
    ui['preview-button'].disabled = true;
    ui['generate-button'].disabled = true;
    ui['preview-status'].textContent = '미리보기를 생성하고 있습니다.';
    renderValidationChecklist();
    renderPreviewEmpty('설정을 검증하고 있습니다.');
    const preview = await api.preview(getState().specificationId, configuration);
    if (requestVersion !== previewRequestVersion) return;
    updateState({preview});
    renderPreview(preview);
    ui['preview-status'].textContent = `설정 검증을 완료했습니다. ${preview.tools.length}개 Tool을 생성할 수 있습니다.`;
    ui['generate-button'].disabled = false;
  } catch (failure) {
    if (requestVersion !== previewRequestVersion) return;
    invalidatePreview();
    showFailure(failure);
  } finally {
    if (requestVersion === previewRequestVersion) updatePreviewGate();
  }
}

async function startGeneration() {
  clearFailure();
  if (!getState().preview) {
    showFailure({message: '프로젝트 생성 전에 미리보기를 완료해 주세요.'});
    return;
  }
  try {
    const configuration = buildConfiguration();
    ui['generate-button'].disabled = true;
    const accepted = await api.startJob(getState().specificationId, configuration);
    updateState({jobId: accepted.id, job: accepted});
    ui['delete-job-button'].disabled = !api.hostedMode;
    renderJob(accepted);
    wizard.goToStep(5);
    await followJob(accepted.id);
  } catch (failure) {
    showFailure(failure);
    ui['generate-button'].disabled = false;
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
  previewRequestVersion += 1;
  updateState({preview: null});
  ui['generate-button'].disabled = true;
  ui['preview-status'].textContent = '생성 전 설정 검증이 필요합니다.';
  renderValidationChecklist();
  renderPreviewEmpty();
  renderGenerationSummary();
  updatePreviewGate();
  wizard.syncGate();
}

function describeProfile() {
  const profile = getState().profiles.find(candidate => candidate.id === ui['target-profile'].value);
  ui['profile-description'].textContent = profile
    ? `${formatProfileLabel(profile)} · Spring Boot ${profile.springBootVersion}`
    : '생성 프로필을 선택해 주세요.';
  renderGenerationSummary();
}

function formatProfileLabel(profile) {
  const springAi = String(profile.springAiVersion).split('.').slice(0, 2).join('.');
  const buildTool = profile.buildTool?.type === 'MAVEN' ? 'Maven' : 'Gradle';
  const runtime = profile.webStack === 'WEBFLUX'
    ? `WebFlux ${profile.programmingModel === 'ASYNC' ? 'Async' : profile.programmingModel}`
    : 'MVC';
  return `Spring AI ${springAi} · Java ${profile.javaVersion} · ${buildTool} · ${runtime}`;
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
  clearFailure();
  ui['analysis-summary'].replaceChildren();
  renderPreviewEmpty();
  renderValidationChecklist();
  renderParameterSummary();
  clearProgress();
  renderArtifacts({id: '', downloads: []});
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
  const profile = ui['target-profile'].value;
  ui['summary-profile'].textContent = artifactId && profile ? `${artifactId} · ${profile}` : profile || artifactId || '—';
}

function updatePreviewGate() {
  const required = ['group-id', 'artifact-id', 'package-name', 'provider-name', 'domain-name', 'target-profile'];
  const state = getState();
  ui['preview-button'].disabled = !state.specificationId
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
    meta.textContent = `${parameter.location} · ${parameter.type}${parameter.required ? ' · 필수' : ' · 선택'}`;
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
      label: '생성 Tool',
      detail: enabledCount > 0 ? `${enabledCount}개 Tool 선택` : 'Tool 선택 대기',
      status: enabledCount > 0 ? 'SUCCESS' : 'PENDING'
    },
    {
      label: '미리보기 결과',
      detail: preview ? `${previewCount}개 Tool 확인` : '설정 검증 대기',
      status: preview ? 'SUCCESS' : 'PENDING'
    },
    {
      label: '대표 Tool 포함',
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
    item.append(icon, content);
    return item;
  }));
}

function renderPreviewEmpty(message = '설정을 검증하면 대표 Tool 정보가 표시됩니다.') {
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
    renderPreviewEmpty('대표 Tool을 미리보기 결과에서 확인하지 못했습니다.');
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
  kicker.textContent = '검증 완료';
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
  result.append(heading, description, facts, inputsTitle, inputs, disclosure);
  ui['preview-output'].replaceChildren(result);
}

function renderJob(snapshot) {
  // The stage belongs to the progress bar, which names it in Korean. Repeating
  // it here produced a second, untranslated line reading RUNNING — COMPILE.
  ui['job-status'].textContent = snapshot.error
    ? `${stateLabel(snapshot.state)} ${snapshot.error.message}`
    : stateLabel(snapshot.state);
  renderProgress(snapshot);
  const detail = snapshot.error?.message ?? '';
  ui['job-error-details'].hidden = detail === '';
  ui['job-error-detail'].textContent = detail;
  renderArtifacts(snapshot);
}

function renderArtifacts(snapshot) {
  const downloads = snapshot.downloads ?? [];
  ui['artifact-placeholder-list'].hidden = downloads.length > 0;
  ui['artifact-status'].hidden = downloads.length === 0;
  ui['downloads'].hidden = downloads.length === 0;
  ui['downloads'].replaceChildren(...downloads.map(artifact => {
    const name = typeof artifact === 'string' ? artifact : artifact.name;
    const item = document.createElement('li');
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
    // Collecting a result is not the primary action on this step, so these stay
    // at secondary weight rather than competing with 프로젝트 생성.
    button.className = 'secondary';
    const icon = document.createElement('i');
    icon.className = 'bi bi-download';
    icon.setAttribute('aria-hidden', 'true');
    const label = document.createElement('span');
    label.textContent = '다운로드';
    button.setAttribute('aria-label', `${artifactTitle(name)} 다운로드`);
    button.append(icon, label);
    button.addEventListener('click', () => downloadArtifact(snapshot.id, artifact));
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
  } catch (failure) {
    showFailure(failure);
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
