const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content ?? '';
const csrfHeader = document.querySelector('meta[name="csrf-header"]')?.content ?? '';
const appMode = document.querySelector('meta[name="app-mode"]')?.content ?? 'local';
export const hostedMode = appMode === 'hosted';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);
let pendingGenerationRequest = null;

async function request(path, options = {}) {
  const method = (options.method ?? 'GET').toUpperCase();
  const headers = new Headers(options.headers ?? {});
  if (!SAFE_METHODS.has(method)) headers.set(csrfHeader, csrfToken);
  const response = await fetch(path, {...options, method, headers, cache: 'no-store'});
  if (!response.ok) {
    let failure = {code: 'REQUEST_FAILED', stage: 'WEB', message: 'The local request failed.'};
    try {
      const payload = await response.json();
      if (payload?.error) failure = payload.error;
    } catch {
      // Keep the fixed local failure.
    }
    throw failure;
  }
  return response;
}

export async function profiles() {
  return (await request('/api/profiles')).json();
}

export async function upload(file) {
  const hosted = appMode === 'hosted';
  const contentType = hosted
    ? (file.name.toLowerCase().endsWith('.json') ? 'application/json' : 'application/yaml')
    : 'application/octet-stream';
  const response = await request(hosted ? '/api/specifications/uploads' : '/api/specifications', {
    method: 'POST',
    headers: {'Content-Type': contentType, 'X-Specification-Name': file.name},
    body: file
  });
  return response.json();
}

export async function analysis(specificationId) {
  return (await request(`/api/specifications/${specificationId}/analysis`)).json();
}

export async function preview(specificationId, configuration) {
  const response = await request(`/api/specifications/${specificationId}/preview`, {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(configuration)
  });
  return response.json();
}

export async function startJob(specificationId, configuration) {
  if (hostedMode) {
    const body = JSON.stringify({specificationId, configuration});
    if (pendingGenerationRequest?.body !== body) {
      pendingGenerationRequest = {body, idempotencyKey: crypto.randomUUID()};
    }
    const response = await request('/api/jobs', {
      method: 'POST',
      headers: {'Content-Type': 'application/json', 'Idempotency-Key': pendingGenerationRequest.idempotencyKey},
      body
    });
    const payload = await response.json();
    pendingGenerationRequest = null;
    return {id: payload.jobId, state: payload.status, stages: [], downloads: []};
  }
  return (await request(`/api/specifications/${specificationId}/jobs`, {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(configuration)
  })).json();
}

// Shared by both transports. Hosted sends its own record shape and local sends
// the flat snapshot, so this is the single place either becomes the shape the
// presentation code expects.
function normalizeJob(payload) {
  if (!hostedMode) return payload;
  const stages = (payload.events ?? []).map(event => ({
    stage: event.stage,
    status: event.status,
    summary: event.summary
  }));
  const latest = stages.at(-1);
  return {
    id: payload.id,
    state: payload.status,
    currentStage: latest?.stage ?? null,
    stages,
    downloads: (payload.artifacts ?? []).map(artifact => ({
      name: artifact.type.toLowerCase(),
      artifactId: artifact.id
    })),
    error: payload.status === 'FAILED' ? {message: latest?.summary ?? 'Generation failed safely.'} : null
  };
}

export async function job(jobId) {
  return normalizeJob(await (await request(`/api/jobs/${jobId}`)).json());
}

export function jobEvents(jobId, {onSnapshot, onDone, onFailure}) {
  const source = new EventSource(`/api/jobs/${jobId}/events`);
  source.addEventListener('snapshot', message => {
    try {
      onSnapshot(normalizeJob(JSON.parse(message.data)));
    } catch {
      onFailure();
    }
  });
  source.addEventListener('done', () => {
    source.close();
    onDone();
  });
  source.addEventListener('error', () => {
    // A reconnecting EventSource is still healthy. Only a closed one is fatal,
    // so the browser's own retry gets its chance before we hand off to polling.
    if (source.readyState === EventSource.CLOSED) onFailure();
  });
  return {close: () => source.close()};
}

export function downloadUrl(jobId, artifact) {
  if (hostedMode) return `/api/artifacts/${artifact.artifactId}/content`;
  return `/api/jobs/${jobId}/${artifact}`;
}

export async function download(jobId, artifact) {
  return request(downloadUrl(jobId, artifact));
}

export async function deleteJob(jobId) {
  if (hostedMode) {
    await request(`/api/jobs/${jobId}/cancellation`, {method: 'POST'});
    return;
  }
  await request(`/api/jobs/${jobId}`, {method: 'DELETE'});
}
