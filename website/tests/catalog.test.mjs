import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { renderFeatureCatalog } from '../scripts/feature-catalog.mjs';
const catalog = JSON.parse(await readFile(new URL('../content/features.json', import.meta.url), 'utf8'));

test('the reviewed app catalog retains all 47 source cards, once each', () => {
  const features = catalog.groups.flatMap(group => group.features);
  assert.equal(features.length, 47);
  assert.equal(new Set(features.map(feature => feature.sourceTitle)).size, 47);
  assert.equal(catalog.groups.length, 7);
  for (const feature of features) {
    const expected = feature.sourceStage === 'NOW' ? 'current' : feature.sourceStage === 'IDEA' ? 'exploring' : 'planned';
    assert.equal(feature.status, expected, feature.sourceTitle);
  }
  assert.deepEqual(['current','planned','exploring'].map(status => features.filter(f => f.status === status).length), [24,19,4]);
});

test('planned device controls and ideas do not become current-build promises', () => {
  const features = catalog.groups.flatMap(group => group.features);
  for (const title of ['Find my phone','Phone controls','Short video clips','City tour','Per-agent permissions']) {
    assert.equal(features.find(feature => feature.sourceTitle === title).status, 'planned', title);
  }
  assert.equal(features.find(feature => feature.sourceTitle === 'Location history and places').status, 'exploring');
  const html = renderFeatureCatalog(catalog);
  assert.match(html, /There is no public download yet/);
  assert.equal((html.match(/<li>/g) || []).length, 47);
});
