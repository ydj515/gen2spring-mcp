import {getState, updateState} from './state.js';

const ids = names => Object.fromEntries(names.map(name => [name, document.querySelector(`#${name}`)]));
const elements = ids([
  'operation-editor', 'tool-name',
  'tool-description', 'parameter-editor', 'data-path', 'success-code-path', 'success-values',
  'error-message-path', 'total-count-path', 'validation-operation', 'output-mode',
  'selected-tool-list', 'selected-tool-empty', 'tool-editor-target',
  'retry-enabled', 'retry-status-codes', 'retry-network-errors', 'retry-max-retries',
  'retry-initial-backoff', 'retry-max-backoff', 'retry-respect-retry-after',
  'pagination-enabled', 'pagination-request-parameter', 'pagination-initial-value',
  'pagination-items-path', 'pagination-next-value-path', 'pagination-max-pages', 'pagination-max-items',
  'selected-tool-list-container', 'tool-editor-panel'
]);

export function initializeEditor(onDirty) {
  for (const id of ['tool-name', 'tool-description', 'data-path',
    'success-code-path', 'success-values', 'error-message-path', 'total-count-path', 'output-mode',
    'retry-enabled', 'retry-status-codes', 'retry-network-errors', 'retry-max-retries',
    'retry-initial-backoff', 'retry-max-backoff', 'retry-respect-retry-after',
    'pagination-enabled', 'pagination-request-parameter', 'pagination-initial-value',
    'pagination-items-path', 'pagination-next-value-path', 'pagination-max-pages', 'pagination-max-items']) {
    elements[id].addEventListener('change', () => {
      saveSelectedOperation();
      updateToolSummary(getState().selectedOperationId);
      onDirty();
    });
  }
  elements['parameter-editor'].addEventListener('change', event => {
    const control = event.target.closest('[data-parameter-name]');
    if (!control) return;
    saveSelectedOperation();
    renderSelectedOperation();
    updateToolSummary(getState().selectedOperationId);
    onDirty();
  });
  elements['selected-tool-list'].addEventListener('click', event => {
    const button = event.target.closest('button[data-operation-id]');
    if (button) selectOperation(button.dataset.operationId);
  });
  elements['selected-tool-list'].addEventListener('change', event => {
    const checkbox = event.target.closest('input[data-tool-enabled-id]');
    if (!checkbox) return;
    saveSelectedOperation();
    const operations = getState().operations.map(operation => operation.operationId === checkbox.dataset.toolEnabledId
      ? {...operation, enabled: operation.supported && checkbox.checked}
      : operation);
    updateState({operations});
    renderToolRows();
    renderValidationOperations();
    onDirty();
  });
  document.querySelectorAll('.policy-section').forEach(section => {
    section.addEventListener('toggle', () => {
      if (!section.open) return;
      document.querySelectorAll('.policy-section').forEach(candidate => {
        if (candidate !== section) candidate.open = false;
      });
    });
  });
}

export function renderOperations() {
  renderToolRows();
  renderValidationOperations();
}

export function selectOperation(operationId) {
  saveSelectedOperation();
  const exists = getState().operations.some(operation => operation.supported
    && operation.operationId === operationId);
  if (!exists) return;
  updateState({selectedOperationId: operationId});
  elements['tool-editor-panel'].open = true;
  renderToolRows();
  elements['operation-editor'].querySelector('input, textarea, select')?.focus();
}

function renderToolRows() {
  const state = getState();
  const tools = state.operations.filter(operation => operation.endpointSelected && operation.operationId);
  let selectedOperationId = state.selectedOperationId;
  if (!tools.some(operation => operation.operationId === selectedOperationId)) {
    selectedOperationId = tools[0]?.operationId ?? null;
    updateState({selectedOperationId});
  }
  const hasTools = tools.length !== 0;
  elements['selected-tool-empty'].hidden = hasTools;
  if (elements['selected-tool-list-container']) {
    elements['selected-tool-list-container'].classList.toggle('is-empty', !hasTools);
  }
  elements['selected-tool-list'].replaceChildren(...tools.map(operation => toolRow(operation, selectedOperationId)));
  renderSelectedOperation();
}

