# Bridgit public release checklist

Prepared 30 Oct 2026 against `main` at e8ec84d (app 1.6.0, versionCode 18). It covers
the Android app, this repository, the website (bridgit.cc) and the Play Store collateral
in the untracked `launch-kit/` folder of the main checkout. Legal and policy facts were
checked on 30 Oct 2026. They are not legal advice; have counsel confirm the items marked ⚖️.

Tags: **[BLOCKER]** must be done before that release · **[DECIDE]** owner decision ·
**[SHOULD]** strongly recommended · **[NICE]** optional.

This file is in a public repository, so it names files and lines but repeats no personal
details or keys.

---

## 0. Decisions that shape everything else

- [x] **Decided 30 Oct 2026: the repository stays public.** `sponicgarden/bridgit` has
  been public since it was created (27 Oct 2026), with 0 forks and 0 stars. Everything
  in §2 stays visible while it is cleaned up: 21 branches, 12 tags, 12 releases with
  APKs and 17 PRs. That makes §1 urgent: fix the tip of `main` first.
- [ ] **Decided 30 Oct 2026: keep the full history in a private backup repo and make
  the main repo clean.**
  - Rename `sponicgarden/bridgit` (with its PRs, releases and history) to a private
    archive.
  - Create a new public `sponicgarden/bridgit` from one squashed commit of the cleaned
    tree.
  - Point the local `origin` remote at the new repo and keep the archive as a second
    remote.

  Order:
  1. Merge the scrub branch to `main`.
  2. Merge or drop `codex/agent-appliance-launch-kit`.
  3. Re-run the personal-string sweep on the merged tree, because that branch predates
     the scrub and edits README and the website.
  4. Rename, create, push.

  **Decided 30 Oct 2026:** the first public commit is authored as GitHub user `rsonnad`
  with its noreply address (`-c user.name=rsonnad
  -c user.email=5198821+rsonnad@users.noreply.github.com`).
- [ ] **[DECIDE] ⚖️ Publishing legal entity.** Everything says "Sponic Garden, Inc."
  (LICENSE/NOTICE, README:3, the site footer, privacy and terms). The Sentry org slug
  spells the name differently (README:179). One entity must be all of these: copyright
  holder, Play developer account, GDPR controller, DSA trader and the contact on the
  privacy and terms pages. If an EU company publishes instead, no GDPR Art. 27
  representative is needed, but that country's website disclosures apply (for example
  KSH Art. 206 for a Polish company). A US Inc. needs an EU representative and, for the
  UK, a UK representative.
- [ ] **[DECIDE] Two builds: Play and sideload.** Several Play blockers (the self-updater,
  accessibility screenshots, Health Connect, notification reading) can only be solved by
  a `play` product flavor that leaves them out, with the `sideload` build keeping them.
  `app/build.gradle.kts` has no flavors today.
- [ ] **[DECIDE] ⚖️ Product name: trademark conflict.** These US marks are live or
  pending, per aggregator search results; confirm in USPTO TSDR:
  - BRIDGIT, Bridgit Inc. (construction workforce software): Reg. 5965263, class 9.
  - B BRIDGIT, Bridgit Inc.: Reg. 7691729, classes 9 and 42.
  - BRIDGIT, Bridgit Inc.: Serial 99554793, class 42, pending.
  - BRIDGIT, Bridge to Better Living LLC: Reg. 6868999, classes 35 and 42.
  - BRIDGIT, Wellbridge Health (mobile health software): Serial 86248566. Status not
    confirmed. It overlaps the Health Connect features.
  - BRDGIT, SilverLogic LLC (AI workflow software): Serial 99489487, pending.

  The identical word in the same software classes is a moderate-to-high risk. Get a
  clearance opinion (US, EUIPO, WIPO, CIPO) **before** Play submission and any more brand
  spend. "Device Manager" is also close to Google's former "Android Device Manager".
- [ ] **[DECIDE] Which website is canonical.** The live bridgit.cc is built from the
  `codex/agent-appliance-launch-kit` lineage, not from `main`. Neither has all of the
  other's content:
  - Live has the Grokbot/Muse screenshots and the AI-vendor logo strip.
  - `main` has `/download/`, `/features/` and the email Worker. Live returns 404 for
    `/download/` and `/features/`.

  Merge them into one source before deploying again.
- [x] **Merged the launch-kit work (30 Oct 2026), with `main` as the base.** The main
  checkout's uncommitted edits were snapshotted to `codex/launch-kit-wip-snapshot`;
  that folder itself was not touched.
  - **Taken:** the Play collateral and brand files (`launch-kit/`, minus the private
    preview site), the v3 appliance spec and packed plan, the 29 Oct reviews, the clean
    app screenshots, the `/api/` reference page, the plum social card, and the helper's
    `wait-interaction` command plus its `tailscale nc` cleanup fix, with tests.
  - **Kept from `main`:** the website pages, app code, README and docs. Both sides
    built the website separately, and `main`'s has the download, features and email
    flow.
  - **Not merged:**
    - agent logo uploads (`AgentBranding`) and the agent-setup edits: written for the
      1.2 code, so they would need a port
    - the AI vendor logos
    - the unpacked plan page: it needs local stylesheets
