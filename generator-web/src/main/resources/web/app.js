import * as api from './api.js';
import {analyzedOperations, getState, updateState} from './state.js';
import {buildConfiguration, initializeEditor, renderOperations} from './editor.js';

const byId = id => document.querySelector(`#${id}`);
const ui = Object.fromEntries([
  'error-summary', 'error-message', 'specification-file', 'upload-button', 'upload-status',
  'analysis-summary', 'target-profile', 'profile-description', 'preview-button', 'preview-status',
  'preview-output', 'generate-button', 'delete-job-button', 'job-status', 'progress-list', 'downloads'
].map(id => [id, byId(id)]));

initializeEditor(invalidatePreview);
ui['upload-button'].addEventListener('click', analyzeSpecification);
ui['preview-button'].addEventListener('click', runPreview);
ui['generate-button'].addEventListener('click', startGeneration);
ui['delete-job-button'].addEventListener('click', removeJob);
for (const id of ['group-id', 'artifact-id', 'package-name', 'provider-name', 'domain-name',
  'target-profile', 'validation-operation', 'validation-arguments']) {
  byId(id).addEventListener('change', invalidatePreview);
}

loadProfiles();
resumeRetainedJob();

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

async function analyzeSpecification() {
  clearFailure();
  const file = ui['specification-file'].files?.[0];
  if (!file || !/\.(?:yaml|yml|json)$/.test(file.name)) {
    showFailure({message: 'Select one .yaml, .yml, or .json OpenAPI file.'});
    return;
  }
  ui['upload-button'].disabled = true;
  ui['upload-status'].textContent = 'Analyzing the local file.';
  try {
    const analysis = await api.upload(file);
    const operations = analyzedOperations(analysis.operations);
    updateState({
      specificationId: analysis.id,
      jobId: null,
      analysis,
      operations,
      selectedOperationId: operations[0]?.operationId ?? null,
      preview: null,
      job: null
    });
    ui['upload-status'].textContent = `${analysis.operationCount} operations analyzed with ${analysis.warningCount} warnings.`;
    renderAnalysis(analysis);
    renderOperations();
    invalidatePreview();
  } catch (failure) {
    showFailure(failure);
    ui['upload-status'].textContent = 'Specification analysis failed safely.';
  } finally {
    ui['upload-button'].disabled = false;
  }
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
    ui['preview-button'].disabled = false;
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
    ui['delete-job-button'].disabled = true;
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
    if (['VALIDATED', 'UNVERIFIED', 'FAILED'].includes(snapshot.state)) {
      ui['delete-job-button'].disabled = false;
      return;
    }
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
  ui['preview-status'].textContent = 'Preview is required before generation.';
  ui['preview-output'].replaceChildren();
}

function describeProfile() {
  const profile = getState().profiles.find(candidate => candidate.id === ui['target-profile'].value);
  ui['profile-description'].textContent = profile
    ? `Java ${profile.javaVersion}, Spring Boot ${profile.springBootVersion}, Spring AI ${profile.springAiVersion}.`
    : 'Select one compatibility profile.';
}

function renderAnalysis(analysis) {
  const values = [
    ['Checksum', analysis.checksum], ['OpenAPI', analysis.openApiVersion],
    ['Base URL', analysis.baseUrl ?? 'Not declared'], ['Operations', String(analysis.operationCount)]
  ];
  ui['analysis-summary'].replaceChildren(...values.flatMap(([term, description]) => {
    const dt = document.createElement('dt');
    dt.textContent = term;
    const dd = document.createElement('dd');
    dd.textContent = description;
    return [dt, dd];
  }));
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
    schema.textContent = JSON.stringify(tool.inputSchema, null, 2);
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
  ui['downloads'].replaceChildren(...snapshot.downloads.map(name => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = `Download ${name}`;
    button.addEventListener('click', () => downloadArtifact(snapshot.id, name));
    return button;
  }));
}

async function downloadArtifact(jobId, name) {
  try {
    const response = await api.download(jobId, name);
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
  const match = disposition.match(/filename="([a-f0-9.-]+)"/);
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