function toolRow(operation, selectedOperationId) {
  const row = document.createElement('div');
  row.className = 'tool-table__row';
  row.dataset.operationId = operation.operationId;
  row.setAttribute('role', 'row');
  row.setAttribute('aria-selected', String(operation.operationId === selectedOperationId));

  const enabledLabel = document.createElement('label');
  enabledLabel.className = 'tool-enabled-toggle';
  const checkbox = document.createElement('input');
  checkbox.type = 'checkbox';
  checkbox.checked = operation.enabled;
  checkbox.dataset.toolEnabledId = operation.operationId;
  checkbox.setAttribute('aria-label', `${operation.method} ${operation.path} Tool 생성`);
  enabledLabel.append(checkbox);

  const select = document.createElement('button');
  select.type = 'button';
  select.className = 'tool-row-select';
  select.dataset.operationId = operation.operationId;
  select.setAttribute('aria-label', `${operation.method} ${operation.path} 세부 설정`);
  const identity = document.createElement('span');
  identity.className = 'tool-row-identity';
  const method = document.createElement('span');
  method.className = 'method-badge';
  method.dataset.method = operation.method;
  method.textContent = operation.method;
  const path = document.createElement('code');
  path.textContent = operation.path;
  identity.append(method, path);
  const operationId = document.createElement('span');
  operationId.className = 'tool-row-operation-id';
  operationId.textContent = operation.operationId;
  const tool = document.createElement('span');
  tool.className = 'tool-row-name';
  tool.textContent = operation.toolName;
  const configuration = document.createElement('span');
  configuration.className = 'configuration-state';
  configuration.dataset.role = 'configuration-state';
  configuration.textContent = hasOverrides(operation) ? '사용자 설정' : '기본값';
  select.append(identity, operationId, tool, configuration);
  row.append(enabledLabel, select);
  return row;
}

function updateToolSummary(operationId) {
  const operation = getState().operations.find(candidate => candidate.operationId === operationId);
  const row = [...elements['selected-tool-list'].querySelectorAll('[data-operation-id]')]
    .find(candidate => candidate.dataset.operationId === operationId);
  if (!operation || !row) return;
  row.querySelector('.tool-row-name').textContent = operation.toolName;
  row.querySelector('[data-role="configuration-state"]').textContent = hasOverrides(operation)
    ? '사용자 설정' : '기본값';
}

function hasOverrides(operation) {
  const normalization = operation.responseNormalization;
  return operation.toolName !== operation.defaultToolName
    || operation.toolDescription !== operation.defaultToolDescription
    || operation.outputMode !== 'GENERIC_JSON'
    || operation.retry.enabled || operation.pagination.enabled
    || operation.parameters.some(parameter => parameter.source !== 'USER_INPUT')
    || normalization.dataPath !== '' || normalization.successCodePath !== ''
    || normalization.successValuesText !== '[]' || normalization.errorMessagePath !== ''
    || normalization.totalCountPath !== '';
}

function renderSelectedOperation() {
  const operation = selectedOperation();
  elements['operation-editor'].disabled = !operation;
  elements['tool-editor-target'].textContent = operation
    ? `${operation.method} ${operation.path}`
    : '왼쪽에서 Tool을 선택하세요.';
  if (!operation) return;
  elements['tool-name'].value = operation.toolName;
  elements['tool-description'].value = operation.toolDescription;
  elements['output-mode'].value = operation.outputMode;
  elements['retry-enabled'].checked = operation.retry.enabled;
  elements['retry-status-codes'].value = operation.retry.statusCodesText;
  elements['retry-network-errors'].checked = operation.retry.networkErrors;
  elements['retry-max-retries'].value = operation.retry.maxRetries;
  elements['retry-initial-backoff'].value = operation.retry.initialBackoffMillis;
  elements['retry-max-backoff'].value = operation.retry.maxBackoffMillis;
  elements['retry-respect-retry-after'].checked = operation.retry.respectRetryAfter;
  elements['pagination-enabled'].checked = operation.pagination.enabled;
  elements['pagination-request-parameter'].value = operation.pagination.requestParameter;
  elements['pagination-initial-value'].value = operation.pagination.initialValueText;
  elements['pagination-items-path'].value = operation.pagination.itemsPath;
  elements['pagination-next-value-path'].value = operation.pagination.nextValuePath;
  elements['pagination-max-pages'].value = operation.pagination.maxPages;
  elements['pagination-max-items'].value = operation.pagination.maxItems;
  setPolicyControls('retry', operation.retry.enabled);
  setPolicyControls('pagination', operation.pagination.enabled);
  elements['data-path'].value = operation.responseNormalization.dataPath;
  elements['success-code-path'].value = operation.responseNormalization.successCodePath;
  elements['success-values'].value = operation.responseNormalization.successValuesText;
  elements['error-message-path'].value = operation.responseNormalization.errorMessagePath;
  elements['total-count-path'].value = operation.responseNormalization.totalCountPath;
  elements['parameter-editor'].replaceChildren(...operation.parameters.map(parameter => parameterRow(parameter)));
}

