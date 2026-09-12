import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {SourceTextModule, SyntheticModule, createContext} from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/wizard.js', import.meta.url), 'utf8');

async function wizard() {
  class Element {
    value = 'valid'; hidden = false; disabled = false; dataset = {}; attributes = {};
    children = new Map(); scrollWidth = 100; clientWidth = 100;
    addEventListener() {}
    setAttribute(name, value) { this.attributes[name] = value; }
    removeAttribute(name) { delete this.attributes[name]; }
    querySelector(selector) {
      if (!this.children.has(selector)) this.children.set(selector, new Element());
      return this.children.get(selector);
    }
    closest() { return settings; }
    focus() {}
  }
  const settings = {open: false};
  const elements = new Map();
  const element = selector => {
    if (!elements.has(selector)) elements.set(selector, new Element());
    return elements.get(selector);
  };
  let state = {currentStep: 1, analysis: {}, jobId: null,
    operations: [{endpointSelected: true, enabled: true}]};
  let listener = () => {};
  const updateState = patch => { state = {...state, ...patch}; listener(); };
  const context = createContext({document: {querySelector: element},
    window: {location: {hash: ''}, matchMedia: () => ({matches: true}), addEventListener() {}}});
  const module = new SourceTextModule(source, {context});
  await module.link(() => new SyntheticModule(['getState', 'subscribe', 'updateState'], function () {
    this.setExport('getState', () => state);
    this.setExport('subscribe', value => { listener = value; });
    this.setExport('updateState', updateState);
  }, {context}));
  await module.evaluate();
  const api = module.namespace.initializeWizard();
  return {api, element, settings, updateState,
    chip: step => element(`.wizard-chip[data-step="${step}"]`)};
}

test('valid defaults unlock future steps without marking unvisited configuration complete', async () => {
  const app = await wizard();
  app.api.goToStep(2);
  assert.equal(app.chip(1).dataset.complete, 'true');
  assert.equal(app.chip(3).disabled, false);
  assert.equal(app.chip(3).dataset.complete, 'false');
  assert.equal(app.chip(5).disabled, true);
  app.api.goToStep(4);
  app.api.goToStep(2);
  assert.equal(app.chip(3).dataset.complete, 'true');
});

test('missing fields open collapsed project settings and invalidate later completion', async () => {
  const app = await wizard();
  app.api.goToStep(4);
  app.element('#artifact-id').value = '';
  app.api.goToStep(3);
  assert.equal(app.settings.open, true);
  assert.equal(app.element('#artifact-id').attributes['aria-invalid'], 'true');
  assert.equal(app.chip(4).disabled, true);
  assert.equal(app.chip(3).dataset.complete, 'false');
});

test('reset clears previous completion while a restored job only unlocks its result', async () => {
  const app = await wizard();
  app.api.goToStep(4);
  app.updateState({currentStep: 1, analysis: null, jobId: null, operations: []});
  app.updateState({analysis: {}, operations: [{endpointSelected: true, enabled: true}]});
  assert.equal(app.chip(3).dataset.complete, 'false');
  app.updateState({analysis: null, operations: [], jobId: 'retained-job'});
  assert.equal(app.chip(2).disabled, true);
  assert.equal(app.chip(5).disabled, false);
});

test('restoring an unloaded step does not expand settings before profile loading', async () => {
  const app = await wizard();
  app.element('#target-profile').value = '';
  app.updateState({currentStep: 3, analysis: null, operations: []});
  assert.equal(app.settings.open, false);
});
