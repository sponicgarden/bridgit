const labels = {current:'Current build', planned:'Planned', exploring:'Exploring'};
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function renderFeatureCatalog(catalog) {
  const features = catalog.groups.flatMap(group => group.features);
  if (features.length !== catalog.sourceCount || new Set(features.map(feature => feature.sourceTitle)).size !== features.length) {
    throw new Error('Feature catalog must account for each source card exactly once.');
  }
  if (features.some(feature => !labels[feature.status])) throw new Error('Unknown feature status.');
  const counts = Object.fromEntries(Object.keys(labels).map(status => [status, features.filter(feature => feature.status === status).length]));
  const groups = catalog.groups.map(group => {
    const summary = Object.entries(labels).map(([status, label]) => {
      const count = group.features.filter(feature => feature.status === status).length;
      return count ? `<span class="feature-status feature-status-${status}">${count} ${escape(label.toLowerCase())}</span>` : '';
    }).join('');
    return `<article class="feature-category" id="feature-${escape(group.id)}">
<img src="${escape(group.image)}" width="720" height="480" loading="lazy" alt="${escape(group.alt)}">
<div class="feature-category-copy"><h3>${escape(group.title)}</h3><p>${escape(group.intro)}</p><div class="feature-statuses">${summary}</div>
<details class="feature-details"><summary>Explore ${group.features.length} features <span aria-hidden="true">↗</span></summary><ul class="feature-list">${group.features.map(feature => `<li><div class="feature-item-heading"><h4>${escape(feature.title)}</h4><span class="feature-status feature-status-${feature.status}">${labels[feature.status]}</span></div><p>${escape(feature.description)}</p><p class="feature-example">${escape(feature.example)}</p></li>`).join('')}</ul></details></div></article>`;
  }).join('\n');
  return `<section class="section feature-catalog" id="features" aria-labelledby="features-title">
<div class="sectionhead"><div><span class="eyebrow">One phone. Many ways to help.</span><h2 id="features-title">What can Bridgit do?</h2></div><p>Give your agent eyes, ears and a way to reach you. Explore the app’s ${features.length} features, organized into ${catalog.groups.length} practical groups.</p></div>
<div class="catalog-overview" aria-label="Feature availability"><span><b>${counts.current}</b> in the current build</span><span><b>${counts.planned}</b> planned</span><span><b>${counts.exploring}</b> being explored</span></div>
<p class="catalog-note">“Current build” means implemented in the personal app build; availability depends on your version, device, enabled features and Android permissions. A compatible agent supplies the reasoning and follow-through. <a href="/download/">Download the current Android build</a> or request its link by email.</p>
<div class="feature-grid">${groups}</div></section>`;
}
