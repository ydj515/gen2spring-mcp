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
