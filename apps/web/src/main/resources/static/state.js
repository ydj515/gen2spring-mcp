const SPECIFICATION_KEY = 'gen2spring.specificationId';
const JOB_KEY = 'gen2spring.jobId';
const STEP_KEY = 'gen2spring.currentStep';

let state = Object.freeze({
  specificationId: sessionStorage.getItem(SPECIFICATION_KEY),
  jobId: sessionStorage.getItem(JOB_KEY),
  currentStep: Number(sessionStorage.getItem(STEP_KEY)) || 1,
  analysis: null,
  operations: [],
  selectedOperationId: null,
  profiles: [],
  compatibilityNotices: [],
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
  if (Object.hasOwn(patch, 'currentStep')) {
    sessionStorage.setItem(STEP_KEY, String(patch.currentStep));
  }
  listeners.forEach(listener => listener(state));
  return state;
}

export function subscribe(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function resetSpecificationState() {
  return updateState({
    specificationId: null,
    jobId: null,
    currentStep: 1,
    analysis: null,
    operations: [],
    selectedOperationId: null,
    preview: null,
    job: null
  });
}

export function analyzedOperations(operations) {
  return operations.map((operation, index) => {
    const defaultToolName = operation.operationId ? safeToolName(operation.operationId) : '';
    const defaultToolDescription = operation.summary || operation.description
      || (operation.operationId ? `Call ${operation.operationId}` : 'Unsupported endpoint');
    return {
      ...operation,
      sourceIndex: index,
      enabled: operation.supported,
      defaultToolName,
      defaultToolDescription,
      toolName: defaultToolName,
      toolDescription: defaultToolDescription,
      outputMode: 'GENERIC_JSON',
      retry: {
        enabled: false, statusCodes: [], statusCodesText: '', networkErrors: false,
        maxRetries: 1, initialBackoffMillis: 100, maxBackoffMillis: 1000, respectRetryAfter: true
      },
      pagination: {
        enabled: false, requestParameter: '', initialValue: null, initialValueText: '',
        itemsPath: '/items', nextValuePath: '/next', maxPages: 10, maxItems: 1000
      },
      parameters: operation.parameters.map(parameter => ({
        ...parameter, source: 'USER_INPUT', environmentVariable: ''
      })),
      responseNormalization: {
        dataPath: '', successCodePath: '', successValues: [], successValuesText: '[]',
        errorMessagePath: '', totalCountPath: ''
      }
    };
  });
}

function safeToolName(operationId) {
  const value = operationId.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '').toLowerCase();
  const prefixed = /^[a-z]/.test(value) ? value : `tool_${value}`;
  return (prefixed || 'generated_tool').slice(0, 64);
}
