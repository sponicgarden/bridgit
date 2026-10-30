# BRIDGIT marketing website

Canonical website source for the BRIDGIT Android repository. Nine responsive pages, original branding, an interactive appliance concept selector and developer information. Current features and planned appliances are labeled separately. The download page offers the signed development APK directly or sends a confirmation/download link by email. Optional release updates require a separate confirmed opt-in.

The site uses plain HTML/CSS/JavaScript and Node's standard library. There are no npm dependencies, lockfile, external font requests, analytics SDKs or marketing forms. The older `launch-kit/site/` preview and its separate hosting repository remain preserved; make new website changes here.

## Preview

From `website/`, run `npm run dev` and open the printed local URL (default http://127.0.0.1:4173/). The server builds on startup. After editing `public/` or configuration, restart it to rebuild. Set `PORT` if necessary.

## Build and check

Run `npm run build`, then `npm run check`. The output is `website/dist/`, which is ignored by Git. Source lives in `public/`; build and preview commands live in `scripts/`.

## Domain and deployment

Set `origin` in `site.config.json` to the real HTTPS origin when chosen, or supply `SITE_ORIGIN` to the build. Also set verified `supportEmail` and `privacyEmail` when those mailboxes exist. Build generates canonical URLs, Open Graph URLs, sitemap and robots rules from that origin. The selected domain is `bridgit.cc` and the configured origin is `https://bridgit.cc`. The zone is active; the repository includes its deployment configuration.

`indexable` is enabled for the public site. The build emits `index,follow`, canonical HTTPS apex URLs and a nine-page sitemap, and robots.txt allows crawling. Run `npm run preflight` to see remaining launch inputs; privacy and terms describe the current app and website, with support/privacy requests sent to `support@bridgit.cc`. Reconcile these disclosures with the actual app release and Play declarations before store submission. A missing domain fails release preflight; unfinished contact/legal details are reported as notes, not fabricated.

Deploy the contents of `dist/` to any static host with HTTPS and directory-index support. Build command: `npm --prefix website run build`. Publish directory: `website/dist`. For a host rooted directly at `website/`, use `npm run build` and publish `dist`. The `_headers` file supplies basic headers on hosts that support that convention; configure equivalent headers on other hosts. Use the site's ordinary trailing-slash URLs; no SPA fallback is needed.

Routes: `/`, `/how-it-works/`, `/developers/`, `/support/`, `/privacy/`, `/terms/`, `/delete-data/`, `/features/`, `/download/`.

There are no hosted BRIDGIT device accounts. Website email signup data can be deleted using the link in the download email. Keep unsupported features labeled as planned; adding a domain does not change feature readiness. The literal "any Android device" tagline describes the product direction; compatibility copy states the current Android 14+ requirement and proposed older-device support.

## Branding

BRIDGIT — “Turn any Android device into an agentic appliance.” Original SVG mark and local social image are in `public/assets/`; brand sources are retained under `launch-kit/brand/`. The site uses Android as a platform descriptor and includes trademark attribution. No Android logo or Google partnership is implied. Public brand/trademark clearance is a separate launch consideration.

## Cloudflare

`wrangler.jsonc` pins the Cloudflare account and the `bridgit` Worker, serving this directory’s static build at `bridgit.cc` and `www.bridgit.cc` through Cloudflare Custom Domains. Cloudflare manages DNS and TLS for these bindings. The domain is already an active Free zone.

From `website/`, run `npm run deploy:check` to build, validate and dry-run the configuration; run `npm run deploy` only when publication is intended. Both commands require Wrangler on PATH, authenticated to the named account. No token belongs in this repository. `workers.dev` and version preview URLs are disabled. The Worker runs before assets and permanently redirects HTTP and www requests to `https://bridgit.cc`, preserving path and query. `npm test` covers redirects and canonical asset/404 behavior.

Cloudflare reference: https://developers.cloudflare.com/workers/configuration/routing/custom-domains/

Deployment verified 28 October 2026: https://bridgit.cc and https://www.bridgit.cc serve this preview through Worker `bridgit`. All seven pages, robots.txt and sitemap.xml respond successfully; unknown paths return 404. The site initially launched with indexing disabled; the search-indexing update below replaces that setting.

## Home page use cases

The “What can you do with Bridgit?” carousel contains five concept illustrations in `public/assets/use-cases/`: baby monitor, family help station, desk scanner, pet camera and workshop eye. Edit the slide markup in `public/index.html` to add cases or replace images. Keep image alt text and current/planned feature labels accurate. Native horizontal scrolling works without JavaScript; arrow buttons and keyboard navigation are enhanced by `assets/app.js`. No autoplay is used.

The home page and footer link to `https://github.com/sponicgarden/bridgit`. The repository is public.

Search-indexing update, 28 October 2026: all seven public pages allow indexing; HTTP/www variants return 301 to the HTTPS apex. Privacy and terms are dated public pages. The support contact receives mail through Cloudflare Email Routing. Cloudflare manages MX/SPF/DKIM DNS; destination verification and routing rules are account configuration and contain no repository secrets. The receive-only forwarding rule does not itself provision outbound SMTP.

## Feature catalog and identity

`npm run build` reads the Android app’s `WhatCanData.kt` and creates `/features/` with all 47 current catalog entries, their availability, examples, options and API calls. Images are copied from the app’s WebP illustrations and loaded lazily. Parsing fails rather than silently dropping malformed entries. Source availability labels describe the personal app build, not a Google Play release.

The approved identity is documented in `brand/README.md`. The icon is the actual Android artwork; the wordmark uses outlined glyphs. Phone layouts expose a Features link with at least 44px tap height. Existing illustrated concept carousel content is preserved.

## Download email service

The Worker uses the `SIGNUPS` D1 binding and three secrets: `RESEND_API_KEY` (sending-only, restricted to bridgit.cc), `TURNSTILE_SECRET`, and `RATE_LIMIT_SALT`. Set secrets using Wrangler stdin; never commit credentials. The public Turnstile site key and current signed APK URL/version/hash are in `site.config.json`. Resend click/open tracking is disabled.

Apply `migrations/0001_download_requests.sql` to the new database before deployment. A daily scheduled handler removes expired download-only/pending signup records and old abuse counters. Requests are limited to 20 email sends per 24 hours globally, 3 per network address per hour, and one per email per 15 minutes, after production Turnstile validation. Increase caps deliberately when launch traffic grows.

GET links show a confirmation screen; explicit POST confirms a download and any optional update preference. Tokens are hashed at rest; network counters use a secret-keyed hash. Confirmation/unsubscribe pages are not cached and use origin-only referrers, so tokens do not leak through Referer headers while browser form Origin checks remain valid. Direct APK downloads remain public; email confirmation is not an APK access-control boundary. `npm test` uses Node’s SQLite runtime to exercise the actual database queries and consent flow.
