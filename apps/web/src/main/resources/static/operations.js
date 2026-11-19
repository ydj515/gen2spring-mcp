import {getState, subscribe, updateState} from './state.js';

const GROUP_LABELS = Object.freeze({
  admin: '관리',
  auth: '인증',
  customers: '고객',
  users: '사용자',
  orders: '주문',
  payments: '결제',
  products: '상품',
  inventory: '재고',
  weather: '날씨',
  reports: '리포트',
  'schema-contracts': '스키마 계약'
});

// Analysis issues arrive as stable codes with English messages. The editor is
// Korean, so known codes get a Korean explanation and the original message
// stays available as secondary text.
const ISSUE_MESSAGES = Object.freeze({
  OPERATION_UNSUPPORTED: '이 endpoint 형식은 MCP Tool로 만들 수 없습니다.',
  OPERATION_ID_MISSING: 'operationId가 없어 Tool 이름을 정할 수 없습니다.',
  HTTP_METHOD_UNSUPPORTED: '지원하지 않는 HTTP 메서드입니다.',
  GET_REQUEST_BODY_UNSUPPORTED: 'GET 요청 본문은 지원하지 않습니다.',
  REQUEST_BODY_MEDIA_TYPE_UNSUPPORTED: '요청 본문의 미디어 타입을 지원하지 않습니다.',
  SUCCESS_MEDIA_TYPE_UNSUPPORTED: '성공 응답의 미디어 타입을 지원하지 않습니다.',
  SUCCESS_SCHEMA_UNSUPPORTED: '성공 응답 스키마를 지원하지 않습니다.',
  SCHEMA_MISSING: '스키마 정의가 없습니다.',
  SCHEMA_TYPE_UNSUPPORTED: '지원하지 않는 스키마 타입입니다.',
  SCHEMA_MULTI_TYPE_UNSUPPORTED: '여러 타입을 함께 쓰는 스키마는 지원하지 않습니다.',
  SCHEMA_NESTED_UNSUPPORTED: '중첩 깊이가 생성 한도를 넘습니다.',
  SCHEMA_COMPOSITION_UNSUPPORTED: '조합 스키마(oneOf/anyOf/allOf)가 생성 한도를 넘습니다.',
  SCHEMA_CONSTRAINT_UNSUPPORTED: '충돌하거나 지원하지 않는 스키마 제약이 있습니다.',
  SCHEMA_NULLABILITY_UNSUPPORTED: '지원하지 않는 nullable 조합입니다.',
  SCHEMA_ADDITIONAL_PROPERTIES_UNSUPPORTED: 'additionalProperties 정의를 지원하지 않습니다.',
  RECURSIVE_SCHEMA_UNSUPPORTED: '순환 참조 스키마는 지원하지 않습니다.',
  PARAMETER_NULLABLE_PATH_UNSUPPORTED: 'path 파라미터는 nullable일 수 없습니다.',
  PARAMETER_REQUIRED_NULLABLE_UNSUPPORTED: '필수 query/header 파라미터는 nullable일 수 없습니다.',
  PARAMETER_LOCATION_UNSUPPORTED: '지원하지 않는 파라미터 위치입니다.',
  PARAMETER_SERIALIZATION_UNSUPPORTED: '지원하지 않는 파라미터 직렬화 방식입니다.',
  SECURITY_REQUIREMENT_UNSUPPORTED: '지원하지 않는 인증 요구사항입니다.'
});

const FILTERS = ['all', 'selected', 'unselected', 'warning', 'unsupported'];

export function issueMessage(issue) {
  return ISSUE_MESSAGES[issue?.code] ?? issue?.message ?? '';
}

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
  const prefix = [];
  if (segments[0]?.toLocaleLowerCase('en') === 'api') prefix.push(segments.shift());
  const resource = segments.find(segment => !segment.startsWith('{')) ?? 'other';
  const key = resource.toLocaleLowerCase('en');
  const fallback = key === 'other'
    ? '기타'
    : resource.replaceAll('-', ' ').replaceAll('_', ' ');
  const pathPrefix = key === 'other' ? '' : `/${[...prefix, resource].join('/')}`;
  return {key, label: GROUP_LABELS[key] ?? fallback, pathPrefix};
}

