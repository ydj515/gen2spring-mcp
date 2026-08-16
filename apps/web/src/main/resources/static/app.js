import * as api from './api.js';
import {getState, updateState} from './state.js';
import {buildConfiguration, initializeEditor, renderOperations, selectOperation} from './editor.js';
import {initializeUpload} from './upload.js';
import {initializeOperations} from './operations.js';
import {initializeWizard} from './wizard.js';

const byId = id => document.querySelector(`#${id}`);
const ui = Object.fromEntries([
  'error-summary', 'error-message', 'analysis-summary', 'target-profile', 'profile-description',
  'preview-button', 'preview-status',
  'preview-output', 'generate-button', 'delete-job-button', 'job-status', 'progress-list', 'downloads',
  'summary-version', 'summary-selected', 'summary-excluded', 'summary-warnings',
  'summary-profile', 'summary-validation', 'validation-operation'
].map(id => [id, byId(id)]));
const TERMINAL_STATES = ['VALIDATED', 'UNVERIFIED', 'SUCCEEDED', 'FAILED', 'CANCELLED'];

const wizard = initializeWizard();
initializeEditor(invalidatePreview);
initializeOperations({
  onSelectionChange: () => {
    renderOperations();
    invalidatePreview();
  },
  onEdit: operationId => {
    wizard.goToStep(3);
    selectOperation(operationId);
  }
});
const upload = initializeUpload({
  onAnalysis: analysis => {
    ui['preview-button'].disabled = false;
    renderAnalysis(analysis);
    renderOperations();
    invalidatePreview();
    // A retained specification replays this callback on resume; advancing
    // unconditionally would discard the restored step.
    if (getState().currentStep === 1) wizard.goToStep(2);
  },
  onReset: resetSpecificationPresentation,
  onFailure: showFailure
});
ui['preview-button'].addEventListener('click', runPreview);
ui['generate-button'].addEventListener('click', startGeneration);
ui['delete-job-button'].addEventListener('click', removeJob);
if (api.hostedMode) ui['delete-job-button'].textContent = '작업 취소';
for (const id of ['group-id', 'artifact-id', 'package-name', 'provider-name', 'domain-name',
  'target-profile', 'validation-operation', 'validation-arguments']) {
  byId(id).addEventListener('change', invalidatePreview);
}

loadProfiles();
resumeRetainedState().then(() => wizard.syncGate());
renderGenerationSummary();

async function loadProfiles() {
  try {
    const payload = await api.profiles();
    updateState({profiles: payload.profiles});
    ui['target-profile'].replaceChildren(...payload.profiles.map(profile => {
      const option = document.createElement('option');
      option.value = profile.id;
      option.textContent = `${profile.id} — Java ${profile.javaVersion}`;
      return option;
    }));
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
    await pollJob(jobId);
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
  try {
    const configuration = buildConfiguration();
    ui['preview-button'].disabled = true;
    ui['preview-status'].textContent = 'Building the canonical preview.';
    const preview = await api.preview(getState().specificationId, configuration);
    updateState({preview});
    renderPreview(preview);
    ui['preview-status'].textContent = `${preview.tools.length} Tools are ready for generation.`;
    ui['generate-button'].disabled = false;
  } catch (failure) {
    invalidatePreview();
    showFailure(failure);
  } finally {
    updatePreviewGate();
  }
}

async function startGeneration() {
  clearFailure();
  if (!getState().preview) {
    showFailure({message: 'Run a successful preview before generation.'});
    return;
  }
  try {
    const configuration = buildConfiguration();
    ui['generate-button'].disabled = true;
    const accepted = await api.startJob(getState().specificationId, configuration);
    updateState({jobId: accepted.id, job: accepted});
    ui['delete-job-button'].disabled = !api.hostedMode;
    await pollJob(accepted.id);
  } catch (failure) {
    showFailure(failure);
    ui['generate-button'].disabled = false;
  }
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
      ui['job-status'].textContent = 'Cancellation requested.';
      ui['delete-job-button'].disabled = true;
      return;
    }
    updateState({jobId: null, job: null});
    ui['job-status'].textContent = 'Generation job deleted.';
    ui['progress-list'].replaceChildren();
    ui['downloads'].replaceChildren();
    ui['delete-job-button'].disabled = true;
    ui['generate-button'].disabled = !getState().preview;
  } catch (failure) {
    showFailure(failure);
  }
}

