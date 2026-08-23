import {getState, subscribe, updateState} from './state.js';

const GROUP_LABELS = Object.freeze({
  users: '사용자',
  orders: '주문',
  payments: '결제',
  products: '상품',
  inventory: '재고',
  weather: '날씨',
  reports: '리포트',
  'schema-contracts': '스키마 계약'
});

export function groupOperations(operations) {
  const groups = new Map();
  for (const operation of operations) {
    const identity = operationGroup(operation.path);
    if (!groups.has(identity.key)) groups.set(identity.key, {...identity, operations: []});
    groups.get(identity.key).operations.push(operation);
  }
  return [...groups.values()];
}

function operationGroup(path) {
  const segments = String(path ?? '').split('/').filter(Boolean);
  if (segments[0]?.toLocaleLowerCase('en') === 'api') segments.shift();
  const resource = segments.find(segment => !segment.startsWith('{')) ?? 'other';
  const key = resource.toLocaleLowerCase('en');
  const fallback = key === 'other'
    ? '기타'
    : resource.replaceAll('-', ' ').replaceAll('_', ' ');
  return {key, label: GROUP_LABELS[key] ?? fallback};
}

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
    list.replaceChildren(...groupOperations(visible).map(operationGroupElement));
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

function operationGroupElement(resourceGroup) {
  const item = document.createElement('li');
  item.className = 'endpoint-group';
  item.dataset.groupKey = resourceGroup.key;

  const group = document.createElement('details');
  group.open = true;
  const summary = document.createElement('summary');
  summary.className = 'endpoint-group__summary';
  const icon = document.createElement('i');
  icon.className = 'bi bi-folder2-open';
  icon.setAttribute('aria-hidden', 'true');
  const name = document.createElement('strong');
  name.textContent = resourceGroup.label;
  const count = document.createElement('span');
  count.textContent = `(${resourceGroup.operations.length})`;
  const groupChevron = document.createElement('i');
  groupChevron.className = 'bi bi-chevron-up endpoint-group__chevron';
  groupChevron.setAttribute('aria-hidden', 'true');
  summary.append(icon, name, count, groupChevron);

  const table = document.createElement('div');
  table.className = 'endpoint-group__table';
  const columnHead = document.createElement('div');
  columnHead.className = 'endpoint-column-head';
  columnHead.setAttribute('aria-hidden', 'true');
  ['선택', 'ENDPOINT', '설명', '지원 상태', ''].forEach(label => {
    const cell = document.createElement('span');
    cell.textContent = label;
    columnHead.append(cell);
  });
  table.append(columnHead, ...resourceGroup.operations.map(operationRow));
  group.replaceChildren(summary, table);
  item.append(group);
  return item;
}

function operationRow(operation) {
  const selectable = operation.supported;
  const item = document.createElement('div');
  item.className = 'endpoint-item';
  item.dataset.supportStatus = operation.status;

  const row = document.createElement('div');
  row.className = 'endpoint-row';

  const selection = document.createElement('label');
  selection.className = 'endpoint-check';
  const checkbox = document.createElement('input');
  checkbox.type = 'checkbox';
  checkbox.checked = selectable && operation.enabled;
  checkbox.disabled = !selectable;
  checkbox.dataset.operationIndex = operation.sourceIndex;
  checkbox.setAttribute('aria-label', `${operation.method} ${operation.path} 선택`);
  selection.append(checkbox);

  const identity = document.createElement('div');
  identity.className = 'endpoint-identity';
  const method = document.createElement('span');
  method.className = 'method-badge';
  method.dataset.method = operation.method;
  method.textContent = operation.method;
  const path = document.createElement('code');
  path.textContent = operation.path;
  identity.append(method, path);

  const description = document.createElement('div');
  description.className = 'endpoint-description';
  const title = document.createElement('strong');
  title.textContent = operation.summary || operation.operationId || 'operationId가 없는 endpoint';
  const operationId = document.createElement('span');
  operationId.textContent = operation.description || operation.operationId || '설명이 제공되지 않았습니다.';
  description.append(title, operationId);

  const status = document.createElement('span');
  status.className = 'support-badge';
  status.textContent = statusLabel(operation.status);

  const edit = document.createElement('button');
  edit.type = 'button';
  edit.className = 'endpoint-edit secondary';
  edit.dataset.operationIndex = operation.sourceIndex;
  edit.setAttribute('aria-label', `${operation.method} ${operation.path} Tool 설정`);
  const chevron = document.createElement('i');
  chevron.className = 'bi bi-chevron-right';
  chevron.setAttribute('aria-hidden', 'true');
  edit.append(chevron);
  edit.disabled = !(selectable && operation.operationId);

  row.append(selection, identity, description, status, edit);
  item.append(row);

  if (operation.issues.length > 0) {
    const issues = document.createElement('div');
    issues.className = 'endpoint-issue-row';
    const issueIcon = document.createElement('i');
    issueIcon.className = selectable ? 'bi bi-exclamation-circle' : 'bi bi-x-circle-fill';
    issueIcon.setAttribute('aria-hidden', 'true');
    const issueContent = document.createElement('div');
    const issueTitle = document.createElement('strong');
    issueTitle.textContent = selectable ? '확인 사항' : '지원하지 않는 이유';
    const issueList = document.createElement('ul');
    operation.issues.forEach(issue => {
      const entry = document.createElement('li');
      entry.textContent = issue.message;
      entry.dataset.severity = issue.severity;
      issueList.append(entry);
    });
    issueContent.append(issueTitle, issueList);
    issues.append(issueIcon, issueContent);
    item.append(issues);
  }
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
