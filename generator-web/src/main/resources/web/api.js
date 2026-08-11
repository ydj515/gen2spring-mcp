const token = document.querySelector('meta[name="generator-api-token"]')?.content ?? '';

async function request(path, options = {}) {
  const headers = new Headers(options.headers ?? {});
  headers.set('X-Gen2Spring-Token', token);
  const response = await fetch(path, {...options, headers, cache: 'no-store'});
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
  const response = await request('/api/specifications', {
    method: 'POST',
    headers: {'Content-Type': 'application/octet-stream', 'X-Specification-Name': file.name},
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
