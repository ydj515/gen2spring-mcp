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
const STAGE_DESCRIPTIONS = {
  ANALYZE: 'OpenAPI 문서와 입력 구성을 확인합니다.',
  GENERATE: '선택한 Endpoint를 기반으로 프로젝트 코드를 생성합니다.',
  COMPILE: '생성된 프로젝트를 Gradle로 컴파일합니다.',
  APPLICATION_CONTEXT: 'Spring 애플리케이션 컨텍스트를 기동합니다.',
  MCP_INITIALIZE: 'MCP 서버 연결과 초기화를 확인합니다.',
  MCP_TOOLS_LIST: '생성된 Tool 목록과 메타데이터를 검증합니다.',
  MCP_TOOL_CALL: '대표 Tool을 실제 인자로 호출해 검증합니다.',
  PACKAGE: '다운로드할 프로젝트와 검증 산출물을 패키징합니다.'
};
const SETTLED = ['SUCCESS', 'SKIPPED'];
const PROGRESS_GROUPS = Object.freeze([
  {key: 'PREPARE', label: '준비', stages: ['ANALYZE']},
  {key: 'GENERATE', label: '프로젝트 생성', stages: ['GENERATE']},
  {key: 'START', label: '컴파일 및 기동', stages: ['COMPILE', 'APPLICATION_CONTEXT']},
  {key: 'VERIFY', label: 'MCP 검증', stages: ['MCP_INITIALIZE', 'MCP_TOOLS_LIST', 'MCP_TOOL_CALL']},
  {key: 'PACKAGE', label: '패키징', stages: ['PACKAGE']}
]);
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

export function groupProgressStages(stages) {
  const entries = Array.isArray(stages) ? stages : [];
  return PROGRESS_GROUPS.map(group => {
    const members = group.stages.map(stage => entries.find(entry => entry.stage === stage)).filter(Boolean);
    let status = 'PENDING';
    if (members.some(entry => entry.status === 'FAILED')) status = 'FAILED';
    else if (members.some(entry => entry.status === 'RUNNING')) status = 'RUNNING';
    else if (members.length === group.stages.length && members.every(entry => SETTLED.includes(entry.status))) {
      status = members.every(entry => entry.status === 'SKIPPED') ? 'SKIPPED' : 'SUCCESS';
    }
    return {...group, status, members};
  });
}

export function clearProgress() {
  region().hidden = true;
  document.querySelector('#progress-overview').replaceChildren();
  document.querySelector('#progress-list').replaceChildren();
  const fill = document.querySelector('#job-progress-fill');
  fill.style.width = '0%';
  fill.dataset.state = 'running';
  fill.parentElement.setAttribute('aria-valuenow', '0');
  document.querySelector('#job-progress-percent').textContent = '0%';
  document.querySelector('#job-current-task').textContent = '생성 작업 준비';
  document.querySelector('#job-current-task-description').textContent = '입력값과 프로젝트 구성을 확인하고 있습니다.';
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
  const groupedStages = groupProgressStages(stages);
  const settledGroups = completed ? groupedStages.length
    : groupedStages.filter(group => SETTLED.includes(group.status)).length;

  const percentage = Math.round((settled / stages.length) * 100);
  const fill = document.querySelector('#job-progress-fill');
  fill.style.width = `${percentage}%`;
  fill.dataset.state = failed ? 'failed' : 'running';
  fill.parentElement.setAttribute('aria-valuenow', String(percentage));
  document.querySelector('#job-progress-percent').textContent = `${percentage}%`;
  document.querySelector('#job-progress-value').textContent = `${settledGroups} / ${groupedStages.length} 단계`;
  document.querySelector('#job-stage-label').textContent = failed
    ? `${label(failed.stage)} 단계에서 실패했습니다.`
    : running ? `${label(running.stage)} 진행 중입니다.`
    : settled === stages.length ? '모든 단계를 완료했습니다.'
    : '생성 작업을 준비하고 있습니다.';

  const current = failed ?? running ?? stages.find(entry => entry.status === 'PENDING') ?? stages.at(-1);
  document.querySelector('#job-current-task').textContent = failed
    ? `${label(failed.stage)} 실패`
    : settled === stages.length ? '모든 생성 작업 완료'
    : current ? label(current.stage) : '생성 작업 준비';
  document.querySelector('#job-current-task-description').textContent = failed
    ? '상세 로그를 확인한 뒤 설정으로 돌아가 다시 실행하세요.'
    : settled === stages.length ? '검증된 산출물을 아래에서 내려받을 수 있습니다.'
    : current ? STAGE_DESCRIPTIONS[current.stage] ?? `${label(current.stage)} 작업을 처리하고 있습니다.`
    : '입력값과 프로젝트 구성을 확인하고 있습니다.';

  document.querySelector('#progress-overview').replaceChildren(...groupedStages.map((group, index) => {
    const item = document.createElement('li');
    item.dataset.status = group.status;
    const marker = document.createElement('span');
    marker.className = 'pipeline-marker';
    marker.setAttribute('aria-hidden', 'true');
    if (group.status === 'PENDING') {
      marker.textContent = String(index + 1);
    } else {
      const icon = document.createElement('i');
      icon.className = stageIcon(group.status);
      marker.append(icon);
    }
    const content = document.createElement('span');
    content.className = 'pipeline-copy';
    const title = document.createElement('strong');
    title.textContent = group.label;
    const status = document.createElement('small');
    status.textContent = STAGE_STATUS_LABELS[group.status] ?? group.status;
    content.append(title, status);
    item.append(marker, content);
    return item;
  }));

  document.querySelector('#progress-list').replaceChildren(...stages.map(entry => {
    const item = document.createElement('li');
    item.dataset.status = entry.status;
    const icon = document.createElement('i');
    icon.className = stageIcon(entry.status);
    icon.setAttribute('aria-hidden', 'true');
    const stage = document.createElement('span');
    stage.className = 'pipeline-stage';
    stage.textContent = label(entry.stage);
    const description = document.createElement('span');
    description.className = 'pipeline-description';
    description.textContent = STAGE_DESCRIPTIONS[entry.stage] ?? `${label(entry.stage)} 작업`;
    const status = document.createElement('span');
    status.className = 'pipeline-status';
    status.textContent = STAGE_STATUS_LABELS[entry.status] ?? entry.status;
    item.append(icon, stage, description, status);
    return item;
  }));
}

function stageIcon(status) {
  if (status === 'SUCCESS') return 'bi bi-check-circle-fill';
  if (status === 'FAILED') return 'bi bi-x-circle-fill';
  if (status === 'RUNNING') return 'bi bi-arrow-clockwise pipeline-spinner';
  if (status === 'SKIPPED') return 'bi bi-dash-circle';
  return 'bi bi-circle';
}
