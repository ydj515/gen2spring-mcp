const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content ?? '';
const csrfHeader = document.querySelector('meta[name="csrf-header"]')?.content ?? '';
const appMode = document.querySelector('meta[name="app-mode"]')?.content ?? 'local';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

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

export async function preview(specificationId, configuration) {
  const response = await request(`/api/specifications/${specificationId}/preview`, {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(configuration)
  });
  return response.json();
}

export async function startJob(specificationId, configuration) {
  const response = await request(`/api/specifications/${specificationId}/jobs`, {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(configuration)
  });
  return response.json();
}

export async function job(jobId) {
  return (await request(`/api/jobs/${jobId}`)).json();
}

export function downloadUrl(jobId, name) {
  return `/api/jobs/${jobId}/${name}`;
}

export async function download(jobId, name) {
  return request(downloadUrl(jobId, name));
}

export async function deleteJob(jobId) {
  await request(`/api/jobs/${jobId}`, {method: 'DELETE'});
}
