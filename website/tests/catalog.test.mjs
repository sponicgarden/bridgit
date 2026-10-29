import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { renderFeatureCatalog } from '../scripts/feature-catalog.mjs';
const catalog = JSON.parse(await readFile(new URL('../content/features.json', import.meta.url), 'utf8'));
const api = await readFile(new URL('../public/api/index.html', import.meta.url), 'utf8');
const provenance = JSON.parse(await readFile(new URL('../content/api-provenance.json', import.meta.url), 'utf8'));
const decode = value => value.replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&#39;', "'");
const normalize = value => decode(value).toLowerCase().replaceAll('bridget', 'bridgit');
const reference = new Map([...api.matchAll(/<section class="card"[^>]*><span class="chip[^\"]*">([^<]+)<\/span><h3>([^<]+)<\/h3>/g)]
  .map(([, stage, title]) => [normalize(title), stage === 'Available now' ? 'current' : stage === 'Needs your OK' ? 'exploring' : 'planned']));

test('every website feature matches the reviewed, versioned public API evidence', () => {
  const features = catalog.groups.flatMap(group => group.features);
  assert.equal(reference.size, catalog.sourceCount);
  assert.equal(features.length, reference.size);
  assert.equal(new Set(features.map(feature => normalize(feature.sourceTitle))).size, reference.size);
  for (const feature of features) {
    assert.equal(feature.status, reference.get(normalize(feature.sourceTitle)), feature.sourceTitle);
    assert.equal(feature.sourceStage, feature.status === 'current' ? 'NOW' : 'IDEA', feature.sourceTitle);
  }
  assert.equal(catalog.source.appVersion, provenance.appVersion);
  assert.equal(catalog.source.apiRevision, provenance.apiRevision);
  assert.match(api, new RegExp(`version ${provenance.appVersion.replaceAll('.', '\\.')}[,<]`));
});

test('implemented tools and exploratory ideas retain their actual scope', () => {
  const features = catalog.groups.flatMap(group => group.features);
  for (const title of ['Find my phone', 'Phone controls', 'Short video clips', 'City tour', 'Per-agent permissions']) {
    assert.equal(features.find(feature => feature.sourceTitle === title).status, 'current', title);
  }
  for (const title of ['Location history and places','Reach Bridgit without Tailscale (relay)',
    'Listen for a word on the phone','Messages, contacts, calendar, calls']) {
    assert.equal(features.find(feature => feature.sourceTitle === title).status, 'exploring', title);
  }
  const html = renderFeatureCatalog(catalog);
  assert.match(html, /Device acceptance is partial/);
  assert.match(html, /This website has no installer or signup form/);
  assert.equal((html.match(/<li>/g) || []).length, reference.size);
});

test('catalog generation rejects missing, duplicated or unknown-status features', () => {
  const missing = structuredClone(catalog); missing.groups[0].features.pop();
  assert.throws(() => renderFeatureCatalog(missing), /each source card/);
  const duplicate = structuredClone(catalog); duplicate.groups[0].features[0].sourceTitle = duplicate.groups[0].features[1].sourceTitle;
  assert.throws(() => renderFeatureCatalog(duplicate), /each source card/);
  const unknown = structuredClone(catalog); unknown.groups[0].features[0].status = 'invented';
  assert.throws(() => renderFeatureCatalog(unknown), /Unknown feature status/);
});
