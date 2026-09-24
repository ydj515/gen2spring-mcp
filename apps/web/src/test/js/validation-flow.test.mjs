import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {SourceTextModule, SyntheticModule, createContext} from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/app.js', import.meta.url), 'utf8');
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
};

async function editor() {
  const elements = new Map();
  let document;
  class Element {
    value = 'test'; hidden = false; disabled = false; textContent = ''; dataset = {};
    children = []; attributes = {}; handlers = new Map();
    addEventListener(name, handler) {
      this.handlers.set(name, [...(this.handlers.get(name) ?? []), handler]);
    }
    async fire(name, event = {}) {
      for (const handler of this.handlers.get(name) ?? []) await handler(event);
    }
    dispatchEvent(event) { return this.fire(event.type, event); }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    setAttribute(name, value) { this.attributes[name] = value; }
    toggleAttribute(name, enabled) { if (enabled) this.attributes[name] = ''; else delete this.attributes[name]; }
    closest() { return null; }
    focus() { document.activeElement = this; }
  }
  const element = id => {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
  };
  const implementationButtons = [element('implementation-annotations'), element('implementation-sdk')];
  implementationButtons[0].dataset.implementation = 'SPRING_AI_ANNOTATIONS';
  implementationButtons[1].dataset.implementation = 'MCP_JAVA_SDK';
  document = {querySelector: selector => element(selector.slice(1)),
    querySelectorAll: selector => selector === '[data-implementation]' ? implementationButtons : [],
    createElement: () => new Element(), activeElement: null};
  element('validation-operation').value = 'representative';
  let state = {
    specificationId: 'spec', analysis: {openApiVersion: '3.1.2'}, profiles: [], jobId: null, preview: null,
    operations: [{operationId: 'representative', enabled: true, endpointSelected: true, method: 'GET', path: '/items',
      parameters: [{name: 'limit', location: 'QUERY', source: 'USER_INPUT', schema: {type: 'integer'}}]}]
  };
  const requests = [], jobs = [], profileRequest = deferred();
  let configurationError;
  const modules = {
    './api.js': {hostedMode: false, profiles: () => profileRequest.promise,
      preview: () => {const request = deferred(); requests.push(request); return request.promise;},
      startJob: () => {const job = deferred(); jobs.push(job); return job.promise;}},
    './state.js': {getState: () => state, updateState: patch => {state = {...state, ...patch}; return state;}},
    './editor.js': {buildConfiguration: () => {if (configurationError) throw configurationError; return {};}, initializeEditor: () => {}, renderOperations: () => {}},
    './upload.js': {initializeUpload: () => ({})},
    './operations.js': {initializeOperations: () => {}},
    './wizard.js': {initializeWizard: () => ({syncGate() {}, goToStep() {}})},
    './progress.js': {clearProgress() {}, renderProgress() {}, stateLabel: value => value}
  };
  const context = createContext({document, window: {location: {search: ''}}, setTimeout, clearTimeout, URLSearchParams,
    Event: class { constructor(type) { this.type = type; } }});
  const app = new SourceTextModule(source, {context});
  await app.link(specifier => {
    const exports = modules[specifier];
    return new SyntheticModule(Object.keys(exports), function () {
      for (const [name, value] of Object.entries(exports)) this.setExport(name, value);
    }, {context});
  });
  await app.evaluate();
  return {element, requests, jobs, profileRequest, activeElement: () => document.activeElement,
    state: () => state, failConfiguration: () => {configurationError = new Error('Invalid JSON');}};
}
const preview = {tools: [{operationId: 'representative', name: 'representative', inputSchema: {properties: {}}}]};