export function initializeOperations({onSelectionChange}) {
  const fieldset = document.querySelector('#operation-selection');
  const search = document.querySelector('#operation-search');
  const filter = document.querySelector('#operation-filter');
  const selectAll = document.querySelector('#select-all-operations');
  const selectAllLabel = document.querySelector('#select-all-label');
  const list = document.querySelector('#operation-list');
  const counts = document.querySelector('#operation-counts');
  const empty = document.querySelector('#operation-empty');
  const groupExpansion = new Map();
  let activeFilter = 'all';

  // Bulk actions only ever touch what the user can see. Applying them to rows
  // hidden by search or filter silently changed selections off screen.
  const visibleOperations = state => {
    const query = search.value.trim().toLocaleLowerCase('ko');
    return state.operations.filter(operation => matchesFilter(operation, activeFilter)
      && searchableText(operation).includes(query));
  };
  const narrowed = () => search.value.trim() !== '' || activeFilter !== 'all';

  const render = () => {
    const state = getState();
    const selectable = state.operations.filter(operation => operation.supported);
    const selected = selectable.filter(operation => operation.endpointSelected);
    const visible = visibleOperations(state);
    const visibleSelectable = visible.filter(operation => operation.supported);
    const visibleSelected = visibleSelectable.filter(operation => operation.endpointSelected);

    fieldset.disabled = !state.analysis;
    selectAll.disabled = visibleSelectable.length === 0;
    selectAll.checked = visibleSelectable.length > 0 && visibleSelected.length === visibleSelectable.length;
    selectAll.indeterminate = visibleSelected.length > 0 && visibleSelected.length < visibleSelectable.length;
    if (selectAllLabel) {
      selectAllLabel.textContent = narrowed()
        ? `표시된 ${visibleSelectable.length}개 모두 선택`
        : '선택 가능한 항목 전체';
    }
    renderFilterCounts(filter, state.operations, activeFilter);
    if (state.analysis) {
      const unsup = state.analysis.counts.unsupported;
      counts.replaceChildren(...(narrowed()
        ? [
            document.createTextNode('표시 '), countValue(visible.length, 'count-highlight'),
            document.createTextNode(' · 이 중 선택 '), countValue(visibleSelected.length, 'count-highlight'),
            document.createTextNode(` · 전체 선택 ${selected.length} / ${state.operations.length}`)
          ]
        : [
            document.createTextNode('선택 '), countValue(selected.length, 'count-highlight'),
            document.createTextNode(` / ${state.operations.length}`),
            document.createTextNode(' · 미지원 '), countValue(unsup, 'count-unsupported')
          ]));
    } else {
      counts.textContent = '파일 분석 후 endpoint를 선택할 수 있습니다.';
    }
    captureGroupExpansion(list, groupExpansion);
    list.replaceChildren(...groupOperations(visible)
      .map(resourceGroup => operationGroupElement(resourceGroup, groupExpansion)));
    empty.hidden = visible.length !== 0;
    renderUnsupportedPreview(state.operations.filter(operation => !operation.supported));
  };

  const applySelection = (sourceIndexes, enabled) => {
    const state = getState();
    const targets = new Set(sourceIndexes);
    const operations = state.operations.map(operation => targets.has(operation.sourceIndex)
      ? {
          ...operation,
          endpointSelected: operation.supported && enabled,
          enabled: operation.supported && enabled
        }
      : operation);
    const selectedOperationId = preservedSelection(operations, state.selectedOperationId);
    updateState({operations, selectedOperationId});
    onSelectionChange();
  };
  const refresh = document.querySelector('#operation-refresh');

  search.addEventListener('input', render);
  filter.addEventListener('click', event => {
    const chip = event.target.closest('[data-filter]');
    if (!chip || !FILTERS.includes(chip.dataset.filter)) return;
    activeFilter = chip.dataset.filter;
    render();
  });
  if (refresh) refresh.addEventListener('click', render);
  selectAll.addEventListener('change', () => {
    const visible = visibleOperations(getState()).filter(operation => operation.supported);
    applySelection(visible.map(operation => operation.sourceIndex), selectAll.checked);
  });
  list.addEventListener('click', event => {
    const groupToggle = event.target.closest('input[data-group-toggle]');
    if (!groupToggle) return;
    // The checkbox sits inside <summary>; cancelling the click keeps the group
    // from collapsing. State, not the native toggle, decides the next render.
    event.preventDefault();
    const members = visibleOperations(getState()).filter(operation => operation.supported
      && operationGroup(operation.path).key === groupToggle.dataset.groupToggle);
    const allSelected = members.every(operation => operation.endpointSelected);
    applySelection(members.map(operation => operation.sourceIndex), !allSelected);
  });
  list.addEventListener('change', event => {
    const checkbox = event.target.closest('input[data-operation-index]');
    if (!checkbox) return;
    applySelection([Number(checkbox.dataset.operationIndex)], checkbox.checked);
  });
  subscribe(render);
  render();
}

