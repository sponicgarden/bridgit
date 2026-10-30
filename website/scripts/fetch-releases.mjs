// Snapshot the GitHub release history into content/releases.json.
// Run by hand after publishing a release; the build itself makes no network calls.
import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const out = fileURLToPath(new URL('../content/releases.json', import.meta.url));
const response = await fetch('https://api.github.com/repos/sponicgarden/bridgit/releases?per_page=100', {
  headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'bridgit-website' }
});
if (!response.ok) throw new Error(`GitHub releases request failed: ${response.status}`);

const releases = (await response.json())
  .filter(release => !release.draft)
  .map(release => {
    const body = (release.body || '').replace(/\r\n/g, '\n').trim();
    const sha = body.match(/^SHA-256:\s*([0-9a-f]{64})\s*$/m)?.[1] || null;
    const download = body.match(/^Download:\s*(https:\S+\.apk)\s*$/m)?.[1] || null;
    const notes = body.split('\n').filter(line => !/^(SHA-256|Download):/.test(line)).join('\n').trim();
    const apk = release.assets.find(asset => asset.name.endsWith('.apk'));
    return {
      version: release.tag_name.replace(/^bridget-v/, ''),
      tag: release.tag_name,
      name: release.name,
      published: release.published_at.slice(0, 10),
      notes,
      sha256: sha,
      download,
      githubApk: apk ? apk.browser_download_url : null,
      sizeBytes: apk ? apk.size : null,
      url: release.html_url
    };
  });

await writeFile(out, JSON.stringify({ fetched: new Date().toISOString().slice(0, 10), releases }, null, 2) + '\n');
console.log(`Wrote ${releases.length} releases to content/releases.json.`);
