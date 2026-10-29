import { existsSync } from 'node:fs';
import path from 'node:path';
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function validateControlCatalog(catalog, root) {
  if (catalog.features.length !== 20 || new Set(catalog.features.map(f=>f.id)).size !== 20) throw new Error('The control audit must account for twenty unique feature families.');
  for (const feature of catalog.features) {
    if (feature.status !== 'development') throw new Error('Local control work cannot inherit published availability.');
    const target=path.resolve(root,feature.implementation);
    if (!target.startsWith(root+path.sep) || !existsSync(target)) throw new Error('Missing implementation evidence: '+feature.id);
  }
}
export function renderControlCatalog(catalog) {
  return `<section class="section" id="agent-control" aria-labelledby="control-title"><div class="sectionhead"><div><span class="eyebrow">Private build preview</span><h2 id="control-title">Help across your apps.</h2></div><p>New control tools build on the existing device bridge. Your phone approves the agent, app scope and each action. Android restrictions and protected screens remain in force.</p></div><p class="catalog-note">${escape(catalog.availability)} ${catalog.appVersion ? `Verified APK ${escape(catalog.appVersion)} · code ${escape(catalog.versionCode)}.` : 'Release artifact verification is pending.'} Requires Android ${catalog.androidMinimum}+. <a href="/developers/#agent-control-protocol">Read the control protocol</a>.</p><div class="feature-grid"><article class="feature-category"><div class="feature-category-copy"><h3>Twenty capability families</h3><p>Implementation is separate from acceptance on every phone. Check live readiness before use.</p><details class="feature-details"><summary>Explore the control build</summary><ul class="feature-list">${catalog.features.map(f=>`<li><div class="feature-item-heading"><h4>${escape(f.title)}</h4><span class="feature-status feature-status-planned">Development build</span></div><p>${escape(f.description)}</p></li>`).join('')}</ul></details></div></article></div><p class="small">General autonomous Accessibility control is confined to the private variant. The limited variant excludes those service declarations and still requires Android permissions and store review. Translation models must be downloaded with approval; no cloud transcription fallback is silently selected.</p></section>`;
}
