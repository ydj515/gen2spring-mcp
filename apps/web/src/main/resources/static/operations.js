import {getState, subscribe, updateState} from './state.js';

export function initializeOperations({onSelectionChange, onEdit}) {
  const fieldset = document.querySelector('#operation-selection');
  const search = document.querySelector('#operation-search');
  const filter = document.querySelector('#operation-filter');
  const selectAll = document.querySelector('#select-all-operations');
  const list = document.querySelector('#operation-list');
  const counts = document.querySelector('#operation-counts');
  const empty = document.querySelector('#operation-empty');

  const render = () => {
    const state = getState();
    const selectable = state.operations.filter(operation => operation.supported);
    const selected = selectable.filter(operation => operation.enabled);
    const query = search.value.trim().toLocaleLowerCase('ko');
    const visible = state.operations.filter(operation => matchesFilter(operation, filter.value)
      && searchableText(operation).includes(query));

    fieldset.disabled = !state.analysis;
    selectAll.disabled = selectable.length === 0;
    selectAll.checked = selectable.length > 0 && selected.length === selectable.length;
    selectAll.indeterminate = selected.length > 0 && selected.length < selectable.length;
    counts.textContent = state.analysis
      ? `전체 ${state.operations.length} · 선택 ${selected.length} · 경고 ${state.analysis.counts.supportedWithWarning} · 지원하지 않음 ${state.analysis.counts.unsupported}`
      : '파일 분석 후 endpoint를 선택할 수 있습니다.';
    list.replaceChildren(...visible.map(operationCard));
    empty.hidden = visible.length !== 0;
  };

  const updateSelection = (sourceIndex, enabled) => {
    const state = getState();
    const operations = state.operations.map(operation => operation.sourceIndex === sourceIndex
      ? {...operation, enabled: operation.supported && enabled}
      : operation);
    const selectedOperationId = preservedSelection(operations, state.selectedOperationId);
    updateState({operations, selectedOperationId});
    onSelectionChange();
  };

  search.addEventListener('input', render);
  filter.addEventListener('change', render);
  selectAll.addEventListener('change', () => {
    const state = getState();
    const enabled = selectAll.checked;
    const operations = state.operations.map(operation => ({
      ...operation,
      enabled: operation.supported && enabled
    }));
    updateState({
      operations,
      selectedOperationId: preservedSelection(operations, state.selectedOperationId)
    });
    onSelectionChange();
  });
  list.addEventListener('change', event => {
    const checkbox = event.target.closest('input[data-operation-index]');
    if (!checkbox) return;
    updateSelection(Number(checkbox.dataset.operationIndex), checkbox.checked);
  });
  list.addEventListener('click', event => {
    const button = event.target.closest('button[data-operation-index]');
    if (!button) return;
    const operation = getState().operations.find(
      candidate => candidate.sourceIndex === Number(button.dataset.operationIndex));
    if (operation?.supported && operation.operationId) onEdit(operation.operationId);
  });
  subscribe(render);
  render();
}

function preservedSelection(operations, selectedOperationId) {
  return operations.find(operation => operation.enabled && operation.operationId === selectedOperationId)?.operationId
    ?? operations.find(operation => operation.enabled)?.operationId
    ?? null;
}

function operationCard(operation) {
  const selectable = operation.supported;
  const item = document.createElement('li');
  item.className = 'endpoint-card';
  item.dataset.supportStatus = operation.status;

  const selection = document.createElement('label');
  selection.className = 'endpoint-check';
  const checkbox = document.createElement('input');
  checkbox.type = 'checkbox';
  checkbox.checked = selectable && operation.enabled;
  checkbox.disabled = !selectable;
  checkbox.dataset.operationIndex = operation.sourceIndex;
  checkbox.setAttribute('aria-label', `${operation.method} ${operation.path} 선택`);
  selection.append(checkbox);

  const content = document.createElement('div');
  content.className = 'endpoint-content';
  const identity = document.createElement('div');
  identity.className = 'endpoint-identity';
  const method = document.createElement('span');
  method.className = 'method-badge';
  method.dataset.method = operation.method;
  method.textContent = operation.method;
  const path = document.createElement('code');
  path.textContent = operation.path;
  const status = document.createElement('span');
  status.className = 'support-badge';
  status.textContent = statusLabel(operation.status);
  identity.append(method, path, status);

  const title = document.createElement('strong');
  title.textContent = operation.summary || operation.operationId || 'operationId가 없는 endpoint';
  const description = document.createElement('p');
  description.textContent = operation.description || operation.operationId || '설명이 제공되지 않았습니다.';
  content.append(identity, title, description);

  if (operation.issues.length > 0) {
    const issues = document.createElement('ul');
    issues.className = 'endpoint-issues';
    operation.issues.forEach(issue => {
      const entry = document.createElement('li');
      entry.textContent = issue.message;
      entry.dataset.severity = issue.severity;
      issues.append(entry);
    });
    content.append(issues);
  }

  if (selectable && operation.operationId) {
    const edit = document.createElement('button');
    edit.type = 'button';
    edit.className = 'endpoint-edit secondary';
    edit.dataset.operationIndex = operation.sourceIndex;
    edit.textContent = 'Tool 설정';
    content.append(edit);
  }
  item.append(selection, content);
  return item;
}

function searchableText(operation) {
  return [operation.method, operation.path, operation.operationId, operation.summary, operation.description]
    .filter(Boolean).join(' ').toLocaleLowerCase('ko');
}

function matchesFilter(operation, filter) {
  if (filter === 'supported') return operation.supported;
  if (filter === 'warning') return operation.status === 'SUPPORTED_WITH_WARNING';
  if (filter === 'unsupported') return !operation.supported;
  return true;
}

function statusLabel(status) {
  if (status === 'SUPPORTED') return '지원';
  if (status === 'SUPPORTED_WITH_WARNING') return '경고';
  return '지원하지 않음';
}