- [ ] **[DECIDE] Version counter.**
  - The branch's automatic allocator (`tools/allocate-apk-version.py`,
    `apk-version.properties`, the Gradle task and AGENTS.md rules) has reached
    **1.6.13 / versionCode 30**. `main` still hard-codes **1.6.0 / 18**.
  - A `main` build can't install over an allocator build of the same package that is
    already on a phone.
  - The allocator also rewrites a tracked file on every build. That is awkward for CI
    and outside contributors.
  - Choose one:
    - port the allocator, keeping the counter out of git (for example in `~/.config`);
    - or set `main` to the next free code (31+).

  The allocator files stay on `codex/launch-kit-wip-snapshot`.
- [ ] **[DECIDE] Launch scope.** Global or a staged set of countries. Free (assumed).
  Target audience: adults 18+ managing their own devices.
- [ ] **[DECIDE] Play account type.** Personal accounts need a closed test with 12 testers
  for 14 days. Organization accounts need a D-U-N-S number, which can take up to 30 days.
  Start whichever applies now; it is the longest lead time on the list.

## 1. Do now (the repo is public)

- [x] **Removed the owner's tailnet IP from the shipped helper (30 Oct 2026).**
  `tools/bridgit` (served from the APK at `/v1/tools/bridget`) has no default host now.
  It exits with a clear message unless `BRIDGIT_HOST` is set, and the phone's setup
  prompt already writes it.
- [x] **Made the Sentry DSN a build setting (30 Oct 2026).** `BuildConfig.SENTRY_DSN`
  comes from `-PbridgitSentryDsn`, `BRIDGIT_SENTRY_DSN` or `sentryDsn` in
  `keystore.properties`, and is empty by default. Builds without a DSN send nothing and
  hide the Settings switch.
- [ ] **[BLOCKER] Rotate the Sentry key.** The old DSN is still in public history. In
  Sentry, open the `bridget-android` project's Client Keys, create a new key, disable the
  old one, and put the new DSN in `keystore.properties` (`sentryDsn=`).
- [x] **Replaced the owner's first name in app code and tests with "the owner"
  (30 Oct 2026),** using they/them.
  - `filed_by` / `last_by` / history actor values for owner actions are now `"owner"`.
    This changes the API value agents see for new reports and status changes.
  - Docs, tools and README are still to do (§2).
- [ ] **[SHOULD] Make the update and docs host configurable.** `update/UpdateLogic.kt:14`
  and `api/ApiDocs.kt:11` pin Sponic's R2 bucket, so forks would offer Sponic's APKs as
  updates.
- [ ] **[SHOULD] Secrets scan result.** `gitleaks` found no real credentials across all
  186 commits; the one hit is a test value in CallbacksTest. No keystore, `.env` or APK
  was ever committed. Rotate the Sentry DSN anyway (above).

## 2. Open-source repository

### Must fix before announcing
- [x] **Scrubbed the working tree (30 Oct 2026).**
  - The owner's name, surname and pronouns, the tailnet hostname and IPs, local paths,
    vault item names and ids, R2 account and bucket, Azure endpoint and personal email
    are gone from every tracked file. Test fixtures use `my-phone.tailnet-name.ts.net`
    and `100.101.102.103`.
  - Publisher settings moved to the gitignored `tools/publish.env` (see
    `tools/publish.env.example`). The acceptance scripts read `BRIDGIT_TEST_PHONE` /
    `BRIDGIT_TEST_ADB`; the image generator reads `AZURE_OPENAI_*`.
  - Removed `docs/handover-p1b.md` and `docs/spec-v1.0.pdf`; the private archive keeps
    them.
  - README rewritten for public readers.
  - Kept on purpose: the Cloudflare account and D1 ids in `website/wrangler.jsonc` (not
    secret, needed to deploy), the public R2 download host (the updater uses it) and
    the old product name "Almusca" in spec history.
- [ ] **[SHOULD] Two website tests already fail on `main`.** `catalog.test.mjs` and
  `features.test.mjs` expect 24 catalog entries, but the app now has 43. Update the
  tests (or the catalog grouping) before adding CI.
