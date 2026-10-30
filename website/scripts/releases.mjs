// Render content/releases.json into the {{RELEASES}} placeholder of /releases/.
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const megabytes = bytes => bytes ? `${Math.round(bytes / 1e6)} MB` : '';
const date = iso => new Date(`${iso}T12:00:00Z`).toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC' });
const paragraphs = text => text.split(/\n{2,}/).map(p => `<p>${escape(p).replace(/\n/g, '<br>')}</p>`).join('');

function links(release) {
  const items = [];
  if (release.download) items.push(`<a href="${escape(release.download)}">Download APK</a>`);
  if (release.githubApk) items.push(`<a href="${escape(release.githubApk)}">APK on GitHub</a>`);
  items.push(`<a href="${escape(release.url)}">Release page</a>`);
  return items.join(' · ');
}

export function renderReleases({ releases }) {
  if (!releases?.length) throw new Error('content/releases.json has no releases.');
  const [latest, ...older] = releases;
  const latestHtml = `<section class="release-latest" aria-labelledby="latest-title"><span class="label">Latest release · ${escape(date(latest.published))}</span><h2 id="latest-title">bridgit ${escape(latest.version)}</h2>${paragraphs(latest.notes)}<div class="actions"><a class="button" href="${escape(latest.download || latest.githubApk)}">Download bridgit ${escape(latest.version)} APK <span aria-hidden="true">↓</span></a><span class="small">${escape(megabytes(latest.sizeBytes))} · Android 14+</span></div>${latest.sha256 ? `<p class="small">SHA-256 <code class="download-checksum">${escape(latest.sha256)}</code></p>` : ''}<p class="small">${links(latest)}</p></section>`;
  const history = older.map(release => `<li><div class="release-head"><h3>${escape(release.version)}</h3><span class="small">${escape(date(release.published))}${release.sizeBytes ? ` · ${escape(megabytes(release.sizeBytes))}` : ''}</span></div>${paragraphs(release.notes)}${release.sha256 ? `<p class="small">SHA-256 <code class="download-checksum">${escape(release.sha256)}</code></p>` : ''}<p class="small">${links(release)}</p></li>`).join('');
  return `${latestHtml}<section aria-labelledby="history-title"><h2 id="history-title">Release history</h2><ol class="release-list">${history}</ol></section>`;
}