function invalidatePreview() {
  updateState({preview: null});
  ui['generate-button'].disabled = true;
  ui['preview-status'].textContent = '생성 전 미리보기가 필요합니다.';
  ui['preview-output'].replaceChildren();
  renderGenerationSummary();
  updatePreviewGate();
  wizard.syncGate();
}

function describeProfile() {
  const profile = getState().profiles.find(candidate => candidate.id === ui['target-profile'].value);
  ui['profile-description'].textContent = profile
    ? `Java ${profile.javaVersion}, Spring Boot ${profile.springBootVersion}, Spring AI ${profile.springAiVersion}.`
    : 'Select one compatibility profile.';
  renderGenerationSummary();
}

function renderAnalysis(analysis) {
  const values = [
    ['파일', analysis.file?.name ?? 'OpenAPI'],
    ['OpenAPI', analysis.openApiVersion],
    ['Endpoint', String(analysis.counts.total)],
    ['선택 가능', String(analysis.counts.supported + analysis.counts.supportedWithWarning)]
  ];
  ui['analysis-summary'].replaceChildren(...values.flatMap(([term, description]) => {
    const dt = document.createElement('dt');
    dt.textContent = term;
    const dd = document.createElement('dd');
    dd.textContent = description;
    return [dt, dd];
  }));
  renderGenerationSummary();
}

function resetSpecificationPresentation() {
  ui['preview-button'].disabled = true;
  clearFailure();
  ui['analysis-summary'].replaceChildren();
  ui['preview-output'].replaceChildren();
  ui['progress-list'].replaceChildren();
  ui['downloads'].replaceChildren();
  ui['job-status'].textContent = '아직 생성 작업을 시작하지 않았습니다.';
  ui['delete-job-button'].disabled = true;
  invalidatePreview();
  renderOperations();
  renderGenerationSummary();
}

function renderGenerationSummary() {
  const state = getState();
  const selected = state.operations.filter(operation => operation.enabled);
  const warnings = selected.filter(operation => operation.status === 'SUPPORTED_WITH_WARNING').length;
  ui['summary-version'].textContent = state.analysis?.openApiVersion ?? '—';
  ui['summary-selected'].textContent = String(selected.length);
  ui['summary-excluded'].textContent = String(Math.max(0, state.operations.length - selected.length));
  ui['summary-warnings'].textContent = String(warnings);
  ui['summary-profile'].textContent = ui['target-profile'].value || '—';
  ui['summary-validation'].textContent = ui['validation-operation'].value
    ? `${ui['validation-operation'].value} · MCP protocol`
    : '미선택';
}

function updatePreviewGate() {
  const required = ['group-id', 'artifact-id', 'package-name', 'provider-name', 'domain-name', 'target-profile'];
  const state = getState();
  ui['preview-button'].disabled = !state.specificationId
    || !state.operations.some(operation => operation.enabled)
    || !ui['validation-operation'].value
    || required.some(id => !byId(id).value.trim());
}

function renderPreview(preview) {
  ui['preview-output'].replaceChildren(...preview.tools.map(tool => {
    const card = document.createElement('article');
    card.className = 'tool-card';
    const heading = document.createElement('h3');
    heading.textContent = tool.name;
    const description = document.createElement('p');
    description.textContent = tool.description;
    const schema = document.createElement('pre');
    schema.textContent = JSON.stringify({
      inputSchema: tool.inputSchema,
      output: tool.output,
      ...(tool.retry ? {retry: tool.retry} : {}),
      ...(tool.pagination ? {pagination: tool.pagination} : {}),
      ...(tool.responseNormalization ? {responseNormalization: tool.responseNormalization} : {})
    }, null, 2);
    card.append(heading, description, schema);
    return card;
  }));
}

function renderJob(snapshot) {
  ui['job-status'].textContent = snapshot.error
    ? `${snapshot.state}: ${snapshot.error.message}`
    : `${snapshot.state}${snapshot.currentStage ? ` — ${snapshot.currentStage}` : ''}`;
  ui['progress-list'].replaceChildren(...snapshot.stages.map(stage => {
    const item = document.createElement('li');
    item.dataset.status = stage.status;
    item.textContent = `${stage.stage}: ${stage.status}`;
    return item;
  }));
  ui['downloads'].replaceChildren(...snapshot.downloads.map(artifact => {
    const name = typeof artifact === 'string' ? artifact : artifact.name;
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = `Download ${name}`;
    button.addEventListener('click', () => downloadArtifact(snapshot.id, artifact));
    return button;
  }));
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
