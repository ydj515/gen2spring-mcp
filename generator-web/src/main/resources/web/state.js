const SPECIFICATION_KEY = 'gen2spring.specificationId';
const JOB_KEY = 'gen2spring.jobId';

let state = Object.freeze({
  specificationId: sessionStorage.getItem(SPECIFICATION_KEY),
  jobId: sessionStorage.getItem(JOB_KEY),
  analysis: null,
  operations: [],
  selectedOperationId: null,
  profiles: [],
  preview: null,
  job: null
});

const listeners = new Set();

export function getState() { return state; }

export function updateState(patch) {
  state = Object.freeze({...state, ...patch});
  if (Object.hasOwn(patch, 'specificationId')) {
    if (patch.specificationId) sessionStorage.setItem(SPECIFICATION_KEY, patch.specificationId);
    else sessionStorage.removeItem(SPECIFICATION_KEY);
  }
  if (Object.hasOwn(patch, 'jobId')) {
    if (patch.jobId) sessionStorage.setItem(JOB_KEY, patch.jobId);
    else sessionStorage.removeItem(JOB_KEY);
  }
  listeners.forEach(listener => listener(state));
  return state;
}

export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function analyzedOperations(operations) {
  return operations.map(operation => ({
    ...operation,
    enabled: operation.supported,
    toolName: safeToolName(operation.operationId),
    toolDescription: operation.summary || operation.description || `Call ${operation.operationId}`,
    parameters: operation.parameters.map(parameter => ({
      ...parameter, source: 'USER_INPUT', environmentVariable: ''
    })),
    responseNormalization: {
      dataPath: '', successCodePath: '', successValues: [], successValuesText: '[]',
      errorMessagePath: '', totalCountPath: ''
    }
  }));
}

function safeToolName(operationId) {
  const value = operationId.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '').toLowerCase();
  const prefixed = /^[a-z]/.test(value) ? value : `tool_${value}`;
  return (prefixed || 'generated_tool').slice(0, 64);
}
