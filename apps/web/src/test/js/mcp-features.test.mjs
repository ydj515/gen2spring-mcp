import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {SourceTextModule, SyntheticModule, createContext} from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/editor.js', import.meta.url), 'utf8');

test('buildConfiguration carries modern features and rejects invalid feature JSON', async () => {
  const values = {'mcp-implementation': 'MCP_JAVA_SDK', 'mcp-protocol': 'DUAL',
    'mcp-features': '{"tasks":{"enabled":true,"ttlMs":1000}}',
    'validation-arguments': '{}', 'validation-operation': 'weather'};
  const state = {specificationId: 'spec', operations: [{operationId: 'weather', enabled: true,
    supported: true, toolName: 'weather', toolDescription: 'Weather', parameters: [],
    outputMode: 'GENERIC_JSON', responseNormalization: {successValuesText: '[]'},
    retry: {enabled: false}, pagination: {enabled: false}}]};
  const context = createContext({document: {querySelector: selector => ({value: values[selector.slice(1)] ?? 'example'})}});
  const editor = new SourceTextModule(source, {context});
  await editor.link(() => new SyntheticModule(['getState', 'updateState'], function () {
    this.setExport('getState', () => state); this.setExport('updateState', () => {});
  }, {context}));
  await editor.evaluate();
  assert.equal(editor.namespace.buildConfiguration().mcpFeatures.tasks.ttlMs, 1000);
  values['mcp-features'] = '[]';
  assert.throws(() => editor.namespace.buildConfiguration(), /JSON/);
  values['mcp-features'] = '{"ttlMs":9007199254740993}';
  assert.throws(() => editor.namespace.buildConfiguration(), /JSON/);
  values['mcp-protocol'] = 'LEGACY';
  assert.equal(Object.keys(editor.namespace.buildConfiguration().mcpFeatures).length, 0);
});
