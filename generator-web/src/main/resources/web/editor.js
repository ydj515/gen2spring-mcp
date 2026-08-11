import {getState, updateState} from './state.js';

const ids = names => Object.fromEntries(names.map(name => [name, document.querySelector(`#${name}`)]));
const elements = ids([
  'operation-filter', 'operation-list', 'operation-editor', 'operation-enabled', 'tool-name',
  'tool-description', 'parameter-editor', 'data-path', 'success-code-path', 'success-values',
  'error-message-path', 'total-count-path', 'validation-operation'
]);

export function initializeEditor(onDirty) {
  elements['operation-filter'].addEventListener('change', renderOperations);
  elements['operation-list'].addEventListener('click', event => {
    const button = event.target.closest('button[data-operation-id]');
    if (!button) return;
    updateState({selectedOperationId: button.dataset.operationId});
    renderOperations();
  });
  for (const id of ['operation-enabled', 'tool-name', 'tool-description', 'data-path',
    'success-code-path', 'success-values', 'error-message-path', 'total-count-path']) {
    elements[id].addEventListener('change', () => {
      saveSelectedOperation();
      onDirty();
    });
  }
  elements['parameter-editor'].addEventListener('change', event => {
    const control = event.target.closest('[data-parameter-name]');
    if (!control) return;
    saveSelectedOperation();
    renderSelectedOperation();
    onDirty();
  });
}

export function renderOperations() {
  const state = getState();
  const filter = elements['operation-filter'].value;
  const visible = state.operations.filter(operation => filter === 'all'
    || (filter === 'supported' && operation.supported)
    || (filter === 'unsupported' && !operation.supported));
  elements['operation-list'].replaceChildren(...visible.map(operation => {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.operationId = operation.operationId;
    button.setAttribute('aria-current', String(operation.operationId === state.selectedOperationId));
    button.textContent = `${operation.method} ${operation.path} — ${operation.operationId}`;
    item.append(button);
    return item;
  }));
  renderSelectedOperation();
  renderValidationOperations();
}

function renderSelectedOperation() {
  const operation = selectedOperation();
  elements['operation-editor'].disabled = !operation;
  if (!operation) return;
  elements['operation-enabled'].checked = operation.enabled;
  elements['tool-name'].value = operation.toolName;
  elements['tool-description'].value = operation.toolDescription;
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
  for (const value of ['USER_INPUT', 'SERVER_SECRET', 'SERVER_DEFAULT']) {
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
  const selected = selectedOperation();
  if (!selected) return;
  const parameterControls = [...elements['parameter-editor'].querySelectorAll('[data-parameter-name]')];
  const parameters = selected.parameters.map(parameter => {
    const controls = parameterControls.filter(control => control.dataset.parameterName === parameter.name);
    return {...parameter, ...Object.fromEntries(controls.map(control => [control.dataset.parameterField, control.value]))};
  });
  let successValues = selected.responseNormalization.successValues;
  try {
    successValues = JSON.parse(elements['success-values'].value);
    if (!Array.isArray(successValues)) throw new Error('array');
  } catch {
    // Preserve the raw value so the final strict build can reject it.
  }
  const replacement = {
    ...selected,
    enabled: elements['operation-enabled'].checked,
    toolName: elements['tool-name'].value.trim(),
    toolDescription: elements['tool-description'].value.trim(),
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
  updateState({operations: state.operations.map(operation =>
    operation.operationId === replacement.operationId ? replacement : operation)});
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
    argumentsValue = JSON.parse(value('validation-arguments'));
    if (!argumentsValue || Array.isArray(argumentsValue) || typeof argumentsValue !== 'object') throw new Error('object');
  } catch {
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
    policy.successValues = JSON.parse(policy.successValuesText);
    if (!Array.isArray(policy.successValues)) throw new Error('array');
  } catch {
    throw new Error('Success values must be one valid JSON array.');
  }
  delete policy.successValuesText;
  const responseNormalization = Object.fromEntries(Object.entries(policy).filter(([, value]) =>
    Array.isArray(value) ? value.length > 0 : value !== ''));
  return {
    operationId: operation.operationId, enabled: true, toolName: operation.toolName,
    toolDescription: operation.toolDescription, parameters,
    ...(Object.keys(responseNormalization).length ? {responseNormalization} : {})
  };
}
