# Bridgit website

Canonical website source for Bridgit. Eight responsive routes: `/`, `/how-it-works/`, `/developers/`, `/api/`, `/support/`, `/privacy/`, `/terms/`, and `/delete-data/`.

The site uses plain HTML/CSS/JavaScript and Node’s standard library, with no npm dependencies, external fonts, analytics SDKs or marketing forms. Source is in `public/`; the 47-item feature catalog is in `content/features.json`. The older `launch-kit/site/` remains preserved; make website changes here.

## Local preview and validation

Run `npm --prefix website run dev` and open the printed URL, normally http://127.0.0.1:4173/. The server builds on startup; rebuild after source changes. Set `PORT` to run an independent preview.

Run `npm --prefix website run build`, `npm --prefix website test`, `npm --prefix website run check`, and `npm --prefix website run preflight`. Output is `website/dist/`, ignored by Git. Tests reconcile each feature against the checked-in API evidence and check Worker canonical redirects and asset responses. Link checks cover local anchors, including the API reference.

## What the content describes

The reviewed public API reference is **1.6.0 / API revision 8**, fetched 29 October 2026. It lists 43 implemented catalog items and four exploratory ideas. The downloaded updater APK was checked against its manifest hash/size, inspected with aapt (1.6.0/code18, Android 14+) and verified with apksigner. This is evidence for that particular published artifact, not a claim that a repository counter is a released version.

Device acceptance is partial. Implementation, individual device readiness, installed app version and public availability are distinct. Feature descriptions retain bounds: owner-selected Downloads, active notification text rather than history, read-only Health Connect source records, owner-tapped clipboard copying, Latin-script OCR, device-dependent offline transcription and Android-dependent timed restoration. New feature switches start off. Existing unrestricted agents need review before relying on per-agent grants.

`content/features.json` records the original feature title and stage plus public copy. `scripts/feature-catalog.mjs` requires each item exactly once and renders native HTML details. `tests/catalog.test.mjs` compares every item’s availability with the reviewed API snapshot rather than enforcing historical counts.

City tour and agent-managed sessions are implemented. A general runtime for arbitrary agent-proposed appliances, live camera streaming and voice-triggered help remain future ideas. The home page leads with practical tools; its ten-card carousel is separate from three future appliance concepts. Monitoring concepts are not safety or emergency systems. The four exploratory catalog ideas are relay, trigger words, communications, and location history/places.

The website does not offer an installer or signup form. Existing preview users have an in-app update channel. Public reachability of an updater artifact is distinct from launching an enrollment or download program. Support is a contact channel, without a promise of preview or repository access.

## API reference and provenance

`public/api/index.html` is a versioned snapshot of the public app-generated reference, served under the site’s own origin. `content/api-provenance.json` records its upstream URL, hash, version/revision, release evidence and editorial changes. Owner-name references were generalized, and obsolete instructions to hardcode Gradle versions were replaced with the automatic APK allocation requirement. It retains upstream attribution and a link to the original. A paired phone’s `/v1/guide`, `/v1/openapi.json` and `/v1/capabilities` remain authoritative for that phone.

Refresh the snapshot deliberately when a new reference is verified. Check all advertised anchors and the source-to-catalog reconciliation; do not silently treat a version counter or development branch as a published reference. Never copy credentials or a real phone address into the snapshot.

## Screenshots and interaction examples

Preserved sample app screens on Home and How it works are privacy-reviewed crops from **1.5.4 on a Pixel 10 Pro XL**; `brand/screenshots/provenance.json` records their provenance. Their version labels are independent of the capability reference. They preserve aspect ratio and link to the full-size PNG. Private connection details remain withheld.

The six Grokbot/Muse interaction images show fictional choices and confirmations from a **1.6 development preview**. `brand/screenshots/interactions.json` records their provenance. They do not demonstrate real bookings, payments or a verified native provider integration. These galleries were preserved during the content review.

## Brand and compatibility

Bridgit is an Agentic Android Device Bridge. The current requirement is **Android 14 or later**. The marketing line is “Give your Android phone a new job.” The hero illustration is labeled as an illustration, separate from real app screenshots.

Provider marks in `public/assets/agents/` have provenance in `brand/agent-sources.json`; they imply neither partnership nor universal direct hosted-chat support. The mismatched muse.ai mark was removed from the provider row. OpenRouter is model routing. A compatible agent must have HTTP/MCP tools and a route to the phone’s private Tailscale network. The introduction prompt grants no access and contains no device key or address; it is separate from the app’s pairing prompt.

Software is Apache 2.0 licensed. GitHub confirms the source repository is private; public copy states that access is required. This site does not grant it. Android is a trademark of Google LLC. No Android logo or Google partnership is implied.

## Domain, indexing and deployment

`site.config.json` sets `https://bridgit.cc`, the verified contact `support@bridgit.cc`, and indexing. Build generates per-route canonicals, Open Graph URLs, sitemap and robots rules. Public source templates begin with noindex; the build applies the configured indexing state. Privacy and Terms are dated 29 October 2026; review their effective date when publishing material changes. Source editing is not publication.

`wrangler.jsonc` configures the existing Cloudflare Worker and custom domains. Account identifiers live in deployment configuration; no credentials belong in the repository. The Worker redirects HTTP/www to HTTPS apex, preserving path/query, before static assets. Unknown paths retain 404. Equivalent security headers must be configured on any alternative host.

`npm --prefix website run deploy:check` builds, tests, validates and performs a Wrangler dry run. `npm --prefix website run deploy` publishes and must only run when publication is authorized. The content review was validated locally without deploying.

The support address receives mail through Cloudflare Email Routing to the existing support inbox; this does not itself provision outbound SMTP. Do not add support response or access-approval promises without an actual supported process.

## Review history

`docs/reviews/2026-10-29-grok-4.7-website-review.md` contains the requested second review and the verified release evidence that supersedes its version uncertainty. The earlier Opus review and Grok findings informed the content changes. No user counts, audits, launch dates or compatibility guarantees were invented.