test('each MCP implementation keeps its selected generation profile when switching tabs', async () => {
  const app = await editor();
  const annotations = 'SPRING_AI_ANNOTATIONS';
  const sdk = 'MCP_JAVA_SDK';
  const profile = (id, implementations, version, javaVersion) => ({
    id, mcpImplementations: implementations, springAiVersion: version, javaVersion,
    springBootVersion: version === '1.1.8' ? '3.5.16' : '4.1.0',
    buildTool: {type: 'GRADLE'}, webStack: 'MVC', programmingModel: 'SYNC'
  });
  app.element('mcp-implementation').value = annotations;
  app.profileRequest.resolve({profiles: [
    profile('spring-ai-2.0-java21-mvc-streamable', [annotations], '2.0.0', 21),
    profile('spring-ai-2.0-java17-mvc-streamable', [annotations], '2.0.0', 17),
    profile('spring-ai-1.1-java21-mvc-streamable', [annotations, sdk], '1.1.8', 21),
    profile('spring-ai-1.1-java17-mvc-streamable', [annotations, sdk], '1.1.8', 17)
  ]});
  await new Promise(resolve => setImmediate(resolve));

  app.element('target-profile').value = 'spring-ai-2.0-java17-mvc-streamable';
  await app.element('target-profile').fire('change');
  app.element('mcp-implementation').value = sdk;
  await app.element('mcp-implementation').fire('change');
  app.element('target-profile').value = 'spring-ai-1.1-java17-mvc-streamable';
  await app.element('target-profile').fire('change');

  app.element('mcp-implementation').value = annotations;
  await app.element('mcp-implementation').fire('change');
  assert.equal(app.element('target-profile').value, 'spring-ai-2.0-java17-mvc-streamable');
  app.element('mcp-implementation').value = sdk;
  await app.element('mcp-implementation').fire('change');
  assert.equal(app.element('target-profile').value, 'spring-ai-1.1-java17-mvc-streamable');
});

test('MCP choice buttons synchronize the control and invalidate a verified configuration', async () => {
  const app = await editor();
  await validate(app);
  await app.element('implementation-sdk').fire('click');
  assert.equal(app.element('mcp-implementation').value, 'MCP_JAVA_SDK');
  assert.equal(app.element('implementation-sdk').attributes['aria-pressed'], 'true');
  assert.equal(app.element('implementation-annotations').attributes['aria-pressed'], 'false');
  assert.equal(app.element('generate-button').disabled, true);
  app.element('mcp-implementation').value = 'SPRING_AI_ANNOTATIONS';
  await app.element('mcp-implementation').fire('change');
  assert.equal(app.element('implementation-sdk').attributes['aria-pressed'], 'false');
  assert.equal(app.element('implementation-annotations').attributes['aria-pressed'], 'true');
});
async function validate(app) {
  const pending = app.element('preview-button').fire('click');
  app.requests.at(-1).resolve(preview);
  await pending;
}

test('validation exposes generation only after success and keeps focus on the next action', async () => {
  const app = await editor();
  app.element('preview-button').focus();
  const pending = app.element('preview-button').fire('click');
  assert.equal(app.element('validation-overall').textContent, '검증 중');
  assert.equal(app.element('generate-button').hidden, true);
  await app.element('preview-button').fire('click');
  assert.equal(app.requests.length, 1);
  app.requests[0].resolve(preview);
  await pending;
  assert.equal(app.element('validation-overall').textContent, '검증 완료');
  assert.equal(app.element('generate-button').hidden, false);
  assert.equal(app.element('generate-button').disabled, false);
  assert.equal(app.element('preview-button').hidden, false);
  assert.equal(app.element('preview-button-label').textContent, '다시 검증');
  assert.equal(app.element('generation-stage').attributes['aria-current'], 'step');
  assert.equal(app.activeElement(), app.element('generate-button'));
});

test('input invalidates success immediately, before blur', async () => {
  const app = await editor();
  await validate(app);
  await app.element('validation-arguments').fire('input');
  assert.equal(app.state().preview, null);
  assert.equal(app.element('generate-button').hidden, true);
  assert.equal(app.element('preview-button').disabled, false);
  assert.equal(app.element('validation-overall').textContent, '재검증 필요');
});

test('outdated validation cannot unlock generation after input changes', async () => {
  const app = await editor();
  const pending = app.element('preview-button').fire('click');
  await app.element('validation-arguments').fire('input');
  app.requests[0].resolve(preview);
  await pending;
  assert.equal(app.state().preview, null);
  assert.equal(app.element('generate-button').disabled, true);
  assert.equal(app.element('validation-overall').textContent, '재검증 필요');
});