function parameterRow(parameter) {
  const row = document.createElement('div');
  row.className = 'parameter-row';
  const name = document.createElement('div');
  name.className = 'parameter-name';
  name.textContent = `${parameter.name} (${parameter.location}, ${parameter.type})`;
  const sourceField = document.createElement('div');
  const sourceLabel = document.createElement('label');
  sourceLabel.textContent = 'Source';
  const source = document.createElement('select');
  source.dataset.parameterName = parameter.name;
  source.dataset.parameterField = 'source';
  for (const value of ['USER_INPUT', 'SERVER_SECRET']) {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = value;
    option.selected = value === parameter.source;
    source.append(option);
  }
  sourceLabel.append(source);
  sourceField.append(sourceLabel);
  const environmentField = document.createElement('div');
  const environmentLabel = document.createElement('label');
  environmentLabel.textContent = 'Environment variable';
  const environment = document.createElement('input');
  environment.dataset.parameterName = parameter.name;
  environment.dataset.parameterField = 'environmentVariable';
  environment.value = parameter.environmentVariable;
  environment.disabled = parameter.source !== 'SERVER_SECRET';
  environmentLabel.append(environment);
  environmentField.append(environmentLabel);
  row.append(name, sourceField, environmentField);
  return row;
}

function saveSelectedOperation() {
  const state = getState();
  const selected = state.operations.find(operation => operation.operationId === state.selectedOperationId) ?? null;
  if (!selected) return;
  const parameterControls = [...elements['parameter-editor'].querySelectorAll('[data-parameter-name]')];
  const parameters = selected.parameters.map(parameter => {
    const controls = parameterControls.filter(control => control.dataset.parameterName === parameter.name);
    return {...parameter, ...Object.fromEntries(controls.map(control => [control.dataset.parameterField, control.value]))};
  });
  let successValues = selected.responseNormalization.successValues;
  try {
    successValues = parseSafeJson(elements['success-values'].value);
    if (!Array.isArray(successValues)) throw new Error('array');
  } catch {
    // Preserve the raw value so the final strict build can reject it.
  }
  let statusCodes = selected.retry.statusCodes;
  try {
    statusCodes = normalizeRetryStatusCodes(elements['retry-status-codes'].value);
  } catch {
    // Preserve the prior parsed value while retaining the raw field for strict build validation.
  }
  let initialValue = selected.pagination.initialValue;
  try {
    initialValue = parsePaginationInitialValue(elements['pagination-initial-value'].value);
  } catch {
    // Preserve the prior parsed value while retaining the raw field for strict build validation.
  }
  const replacement = {
    ...selected,
    toolName: elements['tool-name'].value.trim(),
    toolDescription: elements['tool-description'].value.trim(),
    outputMode: elements['output-mode'].value,
    retry: {
      enabled: elements['retry-enabled'].checked,
      statusCodes,
      statusCodesText: elements['retry-status-codes'].value.trim(),
      networkErrors: elements['retry-network-errors'].checked,
      maxRetries: Number(elements['retry-max-retries'].value),
      initialBackoffMillis: Number(elements['retry-initial-backoff'].value),
      maxBackoffMillis: Number(elements['retry-max-backoff'].value),
      respectRetryAfter: elements['retry-respect-retry-after'].checked
    },
    pagination: {
      enabled: elements['pagination-enabled'].checked,
      requestParameter: elements['pagination-request-parameter'].value.trim(),
      initialValue,
      initialValueText: elements['pagination-initial-value'].value.trim(),
      itemsPath: elements['pagination-items-path'].value.trim(),
      nextValuePath: elements['pagination-next-value-path'].value.trim(),
      maxPages: Number(elements['pagination-max-pages'].value),
      maxItems: Number(elements['pagination-max-items'].value)
    },
    parameters,
    responseNormalization: {
      dataPath: elements['data-path'].value.trim(),
      successCodePath: elements['success-code-path'].value.trim(),
      successValues,
      successValuesText: elements['success-values'].value.trim(),
      errorMessagePath: elements['error-message-path'].value.trim(),
      totalCountPath: elements['total-count-path'].value.trim()
    }
  };
  const operations = state.operations.map(operation =>
    operation.operationId === replacement.operationId ? replacement : operation);
  updateState({operations});
  setPolicyControls('retry', replacement.retry.enabled);
  setPolicyControls('pagination', replacement.pagination.enabled);
}

