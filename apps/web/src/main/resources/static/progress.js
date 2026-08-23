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
const STAGE_STATUS_LABELS = {
  PENDING: '대기',
  RUNNING: '진행 중',
  SUCCESS: '완료',
  FAILED: '실패',
  SKIPPED: '건너뜀'
};
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
  document.querySelector('#job-progress-percent').textContent = '0%';
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
  // Hosted timeline events use job-transition statuses rather than local stage
  // statuses. A terminal SUCCEEDED snapshot settles the whole observed timeline.
  const completed = snapshot.state === 'SUCCEEDED';
  const settled = completed ? stages.length
    : counted.filter(entry => SETTLED.includes(entry.status)).length;

  const percentage = Math.round((settled / stages.length) * 100);
  const fill = document.querySelector('#job-progress-fill');
  fill.style.width = `${percentage}%`;
  fill.dataset.state = failed ? 'failed' : 'running';
  document.querySelector('#job-progress-percent').textContent = `${percentage}%`;
  document.querySelector('#job-progress-value').textContent = `${settled} / ${stages.length}`;
  document.querySelector('#job-stage-label').textContent = failed
    ? `${label(failed.stage)} 단계에서 실패했습니다.`
    : running ? `${label(running.stage)} 진행 중입니다.`
    : settled === stages.length ? '모든 단계를 완료했습니다.'
    : '생성 작업을 준비하고 있습니다.';

  document.querySelector('#progress-list').replaceChildren(...stages.map(entry => {
    const item = document.createElement('li');
    item.dataset.status = entry.status;
    const icon = document.createElement('i');
    icon.className = stageIcon(entry.status);
    icon.setAttribute('aria-hidden', 'true');
    const stage = document.createElement('span');
    stage.className = 'pipeline-stage';
    stage.textContent = label(entry.stage);
    const status = document.createElement('span');
    status.className = 'pipeline-status';
    status.textContent = STAGE_STATUS_LABELS[entry.status] ?? entry.status;
    item.append(icon, stage, status);
    return item;
  }));
}

function stageIcon(status) {
  if (status === 'SUCCESS') return 'bi bi-check-circle-fill';
  if (status === 'FAILED') return 'bi bi-x-circle-fill';
  if (status === 'RUNNING') return 'bi bi-arrow-repeat';
  if (status === 'SKIPPED') return 'bi bi-dash-circle';
  return 'bi bi-circle';
}
