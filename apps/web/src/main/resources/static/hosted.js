'use strict';

const token = document.querySelector('meta[name="csrf-token"]')?.content;
const header = document.querySelector('meta[name="csrf-header"]')?.content;
const feedback = document.querySelector('#hosted-feedback');

async function submit(path, body, contentType) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { [header]: token, 'Content-Type': contentType, 'Idempotency-Key': crypto.randomUUID() },
    body
  });
  if (!response.ok) throw new Error('The hosted request failed safely.');
  return response.json();
}

document.querySelector('#hosted-import-form')?.addEventListener('submit', async event => {
  event.preventDefault();
  try {
    const result = await submit('/api/specifications/imports', JSON.stringify({ url: event.target.url.value }), 'application/json');
    feedback.textContent = `Import queued: ${result.jobId}`;
  } catch (failure) { feedback.textContent = failure.message; }
});

document.querySelector('#hosted-upload-form')?.addEventListener('submit', async event => {
  event.preventDefault();
  try {
    const file = event.target.file.files[0];
    const type = file.name.endsWith('.json') ? 'application/json' : 'application/yaml';
    const result = await submit('/api/specifications/uploads', file, type);
    feedback.textContent = `Uploaded: ${result.id}`;
  } catch (failure) { feedback.textContent = failure.message; }
});

document.querySelector('#hosted-generation-form')?.addEventListener('submit', async event => {
  event.preventDefault();
  try {
    const configuration = JSON.parse(event.target.configuration.value);
    const result = await submit('/api/jobs', JSON.stringify({
      specificationId: event.target.specificationId.value,
      configuration
    }), 'application/json');
    feedback.textContent = `Generation queued: ${result.jobId}`;
  } catch (failure) { feedback.textContent = 'The generation configuration is invalid.'; }
});