test('failed validation supports retry without exposing generation', async () => {
  const app = await editor();
  const pending = app.element('preview-button').fire('click');
  app.requests[0].reject(new Error('Unavailable'));
  await pending;
  assert.equal(app.element('validation-overall').textContent, '수정 필요');
  assert.equal(app.element('preview-button-label').textContent, '다시 검증하기');
  assert.equal(app.element('preview-button').disabled, false);
  assert.equal(app.element('generate-button').hidden, true);
  await validate(app);
  assert.equal(app.element('generate-button').disabled, false);
});

test('configuration errors and missing representative tools cannot unlock generation', async () => {
  const invalid = await editor();
  invalid.failConfiguration();
  await invalid.element('preview-button').fire('click');
  assert.equal(invalid.requests.length, 0);
  assert.equal(invalid.element('validation-overall').textContent, '수정 필요');
  const missing = await editor();
  const pending = missing.element('preview-button').fire('click');
  missing.requests[0].resolve({tools: []});
  await pending;
  assert.equal(missing.state().preview, null);
  assert.equal(missing.element('generate-button').hidden, true);
  assert.equal(missing.element('validation-overall').textContent, '수정 필요');
});

test('generation ignores double clicks and concurrent requests', async () => {
  const app = await editor();
  await validate(app);
  await app.element('generate-button').fire('click', {detail: 2});
  assert.equal(app.jobs.length, 0);
  await app.element('generate-button').fire('click', {detail: 1});
  await app.element('generate-button').fire('click', {detail: 1});
  assert.equal(app.jobs.length, 1);
  assert.equal(app.element('generate-button').disabled, true);
  app.jobs[0].reject(new Error('Unavailable'));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(app.element('generate-button').disabled, false);
});

test('parameter summary reads the schema type, not a missing top-level type', async () => {
  const app = await editor();
  const text = app.element('validation-parameter-summary').children[0].children[0].children[1].textContent;
  assert.equal(text, '쿼리 · integer · 선택');
});

test('an outdated failure cannot replace a newer successful validation', async () => {
  const app = await editor();
  const stale = app.element('preview-button').fire('click');
  await app.element('validation-arguments').fire('input');
  await validate(app);
  app.requests[0].reject(new Error('Old failure'));
  await stale;
  assert.equal(app.element('validation-overall').textContent, '검증 완료');
  assert.equal(app.element('generate-button').disabled, false);
});

test('missing and union schema types remain readable', async () => {
  const app = await editor();
  const parameter = app.state().operations[0].parameters[0];
  for (const [schema, expected] of [[{}, '타입 정보 없음'], [{type: ['string', 'null']}, 'string / null']]) {
    parameter.schema = schema;
    await app.element('validation-operation').fire('change');
    const text = app.element('validation-parameter-summary').children[0].children[0].children[1].textContent;
    assert.equal(text, `쿼리 · ${expected} · 선택`);
  }
});

test('MCP implementation changes invalidate validation and discard stale responses', async () => {
  const app = await editor();
  await validate(app);
  app.element('mcp-implementation').value = 'MCP_JAVA_SDK';
  await app.element('mcp-implementation').fire('change');
  assert.equal(app.state().preview, null);
  assert.equal(app.element('generate-button').hidden, true);
  const pending = app.element('preview-button').fire('click');
  app.element('mcp-implementation').value = 'SPRING_AI_ANNOTATIONS';
  await app.element('mcp-implementation').fire('change');
  app.requests.at(-1).resolve(preview);
  await pending;
  assert.equal(app.state().preview, null);
  assert.equal(app.element('generate-button').disabled, true);
});

test('revalidation disables generation until the new result succeeds', async () => {
  const app = await editor();
  await validate(app);
  const pending = app.element('preview-button').fire('click');
  assert.equal(app.element('generate-button').disabled, true);
  assert.equal(app.element('generate-button').hidden, true);
  assert.equal(app.element('preview-button').disabled, true);
  app.requests.at(-1).resolve(preview);
  await pending;
  assert.equal(app.element('generate-button').disabled, false);
  assert.equal(app.element('preview-button').disabled, false);
});
