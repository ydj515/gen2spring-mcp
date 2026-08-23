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
  if (!response.ok) throw new Error('요청을 안전하게 처리하지 못했습니다.');
  return response.json();
}

function showFeedback(message, state) {
  feedback.textContent = message;
  feedback.dataset.feedbackState = state;
}

document.querySelector('#hosted-import-form')?.addEventListener('submit', async event => {
  event.preventDefault();
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  showFeedback('OpenAPI 문서를 가져오고 있습니다.', 'pending');
  try {
    const result = await submit('/api/specifications/imports', JSON.stringify({ url: event.target.url.value }), 'application/json');
    showFeedback(`가져오기 작업을 등록했습니다. 작업 ID: ${result.jobId}`, 'success');
  } catch (failure) {
    showFeedback(failure.message, 'error');
  } finally {
    button.disabled = false;
  }
});