// Step 1 explains exclusions up front, as information rather than an error,
// so the first time the user meets them is not a red box mid-list in step 2.
function renderUnsupportedPreview(unsupported) {
  const section = document.querySelector('#analysis-unsupported');
  const list = document.querySelector('#analysis-unsupported-list');
  const title = document.querySelector('#analysis-unsupported-title');
  if (!section || !list) return;
  section.hidden = unsupported.length === 0;
  if (title) title.textContent = `지원하지 않는 Endpoint ${unsupported.length}개`;
  list.replaceChildren(...unsupported.map(operation => {
    const item = document.createElement('li');
    const method = document.createElement('span');
    method.className = 'method-badge';
    method.dataset.method = operation.method;
    method.textContent = operation.method;
    const path = document.createElement('code');
    path.textContent = operation.path;
    const reason = document.createElement('span');
    reason.className = 'analysis-unsupported__reason';
    reason.textContent = operation.issues.map(issueMessage).filter(Boolean).join(' ')
      || '지원하지 않는 형식입니다.';
    item.append(method, path, reason);
    return item;
  }));
}

function renderFilterCounts(filter, operations, activeFilter) {
  for (const chip of filter.querySelectorAll('[data-filter]')) {
    const key = chip.dataset.filter;
    const total = operations.filter(operation => matchesFilter(operation, key)).length;
    const count = chip.querySelector('[data-filter-count]');
    if (count) count.textContent = String(total);
    chip.setAttribute('aria-pressed', String(key === activeFilter));
    if (key === 'warning') chip.hidden = total === 0 && activeFilter !== 'warning';
  }
}

function captureGroupExpansion(list, groupExpansion) {
  for (const item of list.querySelectorAll('.endpoint-group')) {
    const group = item.querySelector('details');
    if (group) groupExpansion.set(item.dataset.groupKey, group.open);
  }
}

function preservedSelection(operations, selectedOperationId) {
  return operations.find(operation => operation.endpointSelected
    && operation.operationId === selectedOperationId)?.operationId
    ?? operations.find(operation => operation.endpointSelected)?.operationId
    ?? null;
}

