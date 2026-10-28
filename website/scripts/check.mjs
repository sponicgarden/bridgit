import { readFile, readdir, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const dist = path.join(root, 'dist');
const config = JSON.parse(await readFile(path.join(root, 'site.config.json'), 'utf8'));
const errors = [], warnings = [], files = [];
async function walk(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) await walk(file); else files.push(file);
  }
}
try { await walk(dist); } catch { throw new Error('Run npm run build before npm run check.'); }
const pages = new Map();
for (const file of files.filter(f => f.endsWith('.html'))) {
  const text = await readFile(file, 'utf8'); pages.set(file, text);
  if ((text.match(/<h1\b/g) || []).length !== 1) errors.push(`${file}: expected one h1.`);
  if (!text.includes('<html lang="en">')) errors.push(`${file}: missing language.`);
  if (text.includes('{{SITE_ORIGIN}}') || text.includes('truealder512')) errors.push(`${file}: unresolved/old host identity.`);
  if (/<script[^>]+src="https?:/i.test(text)) errors.push(`${file}: unexpected remote script.`);
}
let references = 0;
for (const [file, text] of pages) {
  for (const match of text.matchAll(/(?:href|src)="([^"]+)"/g)) {
    const ref = match[1]; if (/^(?:[a-z]+:|\/\/)/i.test(ref)) continue;
    const [pathname, fragment] = ref.split('#');
    let target = pathname ? path.resolve(pathname.startsWith('/') ? dist : path.dirname(file), pathname.replace(/^\//, '')) : file;
    try { if ((await stat(target)).isDirectory()) target = path.join(target, 'index.html'); await stat(target); }
    catch { errors.push(`${file}: missing ${ref}`); continue; }
    if (fragment && pages.has(target) && !pages.get(target).includes(`id="${fragment}"`)) errors.push(`${file}: missing anchor ${ref}`);
    references++;
  }
}
const icon = await readFile(path.join(dist, 'assets/social-card-1200x630.png'));
if (icon.readUInt32BE(16) !== 1200 || icon.readUInt32BE(20) !== 630) errors.push('Incorrect social image dimensions.');
if (process.argv.includes('--release')) {
  if (!process.env.SITE_ORIGIN && !config.origin) errors.push('Set the real domain as SITE_ORIGIN or origin before deployment.');
  if (!config.supportEmail) warnings.push('Verified support mailbox is pending.');
  if (!config.privacyEmail) warnings.push('Verified privacy mailbox is pending.');
  if (!config.indexable) warnings.push('Search indexing is disabled deliberately.');
  warnings.push('Privacy/terms pages are labeled drafts. Finalize before using them for Play submission.');
}
for (const warning of warnings) console.warn(`NOTE: ${warning}`);
if (errors.length) { for (const error of errors) console.error(`FAIL: ${error}`); process.exitCode = 1; }
else console.log(`PASS: ${pages.size} pages, ${references} local links/assets, headings, language, origin replacement and social image.`);