- [x] ~~Remove personal and infrastructure details from tracked files~~ (done; original list):
  - `README.md`:
    - line 5: owner and device
    - line 68: tailnet hostname
    - lines 197–198: local keystore paths
    - lines 59 and 204: vault item names
    - line 179: Sentry org slug
  - `website/README.md:33`: personal email and Cloudflare account id.
  - `tools/publish-apk.sh`:
    - line 31: vault item UUID
    - lines 200–216: vault helper
    - line 44: bucket name
  - `tools/whatcan-images/gen.py:7,9`: Azure endpoint and local vault path.
  - `tools/acceptance.py:143`, `planned_acceptance.py:2`, `review_acceptance.py:3`:
    local paths and the tailnet host.
  - `docs/acceptance/*.md`, `docs/bridgit-spec-v2.md` (owner named about 96 times; vault
    mentions at lines 95 and 2184), `docs/scenarios.md`, `ui-spec.md`,
    `spec-agent-inbox.md`.
  - `docs/handover-p1b.md`: internal key-rotation log at lines 51–113 and a session
    path at line 140.
  - `docs/spec-v1.0.pdf`: has a bookmark naming the owner. Regenerate it or drop it.
  - Unit-test fixtures use the real tailnet host and IP: ApiLogicTest:129–169,
    SafeHttpTest, KeysAndAgentsTest, SpeakerRulesTest, CallbacksTest.
- [ ] **[DECIDE] History.** History contains the owner's name about 1,063 times, tailnet
  strings 83 times and the tail IP 145 times. Commit authors are a local machine
  identity and a personal Gmail. See the history decision in §0.
- [ ] **[BLOCKER] ⚖️ License notices are missing from the APK.**
  - `app/build.gradle.kts:75` strips `META-INF/LICENSE*` and `NOTICE*`, and the app has
    no open-source licenses screen.
  - Add one covering Ktor/Netty, AndroidX, CameraX, Material, ZXing, coroutines (all
    Apache-2.0) and Sentry (MIT, including ANRWatchDog).
- [ ] **[BLOCKER] ⚖️ ML Kit is not open source.** `com.google.mlkit:text-recognition`
  is under Google's ML Kit Terms. Disclose it in README and NOTICE as a non-free
  dependency (F-Droid would reject it). The terms say ML Kit sends usage metrics to
  Google and that users must be told, so it also belongs in the privacy policy and
  Data safety. Consider making OCR optional or flavor-gated.
- [ ] **[SHOULD] Fix the build without `.git`.** `writeProvenance` at
  `app/build.gradle.kts:90` runs `git rev-parse` without `runCatching`, so a "Download
  ZIP" checkout fails to configure. Also note in the README that `local.properties` /
  `ANDROID_HOME` is needed.
- [ ] **[SHOULD] Rewrite README.md for the public.**
  - It says 1.3.0; the app is 1.6.0.
  - Drop the "private GitHub release" install steps and the owner-only signing and
    publishing sections.
  - Use a generic tailnet example.
  - Add sections on building from source, the security model and the dependency
    licenses.
  - Fix lines 171–173, which say "no third-party SDKs … the only outgoing call is the
    update check". That is false: Sentry and ML Kit.

### Should add
- [ ] **[BLOCKER for CRA] `SECURITY.md`.** Give a private disclosure address, turn on
  GitHub private vulnerability reporting, and state the supported versions. The app
  exposes a network API and MCP endpoint. The EU CRA requires vulnerability handling
  (§9).
