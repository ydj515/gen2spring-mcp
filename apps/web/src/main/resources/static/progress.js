const STAGE_LABELS = {
  ANALYZE: 'OpenAPI 문서 분석',
  GENERATE: '프로젝트 코드 생성',
  COMPILE: 'Gradle 컴파일',
  APPLICATION_CONTEXT: 'Spring 컨텍스트 기동',
  MCP_INITIALIZE: 'MCP 서버 초기화',
  MCP_TOOLS_LIST: 'Tool 목록 검증',
  MCP_TOOL_CALL: '대표 Tool 호출 검증',
  PACKAGE: '산출물 패키징'
};
const SETTLED = ['SUCCESS', 'SKIPPED'];
// Local mode reports VALIDATED and UNVERIFIED; hosted mode reports SUCCEEDED
// and CANCELLED. The union is covered so neither leaks a raw constant.
const JOB_STATE_LABELS = {
  QUEUED: '대기 중입니다.',
  RUNNING: '생성 중입니다.',
  VALIDATED: '검증까지 완료했습니다.',
  UNVERIFIED: '생성했지만 검증하지 못했습니다.',
  SUCCEEDED: '완료했습니다.',
  FAILED: '실패했습니다.',
  CANCELLED: '취소했습니다.'
};

const region = () => document.querySelector('#job-progress');

// An unrecognized stage degrades to its raw constant so a future pipeline
// stage renders plainly instead of showing undefined.
export function label(stage) {
  return STAGE_LABELS[stage] ?? stage;
}

export function stateLabel(state) {
  return JOB_STATE_LABELS[state] ?? state;
}

export function clearProgress() {
  region().hidden = true;
  document.querySelector('#progress-list').replaceChildren();
  const fill = document.querySelector('#job-progress-fill');
  fill.style.width = '0%';
  fill.dataset.state = 'running';
  document.querySelector('#job-progress-details').open = false;
}

export function renderProgress(snapshot) {
  const stages = snapshot.stages ?? [];
  if (stages.length === 0) {
    clearProgress();
    return;
  }
  region().hidden = false;

  const failedIndex = stages.findIndex(entry => entry.status === 'FAILED');
  const failed = failedIndex === -1 ? undefined : stages[failedIndex];
  const running = stages.find(entry => entry.status === 'RUNNING');
  // A failure marks every later stage SKIPPED. Counting those as settled would
  // render a build that died at stage 3 as 88% complete, so on failure only the
  // stages that actually settled before it count.
  const counted = failed ? stages.slice(0, failedIndex) : stages;
  const settled = counted.filter(entry => SETTLED.includes(entry.status)).length;

  const fill = document.querySelector('#job-progress-fill');
  fill.style.width = `${Math.round((settled / stages.length) * 100)}%`;
  fill.dataset.state = failed ? 'failed' : 'running';
  document.querySelector('#job-progress-value').textContent = `${settled} / ${stages.length}`;
  document.querySelector('#job-stage-label').textContent = failed
    ? `${label(failed.stage)} 단계에서 실패했습니다.`
    : running ? `${label(running.stage)} 진행 중입니다.`
    : settled === stages.length ? '모든 단계를 완료했습니다.'
    : '생성 작업을 준비하고 있습니다.';

  if (failed) document.querySelector('#job-progress-details').open = true;

  document.querySelector('#progress-list').replaceChildren(...stages.map(entry => {
    const item = document.createElement('li');
    item.dataset.status = entry.status;
    item.textContent = `${label(entry.stage)} — ${entry.status}`;
    return item;
  }));
}