function setPolicyControls(prefix, enabled) {
  const fields = document.querySelector(`#${prefix}-fields`);
  if (!fields) return;
  fields.hidden = !enabled;
  fields.querySelectorAll(`[id^="${prefix}-"]`).forEach(control => {
    control.disabled = !enabled;
  });
}

function renderValidationOperations() {
  const selected = elements['validation-operation'].value;
  const options = getState().operations.filter(operation => operation.enabled).map(operation => {
    const option = document.createElement('option');
    option.value = operation.operationId;
    option.textContent = operation.operationId;
    return option;
  });
  elements['validation-operation'].replaceChildren(...options);
  if (options.some(option => option.value === selected)) elements['validation-operation'].value = selected;
}

function selectedOperation() {
  const state = getState();
  return state.operations.find(operation => operation.operationId === state.selectedOperationId) ?? null;
}

export function buildConfiguration() {
  saveSelectedOperation();
  const state = getState();
  const value = id => document.querySelector(`#${id}`).value.trim();
  const required = ['group-id', 'artifact-id', 'package-name', 'provider-name', 'domain-name', 'target-profile'];
  if (!state.specificationId || required.some(id => !value(id))) {
    throw new Error('Analyze a specification and complete every project field.');
  }
  const enabled = state.operations.filter(operation => operation.enabled);
  if (enabled.length === 0) throw new Error('Enable at least one supported operation.');
  if (enabled.some(operation => !operation.supported || !operation.toolName || !operation.toolDescription)) {
    throw new Error('Every enabled operation needs supported metadata and Tool fields.');
  }
  if (enabled.some(operation => operation.parameters.some(parameter =>
    parameter.source === 'SERVER_SECRET' && !parameter.environmentVariable.trim()))) {
    throw new Error('Every server secret needs an environment variable name.');
  }
  let argumentsValue;
  try {
    argumentsValue = parseSafeJson(value('validation-arguments'));
    if (!argumentsValue || Array.isArray(argumentsValue) || typeof argumentsValue !== 'object') throw new Error('object');
  } catch (failure) {
    if (failure?.message === UNSAFE_INTEGER_MESSAGE) throw failure;
    throw new Error('Representative arguments must be one valid JSON object.');
  }
  const validationOperation = value('validation-operation');
  if (!enabled.some(operation => operation.operationId === validationOperation)) {
    throw new Error('Select one enabled representative operation.');
  }
  return {
    project: {groupId: value('group-id'), artifactId: value('artifact-id'), packageName: value('package-name')},
    provider: value('provider-name'), domain: value('domain-name'), targetProfileId: value('target-profile'),
    validationLevel: 'MCP_PROTOCOL', validation: {toolCall: {operationId: validationOperation, arguments: argumentsValue}},
    operations: enabled.map(operation => operationConfiguration(operation))
  };
}

function operationConfiguration(operation) {
  const parameters = Object.fromEntries(operation.parameters.map(parameter => [parameter.name, {
    source: parameter.source,
    ...(parameter.source === 'SERVER_SECRET' ? {environmentVariable: parameter.environmentVariable.trim()} : {})
  }]));
  const policy = {...operation.responseNormalization};
  try {
    policy.successValues = parseSafeJson(policy.successValuesText);
    if (!Array.isArray(policy.successValues)) throw new Error('array');
  } catch (failure) {
    if (failure?.message === UNSAFE_INTEGER_MESSAGE) throw failure;
    throw new Error('Success values must be one valid JSON array.');
  }
  delete policy.successValuesText;
  const responseNormalization = Object.fromEntries(Object.entries(policy).filter(([, value]) =>
    Array.isArray(value) ? value.length > 0 : value !== ''));
  if (!['GENERIC_JSON', 'TYPED'].includes(operation.outputMode)) {
    throw new Error('Output mode is unsupported.');
  }
  const retry = operation.retry.enabled ? retryConfiguration(operation.retry) : null;
  const pagination = operation.pagination.enabled ? paginationConfiguration(operation.pagination) : null;
  return {
    operationId: operation.operationId, enabled: true, toolName: operation.toolName,
    toolDescription: operation.toolDescription, parameters,
    output: {mode: operation.outputMode},
    ...(Object.keys(responseNormalization).length ? {responseNormalization} : {}),
    ...(operation.retry.enabled ? {retry: retry} : {}),
    ...(operation.pagination.enabled ? {pagination: pagination} : {})
  };
}

