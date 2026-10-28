import { readFile, writeFile, mkdir, cp, access } from 'node:fs/promises';
import path from 'node:path';

const quoted = '"(?:\\\\.|[^"\\\\])*"';
const labels = { NOW: 'Implemented', P2: 'Coming next', P3: 'Planned', P4: 'Planned', P5: 'Planned', P6: 'Planned', LATER: 'Later', IDEA: 'Under consideration' };
const order = Object.keys(labels);
const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const slug = value => value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

function closingParenthesis(source, opening) {
  let depth = 0, string = false, escaped = false;
  for (let i = opening; i < source.length; i++) {
    const c = source[i];
    if (string) { if (escaped) escaped = false; else if (c === '\\') escaped = true; else if (c === '"') string = false; }
    else if (c === '"') string = true;
    else if (c === '(') depth++;
    else if (c === ')' && --depth === 0) return i;
  }
  throw new Error('Unbalanced feature catalog list.');
}
function parseMap(source, name) {
  const start = source.indexOf(`val ${name}:`);
  if (start < 0) throw new Error(`Missing ${name} map.`);
  const end = source.indexOf('\n    )', start);
  if (end < 0) throw new Error(`Unclosed ${name} map.`);
  const part = source.slice(start, end + 6);
  const result = new Map();
  const pattern = new RegExp(`(${quoted})\\s+to\\s+listOf\\(`, 'g');
  for (const match of part.matchAll(pattern)) {
    const opening = match.index + match[0].length - 1;
    const body = part.slice(opening + 1, closingParenthesis(part, opening));
    const title = JSON.parse(match[1]);
    if (result.has(title)) throw new Error(`Duplicate ${name} entry: ${title}`);
    result.set(title, [...body.matchAll(new RegExp(quoted, 'g'))].map(m => JSON.parse(m[0])));
  }
  const expected = (part.match(/\bto\s+listOf\(/g) || []).length;
  if (result.size !== expected) throw new Error(`Incomplete ${name} map.`);
  return result;
}
export function parseCatalog(source) {
  const options = parseMap(source, 'options'), endpoints = parseMap(source, 'endpoints');
  const expression = new RegExp(`Feature\\(Stage\\.(\\w+),\\s*(${quoted}),\\s*(${quoted}),\\s*(${quoted}),\\s*(${quoted})(?:,\\s*kind\\s*=\\s*Kind\\.(\\w+))?\\s*\\)`, 'g');
  const features = [...source.matchAll(expression)].map(m => ({ stage: m[1], title: JSON.parse(m[2]), description: JSON.parse(m[3]), example: JSON.parse(m[4]), reference: JSON.parse(m[5]), kind: m[6] || 'ABILITY' }));
  const expected = (source.match(/Feature\(Stage\./g) || []).length;
  if (!features.length || features.length !== expected) throw new Error(`Catalog extraction incomplete: ${features.length}/${expected}.`);
  for (const feature of features) {
    if (!labels[feature.stage]) throw new Error(`Unknown feature stage ${feature.stage}.`);
    feature.id = slug(feature.title);
    feature.options = options.get(feature.title) || [];
    feature.endpoints = endpoints.get(feature.title) || [];
  }
  if (new Set(features.map(f => f.id)).size !== features.length) throw new Error('Duplicate feature anchors.');
  return features.sort((a, b) => order.indexOf(a.stage) - order.indexOf(b.stage));
}

export async function buildFeatures(root, out) {
  const repo = path.resolve(root, '..');
  const source = await readFile(path.join(repo, 'app/src/main/java/net/finleg/bridget/ui/more/WhatCanData.kt'), 'utf8');
  const features = parseCatalog(source);
  const implemented = features.filter(f => f.stage === 'NOW').length;
  const pictures = path.join(repo, 'app/src/main/res/drawable-nodpi');
  await mkdir(path.join(out, 'assets/features'), { recursive: true });
  let cards = '';
  for (const f of features) {
    const base = `whatcan_${f.id.replaceAll('-', '_')}`;
    let picture = null;
    for (const candidate of [base, base.replaceAll('bridgit', 'bridget')]) {
      try { await access(path.join(pictures, candidate + '.webp')); picture = candidate + '.webp'; break; } catch {}
    }
    if (picture) await cp(path.join(pictures, picture), path.join(out, 'assets/features', picture));
    const details = f.options.length || f.endpoints.length ? `<details><summary>Options${f.endpoints.length ? ' & API calls' : ''}</summary>${f.options.length ? `<ul>${f.options.map(o => `<li>${escape(o)}</li>`).join('')}</ul>` : ''}${f.endpoints.length ? `<pre>${escape(f.endpoints.join('\n'))}</pre>` : ''}</details>` : '';
    cards += `<article class="feature-card" id="${f.id}" data-feature data-search="${escape([f.title, f.description, f.example, ...f.options].join(' '))}" data-stage="${f.stage === 'NOW' ? 'now' : 'planned'}"><div class="feature-heading"><span class="feature-status ${f.stage === 'NOW' ? 'is-now' : ''}">${labels[f.stage]}</span><span class="feature-kind">${f.kind === 'ABILITY' ? 'Device capability' : 'Controls & connections'}</span></div>${picture ? `<img src="/assets/features/${picture}" alt="" loading="lazy" decoding="async" width="640" height="360">` : ''}<div class="feature-body"><h2><a href="#${f.id}">${escape(f.title)}</a></h2><p>${escape(f.description)}</p><div class="feature-example"><span>For example</span><p>${escape(f.example)}</p></div>${details}</div></article>`;
  }
  const target = path.join(out, 'features/index.html');
  let html = await readFile(target, 'utf8');
  html = html.replace('{{FEATURE_CARDS}}', cards).replaceAll('{{FEATURE_COUNT}}', String(features.length)).replaceAll('{{IMPLEMENTED_COUNT}}', String(implemented)).replaceAll('{{PLANNED_COUNT}}', String(features.length - implemented));
  await writeFile(target, html);
  await writeFile(path.join(out, 'feature-catalog.json'), JSON.stringify({ source: 'Android app: WhatCanData.kt', features }, null, 2) + '\n');
  return { total: features.length, implemented };
}
