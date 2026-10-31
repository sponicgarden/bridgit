import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { renderFeatureCatalog } from '../scripts/feature-catalog.mjs';
const catalog = JSON.parse(await readFile(new URL('../content/features.json', import.meta.url), 'utf8'));

test('the reviewed app catalog retains all 48 source cards, once each', () => {
  const features = catalog.groups.flatMap(group => group.features);
  assert.equal(features.length, 48);
  assert.equal(new Set(features.map(feature => feature.sourceTitle)).size, 48);
  assert.equal(catalog.groups.length, 7);
  for (const feature of features) {
    const expected = feature.sourceStage === 'NOW' ? 'current' : feature.sourceStage === 'IDEA' ? 'exploring' : 'planned';
    assert.equal(feature.status, expected, feature.sourceTitle);
  }
  assert.deepEqual(['current','planned','exploring'].map(status => features.filter(f => f.status === status).length), [45,0,3]);
});

test('ideas and unreleased stages do not become current-build promises', () => {
  const features = catalog.groups.flatMap(group => group.features);
  for (const feature of features.filter(f => f.sourceStage !== 'NOW')) assert.notEqual(feature.status, 'current', feature.sourceTitle);
  for (const title of ['Reach Bridgit without Tailscale (relay)','Messages, contacts, calendar, calls','Location history and places']) {
    assert.equal(features.find(feature => feature.sourceTitle === title).status, 'exploring', title);
  }
  const html = renderFeatureCatalog(catalog);
  assert.match(html, /Download the current Android build/);
  assert.equal((html.match(/<li>/g) || []).length, 48);
});

test('homepage groups match every canonical app title and availability stage', async () => {
  const { parseCatalog } = await import('../scripts/features.mjs');
  const source = await readFile(new URL('../../app/src/main/java/cc/bridgit/app/ui/more/WhatCanData.kt', import.meta.url), 'utf8');
  const canonical = parseCatalog(source);
  const grouped = catalog.groups.flatMap(group => group.features);
  assert.deepEqual(grouped.map(f => [f.sourceTitle, f.sourceStage]).sort(), canonical.map(f => [f.title, f.stage]).sort());
});