- [ ] **[SHOULD]** Add `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, issue and PR templates,
  `CODEOWNERS` and `CHANGELOG.md` (release notes now live only in GitHub Releases and
  `ApiChanges.kt`).
- [ ] **[SHOULD] CI.** No `.github/` exists. Add a workflow running
  `./gradlew testDebugUnitTest assembleDebug`, `npm --prefix website test`, gitleaks,
  and Dependabot or Renovate. The 161 unit tests pass locally.
- [ ] **[DECIDE] DCO or CLA** before accepting outside PRs. DCO plus a sign-off check is
  the lightest option.
- [ ] **[DECIDE] ⚖️ Rights to assets and code:**
  - AI-generated illustrations: 54 `drawable-nodpi/whatcan_*.webp` files and the
    website feature images (Azure gpt-image). Disclose this and state their licence;
    copyright protection may be weak.
  - Launcher icon and logo: "from the owner's artwork" (README:211). Confirm the
    rights, and decide whether the logo is Apache-licensed or a reserved trademark. If
    reserved, add a trademark note so forks rename.
  - Ported code: "Ported from the Spotka app" (`feedback/ImageDownscale.kt:12`,
    `ui/feedback/LastScreenCapture.kt:30`). Confirm the same owner holds it and can
    relicense it under Apache-2.0.
  - Website font: Bricolage Grotesque (OFL). Add it to the website's notices.
- [ ] **[DECIDE] Internal docs.** Decide whether to publish `docs/reviews/*` (AI review
  transcripts), `docs/handover-p1b.md` and the old specs, or move them out.
- [x] **`launch-kit/` is tracked, minus the private preview `site/`, the zip and the
  tarball** (30 Oct 2026). Private preview links and deployment ids were removed.
- [ ] **[NICE]** Add SPDX license headers to source files. Add placeholders and setup
  docs for `website/wrangler.jsonc` (account id, D1 id) so forks can deploy. Fix
  `website/README.md:45` ("repository was private").

## 3. Google Play: hard blockers

- [ ] **[BLOCKER] Target API 36.** Since 31 Aug 2026, new apps and updates must target
  Android 16 (API 36). `app/build.gradle.kts:22` has `targetSdk = 35`. Raise it, run
  the full regression, and recheck foreground-service and exact-permission behaviour.
- [ ] **[BLOCKER] Remove the self-updater from the Play build.**
  - What it does: `update/UpdateLogic.kt:14` fetches `latest.json` from R2,
    `UpdateInstaller.kt:135,254-260` downloads the APK and opens the installer, and
    `ui/MainActivity.kt:217` checks automatically once a day.
  - Play forbids apps that update themselves outside Play (Device and Network Abuse
    policy).
  - In the `play` flavor, remove `update/`, `REQUEST_INSTALL_PACKAGES`
    (`tools:node="remove"`), the FileProvider `updates` path and the "Check for
    updates" rows (`SettingsFragment.kt:117`, `MoreFragment.kt:50`).
  - Today's gates are inconsistent: `UpdateDialog.kt:40` checks for a `.debug` suffix,
    `AccessFragment.kt:166` checks `BuildConfig.DEBUG`.
- [ ] **Review `SYSTEM_ALERT_WINDOW` for the Play build.**
  - Used only by CAP-56 (open Bridgit when an agent sends something), so a background
    start is allowed. Play treats it as a sensitive permission; either declare it with
    that reason or drop it in the `play` flavor (`tools:node="remove"`), which leaves
    the switch showing "Needs Display over other apps".
- [ ] **[BLOCKER] Move screenshots off the AccessibilityService.**
  - Play's Accessibility API policy prohibits accessibility use that lets an app
    "autonomously initiate, plan, and execute actions". It names general-purpose
    assistants as not being accessibility tools.
  - `capability/Screen.kt:33-60` and `res/xml/accessibility_screenshot.xml` serve agent
    screenshots.
  - In the Play build, use the MediaProjection path that already exists
    (`planned/ScreenSharing.kt`, `ProjectionService`) with a grant the user starts, and
    drop `ScreenshotService` from the manifest.
- [ ] **[BLOCKER] Health Connect.**
  - `planned/Health.kt:24-26` sends steps, sleep and heart rate to agents, which may run
    on third-party LLM backends. `HealthPrivacyActivity.kt:4` itself says the
    provider "may retain a copy".
  - Health Connect allows transfers only with explicit consent, under limited-use
    rules. You also need a Health Connect declaration and a privacy-policy link in the
    rationale activity.
  - Recommendation: leave it out of the first Play release (flavor-gate it), then apply
    later with an explicit per-transfer consent flow.
- [ ] **[BLOCKER] Privacy policy link inside the app.** No link exists in `java/` or
  `res/values/`. Add one to first run, More > About, Settings > Privacy and the Health
  rationale screen.
- [ ] **[BLOCKER] Upload an AAB.** `tools/publish-apk.sh:100` only runs `assembleRelease`.
  Add `bundleRelease` for the `play` flavor and enroll in Play App Signing. Decide
  whether to keep the current signing key as the upload key so it stays continuous
  with the sideload channel.
- [ ] **[BLOCKER] Verify 16 KB page size.** ML Kit and CameraX ship native libraries.
  Check the final AAB with APK Analyzer or `zipalign -c -P 16`. Updates without 16 KB
  support are refused from 1 Feb 2027, and new apps may already be checked.
- [ ] **[BLOCKER] Reviewer access.** `api/ApiServer.kt:131,158` refuses callers outside
  the tailnet, so a reviewer can't exercise anything. Pick one:
  - provide an isolated review tailnet invite and a review agent client (CLI or web),
    with step-by-step App access instructions; or
  - add a clearly labelled on-device demo agent.

  Test the instructions on a clean device first. This also answers the Minimum
  Functionality question.
- [ ] **[BLOCKER] Prominent disclosure before every sensitive grant.** These are bare
  buttons that open Android Settings:
  - accessibility (`AccessFragment.kt:159,259-265`)
  - notification access, usage access, Modify system settings and Health
    (`planned/OwnerToolsActivity.kt:73-80`)

  Each needs an in-app dialog stating what is collected, who receives it (your agents
  and their AI providers) and how to turn it off, with an explicit Accept, before the
  system screen opens.

## 4. Google Play: Console declarations and forms

- [ ] **[BLOCKER] Data safety form.** Rebuild it from the 1.6.0 manifest. The launch-kit
  matrix is from 1.2. Declare as collected and shared:
  - photos and videos, audio, precise location
  - health (if not flavor-gated)
  - app activity (usage stats), messages (notification text), files, clipboard
  - screen content
  - crash logs and diagnostics (Sentry, on by default)
  - ML Kit usage metrics to Google

  Mark it encrypted in transit only where that is true. Tailscale is WireGuard, but
  the API is plain HTTP inside the tunnel, and callbacks are HTTPS.
- [ ] **[BLOCKER] Foreground-service declarations.** Each type needs a description, the
  user impact and a video: camera, microphone, location, mediaPlayback, specialUse and
  mediaProjection.
  - Fix the type misuse first. `BridgeService.kt:91-94` adds `location` whenever the
    permission is held, and `mediaPlayback` on every visible start. Start with
    `specialUse` only, and add a type only while a capture, tour or playback is
    running.
  - The `specialUse` subtype text at `AndroidManifest.xml:124-126` needs a stronger
    justification. Reviewers expect user-started work, and here agents start it
    remotely.
- [ ] **[BLOCKER] Photo and video permissions.** Declare `READ_MEDIA_IMAGES` with the
  agent gallery search as the core use, or use only `READ_MEDIA_VISUAL_USER_SELECTED`
  and the Photo Picker. `READ_MEDIA_VIDEO` (manifest:29) is weak: remove it or use the
  picker or share flow.
- [ ] **[BLOCKER] Notification listener** (`planned/PrivateContext.kt:31-45` sends up to
  4,000 characters of every app's notifications) and **PACKAGE_USAGE_STATS**
  (`PrivateContext.kt:59-75`). These are high review risk. Disclose and justify them,
  or leave them out of the Play flavor.
- [ ] **[SHOULD] Remove permissions that aren't worth defending:**
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (manifest:19): `UiActions.kt:71` already has
    a fallback that needs no permission.
  - `WRITE_SETTINGS` (manifest:34, brightness only).
  - Narrow the `<queries>` launcher intent if possible.
- [ ] **[BLOCKER] Location.** Foreground only; no background location.
  `ACCESS_FINE_LOCATION` needs a declaration from 27 Jan 2027, so prepare it now.
- [ ] **[BLOCKER] Stalkerware positioning.** The listing and app must present Bridgit as a
  tool for the owner's own device and agents, not for monitoring other people. Don't set
  `isMonitoringTool`. Points in the app's favour:
  - a persistent notification, plus one for each capture (`BridgeService.kt:186-223`)
  - captures are refused when notifications are off (`Capability.kt:55`)
  - the launcher icon can't be hidden
  - screenshots are refused while the phone is locked (`Screen.kt:121`)

  Keep all of these, and say so in the review notes.
- [ ] **[BLOCKER] Remaining App content sections:**
  - ads: No
  - target audience: 18+, not designed for children
  - IARC content rating questionnaire
  - health-apps declaration (answer from what the build actually does)
  - news, financial and government: No
  - account deletion: N/A (no accounts), but give a local data deletion path (§5)
- [ ] **[BLOCKER] ⚖️ EU DSA trader status.** Required in Play Console from November 2026.
  A company publisher is a trader, and its address, phone and email are shown to EU
  users.
- [ ] **[BLOCKER] Export compliance answer.** The app uses only standard cryptography
  (TLS, HMAC-SHA256, Android Keystore): mass-market or publicly available, so no BIS/NSA
  notice is needed since the 2021 rule. Play blocks embargoed countries automatically.
- [ ] **[SHOULD] Play Age Signals API.** Integrate it to meet US state app-store laws
  (§9).
- [ ] **[SHOULD] Developer verification for the sideload APK.** Android developer
  verification is enforced in Brazil, Indonesia, Singapore and Thailand since 30 Sep
  2026, and globally in 2027, whatever the download source. Register the package
  `net.finleg.bridget` and its signing key under the verified publisher.

## 5. App code and privacy fixes

- [ ] **[BLOCKER] ⚖️ Crash reports: opt in, not opt out.** Sentry starts by default
  (`core/CrashReports.kt:21`, `BridgeApp.kt:62`) and is mentioned only in Settings. Ask
  at first run (the ingest is in the EU, so GDPR applies), and record the region and
  retention in the privacy policy. Make sure health data, notification text and
  clipboard can never reach Sentry payloads (Washington MHMDA).
- [ ] **[SHOULD] Turn on R8.** `isMinifyEnabled = false` (`build.gradle.kts:56`). Enable
  minify and shrinkResources with keep rules for Ktor, Netty and Sentry. Turning it on
  needs a full regression run.
- [ ] **[SHOULD] "Delete all Bridgit data" in Settings.** It should wipe agents, keys,
  the log, the inbox, transfers and caches. The website `/delete-data/` page should
  describe it.
- [ ] **[SHOULD] Hide unbuilt features in the Play build.** The "Needs your OK" ideas in
  What can (`ui/more/WhatCanData.kt:272-283`: relay, messages/contacts/calendar/calls,
  location history, listen for a word) read as features that exist, which is a
  misleading-claims risk.
- [ ] **[SHOULD]** Remove "sponicgarden/bridgit" from About
  (`res/values/strings_home.xml:98`) or make it the real public repo link. Fix the
  misplaced manifest comment above `READ_MEDIA_VIDEO` (manifest:28).
- [ ] **[SHOULD] Localization readiness.** About 42 English UI strings are hardcoded in
  Kotlin (`OwnerToolsActivity.kt:70-80`, `ShareActivity` 152–170,
  `HealthPrivacyActivity.kt:4`, and the manifest share label at line 94). Move them to
  resources. Translations: [NICE] for launch, since English-only listings can still
  ship globally.
- [ ] **[NICE]** Add contentDescription to images in `addagent_qr_sheet.xml` and
  `feedback_thumb.xml`, then run a TalkBack and Accessibility Scanner pass.
- [ ] **No action needed** (checked): backup off, cleartext off, release builds not
  debuggable, CardWebView locked down (CSP, no file/content/network access, one gated
  bridge), BootReceiver checks the action, ShareActivity validates input, internet
  callbacks are off by default.

## 6. Website (bridgit.cc) and legal pages

### Live site
- [ ] **[BLOCKER] `/download/` and `/features/` return 404 on the live site.** The email
  Worker routes (`/api/download-request`, `/confirm-download/`, `/unsubscribe/`) are
  also not deployed. Merge the two site sources (§0), apply the D1 migration, set the
  secrets, then deploy.
- [ ] **[BLOCKER] Version mismatch.** `website/site.config.json` points to the 1.5.4 APK;
  the app is 1.6.0, and the 1.6.0 APK already exists on R2.
- [ ] **[SHOULD] Serve APKs from a custom domain** (e.g. `dl.bridgit.cc`) instead of the
  `*.r2.dev` dev URL. Cloudflare rate-limits that URL and advises against it for
  production. Update `UpdateLogic.kt` for the sideload flavor.
- [ ] **[SHOULD] Security headers.** There is no HSTS, CSP or frame-ancestors
  (`website/scripts/build.mjs:53`; Worker pages at `email.mjs:5`). Add them, since the
  site distributes an APK.
- [x] The `og:image` social card now uses the approved plum identity (from the
  launch-kit branch). It reaches the live site with the next deploy.

### Privacy policy (`website/public/privacy/`)
- [ ] **[BLOCKER] ⚖️ Name the controller.** Give the legal name, postal address, country,
  registration number, contact and, if needed, the EU/UK representative.
- [ ] **[BLOCKER] ⚖️ Cover every 1.6.0 data category:** notifications, the accessibility
  screenshot service, usage stats, Health Connect (with its own section, which Google
  requires, plus GDPR Art. 9 explicit consent), clipboard, files and downloads, video
  clips, OCR and transcription (on device), sensors and activity recognition, phone
  controls, share-to-agent, MCP and callbacks.
- [ ] **[BLOCKER] ⚖️ Explain the roles.** The user chooses each agent and AI provider and
  is responsible for it. Bridgit sends data only to agents the user paired. Say what
  Sponic does and does not receive.
- [ ] **[BLOCKER] ⚖️ Add the GDPR mechanics:**
  - legal basis for each purpose
  - international transfers (SCCs or DPF for Cloudflare, Sentry, Resend and Google
    ML Kit)
  - retention periods (Sentry, support mail, D1)
  - a security section (Play requires one)
  - rights, response time and supervisory authority
  - a children line (18+, COPPA)
  - US state-law rights and appeal language
  - ML Kit metrics to Google
  - Turnstile as a strictly necessary service, and no cookies or localStorage from the
    site itself
  - Sentry region
- [ ] **[BLOCKER] Make live and repo match.** Both show "28 Oct 2026" but the texts
  differ. Change the effective date whenever the text changes.

### Terms (`website/public/terms/`)
- [ ] **[BLOCKER] ⚖️ Entity and governing law.** Add the entity and address. §10
  (line 30) currently declines to name a governing law; add one and a disputes clause.
- [ ] **[BLOCKER] ⚖️ Liability.** Add a cap, an exclusion of consequential damages and an
  indemnity. §8 currently only says "as available".
- [ ] **[BLOCKER] ⚖️ Acceptable use:**
  - get all-party consent before recording audio or capturing others (e.g. California
    Penal Code §632; about a dozen all-party states)
  - no covert use and no hiding the indicators
  - no monitoring anyone without their knowledge
  - the GDPR household exemption doesn't cover cameras facing public space (Ryneš)
- [ ] **[SHOULD] ⚖️ Disclaimers.** Cover agent actions (purchases, bookings, messages
  sent by agents), add a health/medical "not a medical device" line, and an
  export-control and sanctions clause.
- [ ] **[SHOULD]** Set the minimum age at 18 (Play worksheet). State that the terms cover
  the app, the website and the APKs, while the code itself is under Apache-2.0 (§7
  already handles this well).

### Imprint and other pages
- [ ] **[BLOCKER] ⚖️ Legal notice / imprint page.** Germany (§5 DDG) expects one from
  commercial providers. If a Polish sp. z o.o. publishes, KSH Art. 206 requires the name,
  registered seat, registry court, KRS number, NIP and share capital on the website.
  Add `/legal/` and link it from the footer.
- [ ] **[SHOULD] Developers page.** It lists only the 8-endpoint 1.2 API and says "no
  public download". Update it, or generate it from `/v1/openapi.json`.
- [ ] **[SHOULD] How-it-works and home FAQ.** They say "switches apply across all paired
  agents" and "only photos and audio go to agents"; both are stale.

### Email and consent (`website/email.mjs`)
- [ ] **[BLOCKER before any update email]** No release-update sender exists. Before
  sending one, add:
  - an unsubscribe footer and `List-Unsubscribe` / `List-Unsubscribe-Post` headers
    (Gmail and Yahoo bulk-sender rules)
  - a postal address (CAN-SPAM)
- [ ] **[SHOULD] Keep consent records intact.** `ON CONFLICT` overwrites
  `consent_version` (`email.mjs:73-74`), so a subscriber's record can show a version
  they never agreed to. Keep consent history instead.
- [ ] **[SHOULD] Unsubscribe tokens.** The token is an HMAC of the email with
  `RATE_LIMIT_SALT` (`email.mjs:70`), so rotating the salt breaks links already sent.
  Use a separate, stable key.
- **Already good:** double opt-in, an unchecked box, consent version and time recorded,
  hashed IPs, Resend tracking off.

## 7. Play Store collateral (`launch-kit/`)

Everything in `launch-kit/play-store/` was written for 1.2 (camera, mic, gallery).

- [ ] **[BLOCKER] Rewrite `listing-current.json` for the Play build.**
  - It says "current 1.2".
  - It wrongly says "switches apply to all paired agents"; 1.6 has per-agent
    permissions.
  - Describe only the features left in the Play flavor.
  - Fill in `support_email` (support@bridgit.cc) and `privacy_policy_url`
    (https://bridgit.cc/privacy/).
- [ ] **[BLOCKER] Rebuild `data-safety-matrix.csv`** (see §4).
- [ ] **[BLOCKER] Real screenshots.** None have been captured. The live site's
  1080×2404 captures are 2.23:1, over Play's 2:1 limit. Capture at least 2 (ideally
  4–6) on a clean fixture phone with no personal data or keys, at 1080×1920 or
  similar. Update `screenshots-storyboard.md` for the 1.6 UI.
- [ ] **[BLOCKER] Declaration videos.** Record one per FGS type and one for MediaProjection
  screenshots, plus the reviewer walkthrough. `reviewer-demo.md` covers only the 1.2
  flow.
- [ ] **[BLOCKER] Match the brand.**
  - `play-icon-512.png` and `play-feature-1024x500.png` use the retired bridge/beacon
    mark and lime palette.
  - The app launcher (phone with a B ribbon on plum) and `website/brand/README.md` are
    the approved identity.
  - Regenerate the Play icon from the launcher artwork. Sizes and alpha are already
    correct.
- [ ] **[DECIDE] One product name.** The listing says "BRIDGIT Device Manager", the live
  site "Agentic Android Device Bridge", the app "bridgit: Device Manager for Android".
  "for Android" is the allowed Android trademark form.
- [ ] **[SHOULD] Claims (Play metadata policy and consumer law):**
  - "Turn **any** Android device…" while minSdk is 29: qualify it as Android 10+.
  - Don't lead with the nursery/baby-monitor concept, which doesn't exist; keep
    planned features out of the listing.
  - "Set up in 3 seconds" ignores the Tailscale and pairing prerequisites.
  - The homepage's "Open source · Apache 2.0" claim must match the repo state after
    §0.
- [ ] **[SHOULD] ⚖️ Remove AI vendor logos** (Claude, ChatGPT, Gemini, Grok, OpenCode,
  OpenRouter, Muse) from the live homepage (`/assets/agents/*`) unless you have
  permission. They are not in the repo; they disappear when the site is redeployed
  from `main`. Plain-text mentions are fine. Never use them in Play assets. The
  conversation screenshots in `assets/screenshots/` show the Grok and Muse app icons
  inside the demo cards.
- [ ] **[NICE]** Promotional video (optional for Play).

## 8. Sideload channel (keeps the full feature set)

- [ ] **[SHOULD]** A `sideload` flavor keeps the updater, accessibility screenshots, Health,
  notifications and usage. Its APK, `latest.json` and the website download all point at
  the same custom domain and the same signing key.
- [ ] **[SHOULD]** Play and sideload builds of one package can't both be installed if
  signed differently, and Play App Signing re-signs. Decide whether the Play build gets
  its own applicationId (e.g. `cc.bridgit.app`) or shares `net.finleg.bridget`. Either
  way, document the migration for existing sideload users.
- [ ] **[SHOULD]** Developer verification (§4) applies to this APK from 2027 worldwide.

## 9. Global legal and compliance

- [ ] **[BLOCKER] ⚖️ GDPR / UK GDPR:**
  - controller (§0); EU and UK representatives if the publisher is outside the EU
  - data processing agreements with Sentry, Resend, Cloudflare and Google; a record of
    processing
  - explicit consent for health data
- [ ] **[SHOULD] ⚖️ EU Cyber Resilience Act.** Reporting of actively exploited
  vulnerabilities applies since **11 Sep 2026**: early warning in 24 h, notification in
  72 h. Full obligations apply from 11 Dec 2027.
  - Free, non-monetised open source is outside "commercial activity". But a company that
    collects emails or crash data may be in scope, or count as an open-source steward
    (security policy plus reporting).
  - Adopt SECURITY.md and a vulnerability process now, and get a legal view on
    "manufacturer vs steward".
- [ ] **[SHOULD] ⚖️ EU Product Liability Directive 2024/2853** applies to products placed
  on the market after **8 Dec 2026**, and software counts as a product. Free open source
  is excluded only "outside commercial activity". Supplying software in exchange for
  personal data falls back into scope. Factor this into the Terms liability section.
- [ ] **[SHOULD] US app-store age laws.**
  - Texas SB 2420 is enforceable now (the injunction was stayed by the Fifth Circuit
    in 2026).
  - Utah SB 142 applies since May 2026; Louisiana HB 570 since 1 Jul 2026; California
    AB 1043 from 1 Jan 2027.
  - Integrate Play Age Signals and set an 18+ audience.
- [ ] **[SHOULD] Washington My Health My Data Act** has no revenue threshold. Keep health
  data on the device or with the user's own agent only, never in Sentry or Sponic
  systems. Add the consumer health data privacy notice if health stays in any build.
- [ ] **[SHOULD] Recording laws.** Covered by the Terms acceptable-use section (§6), and
  echo it in-app before the mic and camera are first enabled.
- **Likely not applicable** (re-check if the facts change):
  - CCPA/CPRA and other US state privacy laws: below their thresholds.
  - EU Accessibility Act: a free utility app isn't a listed service, and
    microenterprises are exempt.
  - EU AI Act: Bridgit ships no AI model apart from on-device OCR; Art. 50 targets
    chatbot and generated-content providers.
  - India DPDP: main duties start about May 2027.
  - China PIPL: no Play in China.
  - Brazil LGPD, Japan APPI, Korea PIPA: a GDPR-grade policy covers the notice basics.
    Confirm Korea's location-information rules if the app is promoted there.

## Suggested order

1. §1 now, because the repo is public: the helper IP, the Sentry DSN and the owner's
   name in strings.
2. §0 decisions: entity, history, flavors, name clearance, Play account. Start D-U-N-S
   or the closed-test clock right away.
3. §2: scrub docs and tools, notices, SECURITY.md, CI.
4. §3 and §5: the `play` flavor, API 36, disclosures, privacy link, crash opt-in,
   AAB, 16 KB.
5. §6: legal pages and imprint once the entity is fixed; merge and deploy the site.
6. §4 and §7: screenshots, videos, Data safety and declarations from the final build.
7. Closed test (12 testers for 14 days if a personal account), then production.

## Sources (checked 30 Oct 2026)

- Target API: https://developer.android.com/google/play/requirements/target-sdk
- Accessibility API: https://support.google.com/googleplay/android-developer/answer/10964491
- Device and Network Abuse (self-update): https://support.google.com/googleplay/android-developer/answer/9888379
- Stalkerware / monitoring: https://support.google.com/googleplay/android-developer/answer/12955211 · https://support.google.com/googleplay/android-developer/answer/14745000
- Health Connect / permissions: https://support.google.com/googleplay/android-developer/answer/16558241 · https://support.google.com/googleplay/android-developer/answer/12991134
- Foreground services: https://support.google.com/googleplay/android-developer/answer/13392821
- Testing requirement: https://support.google.com/googleplay/android-developer/answer/14151465 · D-U-N-S: https://support.google.com/googleplay/android-developer/answer/13628312
- DSA trader / Nov 2026 changes: https://support.google.com/googleplay/android-developer/answer/18100766
- Developer verification: https://developer.android.com/developer-verification/guides
- 16 KB pages: https://developer.android.com/guide/practices/page-sizes
- Account deletion: https://support.google.com/googleplay/android-developer/answer/13327111
- Export: https://support.google.com/googleplay/android-developer/answer/113770 · https://bis.gov/node/6731
- ML Kit terms: https://developers.google.com/ml-kit/terms · Sentry SDK licence: https://github.com/getsentry/sentry-java/blob/main/LICENSE
- Android brand guidelines: https://developer.android.com/distribute/marketing-tools/brand-guidelines
- GDPR: https://eur-lex.europa.eu/eli/reg/2016/679/oj · CRA and open source: https://digital-strategy.ec.europa.eu/en/policies/cra-open-source · PLD: https://eur-lex.europa.eu/eli/dir/2024/2853/oj
- §5 DDG: https://www.gesetze-im-internet.de/ddg/__5.html
- Cal. Penal Code §632: https://law.justia.com/codes/california/code-pen/part-1/title-15/chapter-1-5/section-632
- Washington MHMDA: https://lawfilesext.leg.wa.gov/Law/RCW/RCW%20%2019%20%20TITLE/RCW%20%2019%20.373%20%20CHAPTER/RCW%20%2019%20.373%20.010.htm
- Texas SB 2420 status: https://www.pearlcohen.com/fifth-circuit-stays-injunctions-against-texas-app-store-accountability-act/
