# BRIDGIT marketing website

Canonical website source for the BRIDGIT Android repository. Seven responsive pages, original branding, an interactive appliance concept selector and developer information. Current features and planned appliances are labeled separately. No public download or signup is advertised yet.

The site uses plain HTML/CSS/JavaScript and Node's standard library. There are no npm dependencies, lockfile, external font requests, analytics SDKs or marketing forms. The older `launch-kit/site/` preview and its separate hosting repository remain preserved; make new website changes here.

## Preview

From `website/`, run `npm run dev` and open the printed local URL (default http://127.0.0.1:4173/). The server builds on startup. After editing `public/` or configuration, restart it to rebuild. Set `PORT` if necessary.

## Build and check

Run `npm run build`, then `npm run check`. The output is `website/dist/`, which is ignored by Git. Source lives in `public/`; build and preview commands live in `scripts/`.

## Domain and deployment

Set `origin` in `site.config.json` to the real HTTPS origin when chosen, or supply `SITE_ORIGIN` to the build. Also set verified `supportEmail` and `privacyEmail` when those mailboxes exist. Build generates canonical URLs, Open Graph URLs, sitemap and robots rules from that origin. The selected domain is `bridgit.cc` and the configured origin is `https://bridgit.cc`. The zone is active in the Wingsiebird account; the repository includes its deployment configuration.

`indexable` defaults to false while the site is in preparation. Set true only when ready for search engines. Run `npm run preflight` to see remaining launch inputs; privacy/terms drafts must be finalized before those URLs can be used in Google Play. A missing domain fails release preflight; unfinished contact/legal details are reported as notes, not fabricated.

Deploy the contents of `dist/` to any static host with HTTPS and directory-index support. Build command: `npm --prefix website run build`. Publish directory: `website/dist`. For a host rooted directly at `website/`, use `npm run build` and publish `dist`. The `_headers` file supplies basic headers on hosts that support that convention; configure equivalent headers on other hosts. Use the site's ordinary trailing-slash URLs; no SPA fallback is needed.

Routes: `/`, `/how-it-works/`, `/developers/`, `/support/`, `/privacy/`, `/terms/`, `/delete-data/`.

There is no hosted-account deletion backend, waitlist, email sender or public download. Keep unsupported features labeled as planned; adding a domain does not change feature readiness. The literal "any Android device" tagline describes the product direction; compatibility copy states the current Android 14+ requirement and proposed older-device support.

## Branding

BRIDGIT — “Turn any Android device into an agentic appliance.” Original SVG mark and local social image are in `public/assets/`; brand sources are retained under `launch-kit/brand/`. The site uses Android as a platform descriptor and includes trademark attribution. No Android logo or Google partnership is implied. Public brand/trademark clearance is a separate launch consideration.

## Cloudflare

The existing Wrangler login is `wingsiebird@gmail.com`. `wrangler.jsonc` pins account `9cd3a280a54ce2a5b382602f0247b577` and the `bridgit` Worker, serving this directory’s static build at `bridgit.cc` and `www.bridgit.cc` through Cloudflare Custom Domains. Cloudflare manages DNS and TLS for these bindings. The domain is already an active Free zone.

From `website/`, run `npm run deploy:check` to build, validate and dry-run the configuration; run `npm run deploy` only when publication is intended. Both commands require Wrangler on PATH, authenticated to the named account. No token belongs in this repository. `workers.dev` and version preview URLs are disabled. Search indexing stays disabled and policy pages stay clearly marked as drafts until launch readiness is confirmed.

Cloudflare reference: https://developers.cloudflare.com/workers/configuration/routing/custom-domains/

Deployment verified 28 October 2026: https://bridgit.cc and https://www.bridgit.cc serve this preview through Worker `bridgit` in the Wingsiebird account. All seven pages, robots.txt and sitemap.xml respond successfully; unknown paths return 404. Indexing remains disabled; verified contact mailboxes and final legal text are still launch follow-ups.