function retryConfiguration(retry) {
  const statusCodes = normalizeRetryStatusCodes(retry.statusCodesText);
  if (!retry.networkErrors && statusCodes.length === 0) {
    throw new Error('Retry needs at least one HTTP status code or network errors enabled.');
  }
  const initialBackoffMillis = boundedInteger(retry.initialBackoffMillis, 1, 5000,
    'Retry initial backoff is out of range.');
  const maxBackoffMillis = boundedInteger(retry.maxBackoffMillis, initialBackoffMillis, 10000,
    'Retry maximum backoff is out of range.');
  return {
    statusCodes, networkErrors: retry.networkErrors,
    maxRetries: boundedInteger(retry.maxRetries, 1, 3, 'Retry count is out of range.'),
    initialBackoffMillis, maxBackoffMillis, respectRetryAfter: retry.respectRetryAfter
  };
}

function paginationConfiguration(paginationState) {
  const pagination = {
    requestParameter: paginationState.requestParameter,
    initialValue: parsePaginationInitialValue(paginationState.initialValueText),
    itemsPath: paginationState.itemsPath,
    nextValuePath: paginationState.nextValuePath,
    maxPages: boundedInteger(paginationState.maxPages, 2, 20, 'Pagination page limit is out of range.'),
    maxItems: boundedInteger(paginationState.maxItems, 1, 2000, 'Pagination item limit is out of range.')
  };
  if (!pagination.requestParameter || !pagination.itemsPath || !pagination.nextValuePath) {
    throw new Error('Pagination parameter and JSON Pointers are required.');
  }
  if (pagination.initialValue === undefined) delete pagination.initialValue;
  return pagination;
}

function normalizeRetryStatusCodes(source) {
  const tokens = source.split(',').map(token => token.trim()).filter(Boolean);
  if (tokens.length > 16 || tokens.some(token => !/^\d{3}$/.test(token))) {
    throw new Error('Retry status codes must be unique HTTP error integers.');
  }
  const values = [...new Set(tokens.map(Number))].sort((left, right) => left - right);
  if (values.some(value => value < 400 || value > 599)) {
    throw new Error('Retry status codes must be unique HTTP error integers.');
  }
  return values;
}

function parsePaginationInitialValue(source) {
  if (!source.trim()) return undefined;
  let value;
  try {
    value = parseSafeJson(source);
  } catch (failure) {
    if (failure?.message === UNSAFE_INTEGER_MESSAGE) throw failure;
    throw new Error('Pagination initial value must be one JSON string or integer.');
  }
  if (typeof value === 'string' && value.length > 0 && value.length <= 2048) return value;
  if (typeof value === 'number' && Number.isSafeInteger(value)) return value;
  throw new Error('Pagination initial value must be one JSON string or integer.');
}

function boundedInteger(value, minimum, maximum, message) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) throw new Error(message);
  return value;
}

const UNSAFE_INTEGER_MESSAGE = 'JSON integers must stay within the JavaScript safe integer range.';

function parseSafeJson(source) {
  const parsed = JSON.parse(source);
  requireSafeIntegers(parsed);
  return parsed;
}

function requireSafeIntegers(value) {
  if (typeof value === 'number' && Number.isInteger(value) && !Number.isSafeInteger(value)) {
    throw new Error(UNSAFE_INTEGER_MESSAGE);
  }
  if (Array.isArray(value)) {
    value.forEach(requireSafeIntegers);
  } else if (value && typeof value === 'object') {
    Object.values(value).forEach(requireSafeIntegers);
  }
}
