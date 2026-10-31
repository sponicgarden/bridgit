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
  if (text.includes('{{SITE_ORIGIN}}') || text.includes('chatgpt.site')) errors.push(`${file}: unresolved/old host identity.`);
  for (const match of text.matchAll(/<script[^>]+src="(https?:[^"]+)"/gi)) {
    if (path.relative(dist, file) !== 'download/index.html' || match[1] !== 'https://challenges.cloudflare.com/turnstile/v0/api.js') errors.push(`${file}: unexpected remote script.`);
  }
  if (/{{[A-Z_]+}}/.test(text)) errors.push(`${file}: unresolved build placeholder.`);
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
const origin = new URL(process.env.SITE_ORIGIN || config.origin).origin;
const robots = await readFile(path.join(dist, 'robots.txt'), 'utf8');
const sitemap = await readFile(path.join(dist, 'sitemap.xml'), 'utf8');
if (config.indexable) {
  if (!robots.includes('Allow: /') || /^Disallow:\s*\/\s*$/m.test(robots)) errors.push('Public site must allow crawling.');
  if (!robots.includes(`Sitemap: ${origin}/sitemap.xml`)) errors.push('robots.txt must advertise the canonical sitemap.');
}
for (const [file, text] of pages) {
  const route = '/' + path.relative(dist, file).split(path.sep).join('/').replace(/index\.html$/, '');
  if (!text.includes(`<link rel="canonical" href="${origin + route}">`)) errors.push(`${file}: incorrect canonical URL.`);
  if (config.indexable && !text.includes('<meta name="robots" content="index,follow">')) errors.push(`${file}: indexing blocked.`);
  if (config.indexable && !sitemap.includes(`<loc>${origin + route}</loc>`)) errors.push(`${file}: missing from sitemap.`);
}
const icon = await readFile(path.join(dist, 'assets/social-card-1200x630.png'));
if (icon.readUInt32BE(16) !== 1200 || icon.readUInt32BE(20) !== 630) errors.push('Incorrect social image dimensions.');
if (process.argv.includes('--release')) {
  if (!process.env.SITE_ORIGIN && !config.origin) errors.push('Set the real domain as SITE_ORIGIN or origin before deployment.');
  if (!config.supportEmail) warnings.push('Verified support mailbox is pending.');
  if (!config.privacyEmail) warnings.push('Verified privacy mailbox is pending.');
  if (!config.indexable) warnings.push('Search indexing is disabled deliberately.');
  // An older checkout once deployed a static-only Worker, which took email signups and the daily cleanup offline.
  const wrangler = await readFile(path.join(root, 'wrangler.jsonc'), 'utf8'), worker = await readFile(path.join(root, 'worker.mjs'), 'utf8');
  if (!/"binding":\s*"SIGNUPS"/.test(wrangler)) errors.push('wrangler.jsonc must bind the SIGNUPS D1 database.');
  if (!/"send_email"[\s\S]*"name":\s*"EMAIL"/.test(wrangler)) errors.push('wrangler.jsonc must bind EMAIL (send_email).');
  if (!/"crons"/.test(wrangler) || !/async scheduled\(/.test(worker)) errors.push('The daily cleanup cron and scheduled handler are required.');
}
for (const warning of warnings) console.warn(`NOTE: ${warning}`);
if (errors.length) { for (const error of errors) console.error(`FAIL: ${error}`); process.exitCode = 1; }
else console.log(`PASS: ${pages.size} pages, ${references} local links/assets, headings, language, origin replacement and social image.`);
