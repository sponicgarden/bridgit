import { readFile, writeFile, cp, mkdir, readdir, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const config = JSON.parse(await readFile(path.join(root, 'site.config.json'), 'utf8'));
const suppliedOrigin = process.env.SITE_ORIGIN || config.origin;
let origin = '';
if (suppliedOrigin) {
  const parsed = new URL(suppliedOrigin);
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error('SITE_ORIGIN must be a plain HTTPS origin, for example https://your-domain.com');
  }
  origin = parsed.origin;
}
if (config.indexable && !origin) throw new Error('An indexable build needs SITE_ORIGIN or origin in site.config.json.');
const out = path.join(root, 'dist');
await rm(out, { recursive: true, force: true });
await mkdir(out, { recursive: true });
await cp(path.join(root, 'public'), out, { recursive: true });
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const pages = [];
async function prepare(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const target = path.join(dir, entry.name);
    if (entry.isDirectory()) { await prepare(target); continue; }
    if (!entry.name.endsWith('.html')) continue;
    const relative = path.relative(out, target).split(path.sep).join('/');
    const route = '/' + relative.replace(/index\.html$/, '');
    let html = (await readFile(target, 'utf8')).replaceAll('{{SITE_ORIGIN}}', origin);
    html = html.replace('<meta name="robots" content="noindex,nofollow">', `<meta name="robots" content="${config.indexable ? 'index,follow' : 'noindex,nofollow'}">`);
    if (origin) {
      html = html.replace('</head>', `<link rel="canonical" href="${escape(origin + route)}"><meta property="og:url" content="${escape(origin + route)}"></head>`);
      pages.push(origin + route);
    }
    for (const kind of ['support', 'privacy']) {
      const email = config[`${kind}Email`];
      if (!email) continue;
      if (!/^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(email)) throw new Error(`Invalid ${kind}Email.`);
      html = html.replace(new RegExp(`<span data-contact="${kind}">[^<]*</span>`, 'g'), `<a href="mailto:${escape(email)}">${escape(email)}</a>`);
    }
    await writeFile(target, html);
  }
}
await prepare(out);
await writeFile(path.join(out, 'robots.txt'), config.indexable ? `User-agent: *\nAllow: /\nSitemap: ${origin}/sitemap.xml\n` : 'User-agent: *\nDisallow: /\n');
if (origin) await writeFile(path.join(out, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${pages.map(url => `<url><loc>${escape(url)}</loc></url>`).join('')}</urlset>\n`);
await writeFile(path.join(out, '_headers'), '/*\n  X-Content-Type-Options: nosniff\n  Referrer-Policy: strict-origin-when-cross-origin\n  Permissions-Policy: camera=(), microphone=(), geolocation=()\n');
console.log(`Built ${pages.length || 7} pages in website/dist. Origin: ${origin || 'unset (local preview)'}. Search indexing: ${config.indexable ? 'on' : 'off'}.`);
