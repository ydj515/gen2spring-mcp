import * as api from './api.js';
import {analyzedOperations, resetSpecificationState, updateState} from './state.js';

const MAX_BYTES = 10 * 1024 * 1024;
const VALID_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.(?:yaml|yml|json)$/;

export function initializeUpload({onAnalysis, onReset, onFailure}) {
  const surface = document.querySelector('#upload-surface');
  const dropzone = document.querySelector('#upload-dropzone');
  const input = document.querySelector('#specification-file');
  const replace = document.querySelector('#replace-file-button');
  const remove = document.querySelector('#remove-file-button');
  const completed = document.querySelector('#uploaded-file');
  const fileName = document.querySelector('#uploaded-file-name');
  const fileDetails = document.querySelector('#uploaded-file-details');
  const live = document.querySelector('#upload-live-status');
  let dragDepth = 0;

  const setUploadState = state => {
    surface.dataset.uploadState = state;
    const finished = state === 'completed';
    completed.hidden = !finished;
    dropzone.hidden = finished;
    surface.setAttribute('aria-busy', String(state === 'analyzing'));
  };

  const reset = () => {
    input.value = '';
    dragDepth = 0;
    resetSpecificationState();
    setUploadState('idle');
    live.textContent = '분석할 OpenAPI 파일을 선택해 주세요.';
    fileName.textContent = '';
    fileDetails.textContent = '';
    onReset();
  };

  const analyze = async file => {
    resetSpecificationState();
    onReset();
    if (!(file instanceof File) || !VALID_NAME.test(file.name) || file.name.includes('..')
        || file.size < 1 || file.size > MAX_BYTES) {
      setUploadState('error');
      live.textContent = 'YAML, YML, JSON 파일을 10MB 이하로 선택해 주세요.';
      onFailure({message: live.textContent});
      return;
    }
    fileName.textContent = file.name;
    fileDetails.textContent = formatBytes(file.size);
    setUploadState('analyzing');
    live.textContent = `${file.name} 파일을 분석하고 있습니다.`;
    try {
      const analysis = await api.upload(file);
      const operations = analyzedOperations(analysis.operations ?? []);
      updateState({
        specificationId: analysis.id,
        analysis,
        operations,
        selectedOperationId: operations.find(operation => operation.supported)?.operationId ?? null,
        preview: null,
        job: null,
        jobId: null
      });
      setUploadState('completed');
      fileDetails.textContent = `${formatBytes(file.size)} · OpenAPI ${analysis.openApiVersion}`;
      live.textContent = `${analysis.counts.total}개 endpoint 분석을 완료했습니다.`;
      onAnalysis(analysis);
    } catch (failure) {
      resetSpecificationState();
      setUploadState('error');
      live.textContent = 'OpenAPI 파일을 분석하지 못했습니다.';
      onFailure(failure);
    }
  };

  dropzone.addEventListener('click', () => input.click());
  dropzone.addEventListener('keydown', event => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      input.click();
    }
  });
  input.addEventListener('change', () => analyze(input.files?.[0]));
  replace.addEventListener('click', () => input.click());
  remove.addEventListener('click', reset);

  for (const name of ['dragenter', 'dragover']) {
    surface.addEventListener(name, event => {
      event.preventDefault();
      if (name === 'dragenter') dragDepth += 1;
      if (surface.dataset.uploadState !== 'analyzing') setUploadState('drag-over');
    });
  }
  surface.addEventListener('dragleave', event => {
    event.preventDefault();
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0 && surface.dataset.uploadState === 'drag-over') setUploadState('idle');
  });
  surface.addEventListener('drop', event => {
    event.preventDefault();
    dragDepth = 0;
    analyze(event.dataTransfer?.files?.[0]);
  });

  setUploadState('idle');
  return {reset};
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