function operationGroupElement(resourceGroup, groupExpansion) {
  const item = document.createElement('li');
  item.className = 'endpoint-group';
  item.dataset.groupKey = resourceGroup.key;

  const selectable = resourceGroup.operations.filter(operation => operation.supported);
  const selected = selectable.filter(operation => operation.endpointSelected);

  const group = document.createElement('details');
  group.open = groupExpansion.get(resourceGroup.key) ?? true;
  const summary = document.createElement('summary');
  summary.className = 'endpoint-group__summary';
  const groupChevron = document.createElement('i');
  groupChevron.className = 'bi bi-chevron-down endpoint-group__chevron';
  groupChevron.setAttribute('aria-hidden', 'true');
  const toggle = document.createElement('input');
  toggle.type = 'checkbox';
  toggle.className = 'endpoint-group__toggle';
  toggle.dataset.groupToggle = resourceGroup.key;
  toggle.disabled = selectable.length === 0;
  toggle.checked = selectable.length > 0 && selected.length === selectable.length;
  toggle.indeterminate = selected.length > 0 && selected.length < selectable.length;
  toggle.setAttribute('aria-label', `${resourceGroup.label} 그룹 전체 선택`);
  const name = document.createElement('strong');
  name.textContent = resourceGroup.label;
  const meta = document.createElement('span');
  meta.className = 'endpoint-group__meta';
  meta.textContent = [
    resourceGroup.pathPrefix,
    selectable.length === 0 ? `${resourceGroup.operations.length}개 · 모두 미지원`
      : `${resourceGroup.operations.length}개 중 ${selected.length}개 선택`
  ].filter(Boolean).join(' · ');
  summary.append(groupChevron, toggle, name, meta);

  const table = document.createElement('div');
  table.className = 'endpoint-group__table';
  // Unsupported rows sink to the bottom so the selectable list reads first.
  const ordered = [...resourceGroup.operations].sort((left, right) =>
    Number(right.supported) - Number(left.supported));
  table.append(...ordered.map(operationRow));
  group.replaceChildren(summary, table);
  item.append(group);
  return item;
}

function operationRow(operation) {
  const selectable = operation.supported;
  const item = document.createElement('div');
  item.className = 'endpoint-item';
  item.dataset.supportStatus = operation.status;
  item.dataset.selected = String(selectable && operation.endpointSelected);

  const row = document.createElement('div');
  row.className = 'endpoint-row';

  const selection = document.createElement('label');
  selection.className = 'endpoint-check';
  const checkbox = document.createElement('input');
  checkbox.type = 'checkbox';
  checkbox.checked = selectable && operation.endpointSelected;
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

  const description = document.createElement('span');
  description.className = 'endpoint-description';
  description.textContent = operation.summary || operation.operationId || '';

  const statusWrap = document.createElement('div');
  statusWrap.className = 'endpoint-status-wrap';
  const status = document.createElement('span');
  status.className = 'support-badge';
  status.dataset.state = !selectable ? 'unsupported' : operation.endpointSelected ? 'selected' : 'unselected';
  status.textContent = statusLabel(operation);
  statusWrap.append(status);

  row.append(selection, identity, description, statusWrap);
  item.append(row);

  if (operation.issues.length > 0) {
    const issues = document.createElement('div');
    issues.className = 'endpoint-issue-row';
    const issueIcon = document.createElement('i');
    issueIcon.className = selectable ? 'bi bi-exclamation-circle' : 'bi bi-info-circle';
    issueIcon.setAttribute('aria-hidden', 'true');
    const issueList = document.createElement('ul');
    issueList.setAttribute('aria-label', selectable ? '확인 사항' : '지원하지 않는 이유');
    operation.issues.forEach(issue => {
      const entry = document.createElement('li');
      entry.dataset.severity = issue.severity;
      const message = document.createElement('span');
      message.textContent = issueMessage(issue);
      entry.append(message);
      if (ISSUE_MESSAGES[issue.code] && issue.message) {
        const original = document.createElement('small');
        original.textContent = issue.message;
        entry.append(original);
      }
      issueList.append(entry);
    });
    issues.append(issueIcon, issueList);
    item.append(issues);
  }
  return item;
}

function countValue(value, className) {
  const count = document.createElement('span');
  count.className = className;
  count.textContent = String(value);
  return count;
}

function searchableText(operation) {
  return [operation.method, operation.path, operation.operationId, operation.summary, operation.description]
    .filter(Boolean).join(' ').toLocaleLowerCase('ko');
}

function matchesFilter(operation, filter) {
  if (filter === 'selected') return operation.supported && operation.endpointSelected;
  if (filter === 'unselected') return operation.supported && !operation.endpointSelected;
  if (filter === 'warning') return operation.status === 'SUPPORTED_WITH_WARNING';
  if (filter === 'unsupported') return !operation.supported;
  return true;
}

function statusLabel(operation) {
  if (!operation.supported) return '미지원';
  if (!operation.endpointSelected) return '제외';
  if (operation.status === 'SUPPORTED_WITH_WARNING') return '경고';
  return '지원';
}
