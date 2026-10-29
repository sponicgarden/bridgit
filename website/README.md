# BRIDGIT marketing website

Canonical website source for the BRIDGIT Android repository. Seven responsive pages, original branding, an interactive appliance concept selector and developer information. Current features and planned appliances are labeled separately. No public download or signup is advertised yet.

The site uses plain HTML/CSS/JavaScript and Node's standard library. There are no npm dependencies, lockfile, external font requests, analytics SDKs or marketing forms. The older `launch-kit/site/` preview and its separate hosting repository remain preserved; make new website changes here.

## Preview

From `website/`, run `npm run dev` and open the printed local URL (default http://127.0.0.1:4173/). The server builds on startup. After editing `public/` or configuration, restart it to rebuild. Set `PORT` if necessary.

## Build and check

Run `npm run build`, then `npm run check`. The output is `website/dist/`, which is ignored by Git. Source lives in `public/`; build and preview commands live in `scripts/`.

## Domain and deployment

Set `origin` in `site.config.json` to the real HTTPS origin when chosen, or supply `SITE_ORIGIN` to the build. Also set verified `supportEmail` and `privacyEmail` when those mailboxes exist. Build generates canonical URLs, Open Graph URLs, sitemap and robots rules from that origin. The selected domain is `bridgit.cc` and the configured origin is `https://bridgit.cc`. The zone is active in the Wingsiebird account; the repository includes its deployment configuration.

`indexable` is enabled for the public site. The build emits `index,follow`, canonical HTTPS apex URLs and a seven-page sitemap, and robots.txt allows crawling. Run `npm run preflight` to see remaining launch inputs; privacy and terms describe the current app and website, with support/privacy requests sent to `support@bridgit.cc`. Reconcile these disclosures with the actual app release and Play declarations before store submission. A missing domain fails release preflight; unfinished contact/legal details are reported as notes, not fabricated.

Deploy the contents of `dist/` to any static host with HTTPS and directory-index support. Build command: `npm --prefix website run build`. Publish directory: `website/dist`. For a host rooted directly at `website/`, use `npm run build` and publish `dist`. The `_headers` file supplies basic headers on hosts that support that convention; configure equivalent headers on other hosts. Use the site's ordinary trailing-slash URLs; no SPA fallback is needed.

Routes: `/`, `/how-it-works/`, `/developers/`, `/support/`, `/privacy/`, `/terms/`, `/delete-data/`.

There is no hosted-account deletion backend, waitlist, email sender or public download. Keep unsupported features labeled as planned; adding a domain does not change feature readiness. The literal "any Android device" tagline describes the product direction; compatibility copy states the current Android 14+ requirement and proposed older-device support.

## Branding

BRIDGIT — “Turn any Android device into an agentic appliance.” Original SVG mark and local social image are in `public/assets/`; brand sources are retained under `launch-kit/brand/`. The site uses Android as a platform descriptor and includes trademark attribution. No Android logo or Google partnership is implied. Public brand/trademark clearance is a separate launch consideration.

## Cloudflare

The existing Wrangler login is `wingsiebird@gmail.com`. `wrangler.jsonc` pins account `9cd3a280a54ce2a5b382602f0247b577` and the `bridgit` Worker, serving this directory’s static build at `bridgit.cc` and `www.bridgit.cc` through Cloudflare Custom Domains. Cloudflare manages DNS and TLS for these bindings. The domain is already an active Free zone.

From `website/`, run `npm run deploy:check` to build, validate and dry-run the configuration; run `npm run deploy` only when publication is intended. Both commands require Wrangler on PATH, authenticated to the named account. No token belongs in this repository. `workers.dev` and version preview URLs are disabled. The Worker runs before assets and permanently redirects HTTP and www requests to `https://bridgit.cc`, preserving path and query. `npm test` covers redirects and canonical asset/404 behavior.

Cloudflare reference: https://developers.cloudflare.com/workers/configuration/routing/custom-domains/

Deployment verified 28 October 2026: https://bridgit.cc and https://www.bridgit.cc serve this preview through Worker `bridgit` in the Wingsiebird account. All seven pages, robots.txt and sitemap.xml respond successfully; unknown paths return 404. The site initially launched with indexing disabled; the search-indexing update below replaces that setting.

## Home page use cases

The “What can you do with Bridgit?” carousel contains five concept illustrations in `public/assets/use-cases/`: baby monitor, family help station, desk scanner, pet camera and workshop eye. Edit the slide markup in `public/index.html` to add cases or replace images. Keep image alt text and current/planned feature labels accurate. Native horizontal scrolling works without JavaScript; arrow buttons and keyboard navigation are enhanced by `assets/app.js`. No autoplay is used.

The home page and footer link to `https://github.com/sponicgarden/bridgit`. At verification the repository was private, so the page explains that GitHub access is needed. Recheck visibility before removing that note.

Search-indexing update, 28 October 2026: all seven public pages allow indexing; HTTP/www variants return 301 to the HTTPS apex. Privacy and terms are dated public pages. The support contact receives mail through Cloudflare Email Routing. Cloudflare manages MX/SPF/DKIM DNS; destination verification and routing rules are account configuration and contain no repository secrets. The receive-only forwarding rule does not itself provision outbound SMTP.

## App feature catalog

The home page’s “What can Bridgit do?” section is built from `content/features.json` by `scripts/feature-catalog.mjs`. It reviews all 47 cards from the app 1.5.2 `ui/more/WhatCanData.kt` collection found in the existing `shotpix-5ee1bf` worktree (also identical in `brave-ellis-71a51e` and `bridget-p1a-22eaeb`). These worktrees were read as source material; their app code was not changed or merged.

Seven purpose-based groups replace a flat feature dump. Each item keeps its original source title and stage for coverage checks, with shorter public copy and a practical example. `NOW` maps to Current build (24); P2–P6 and LATER map to Planned (19); IDEA maps to Exploring (4). These labels reflect the app catalog and implementation source, not a new device acceptance test or a public release. Recheck them against the app when features ship. The current root Android checkout is older than this catalog.

The seven original WebP illustrations in `public/assets/features/` come from that app’s `drawable-nodpi/whatcan_*.webp` collection. Native HTML details expose the individual features without requiring JavaScript. The five existing appliance concepts remain a separate planned-workflow carousel. Build before previewing changes to the JSON.

Home page update: the appliance/use-case carousel appears before the complete feature catalog and contains 12 illustrated ideas. The added examples cover voice notes, spoken checklists, agent choices, a family board, project photos, language practice and live progress text. Cards distinguish current tools from planned workflows. Arrow buttons, swipe and Left/Right keys browse; Home/End jump to either end. No autoplay.

Agent setup band: seven locally served official marks identify Grok bot, Claude, ChatGPT, Meta Muse, OpenCode, OpenRouter.ai and Google Gemini. Provenance is in `brand/agent-sources.json`. The three-second copy refers to pasting a prepared pairing prompt after phone permissions and private-network setup, not total installation time. Connections still require a compatible API/MCP-capable agent; this row does not establish universal direct support in each hosted chat product.

The How it works page describes the unified product offering. The Developers page links to the app-generated public API reference (verified version 1.5.4), exact section anchors, live discovery endpoints, vendor MCP guides and the access-controlled source specifications.

## Sample app screenshots

Home (`/#app-screenshots`) and the matching connection, access and first-task steps on `/how-it-works/` use three real Pixel 10 Pro XL captures from app 1.5.4. Privacy-reviewed crops are copied from the local release-collateral worktree into `public/assets/screenshots/`; provenance is retained in `brand/screenshots/provenance.json`. The home connection address is already replaced with an explicit privacy label. Controls is a detail crop. Images preserve their aspect ratio, load lazily and link to the full-size PNG without JavaScript. Captions identify the release version separately from the development feature catalog and planned appliance illustrations.
