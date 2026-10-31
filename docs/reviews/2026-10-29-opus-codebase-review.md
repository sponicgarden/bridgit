# Opus 5.5 source review and release remediation

Review model: `claude-opus-5-5`, pinned for every CLI model slot. Source reviewed: local `codex/planned-capabilities` at `55a4c33`. Read-only CLI review; website excluded. The initial advisor timed out; a resumed report was recovered from the same review context and exposed additional findings. These are two outputs of one review, not independent reviews.

This file preserves the original findings below. They describe the pre-fix source; release resolutions and actual checks are recorded separately at the end.

# Bridgit Android app review: `codex/planned-capabilities` branch (versionName 1.6.0-dev, versionCode 17, built on 1.5.4)

Read-only review. I executed nothing and did not use the device. Prior review docs and specs were read only after my findings were formed. The advisor check timed out, so this report has not had a second review.

## 1. Readiness for production

**Not ready to release.** One planned capability fails on every call (P1-1). Four P2 defects break documented guarantees: idempotent retries, keeping the owner's manual changes to timed settings, MCP video, and the owner's Play aloud.

The core platform holds up under source review:
- Authentication, rotation and tombstones work, and pairing has brute-force limits.
- Per-agent grants are re-checked before a handler runs, during it (a watcher every 200 ms), after it, and on cached replays, over both HTTP and MCP. MCP can't call itself.
- HTML cards are well sandboxed (forced CSP, sandbox header, blocked network, a single `respond` bridge).
- Outbound requests are protected against SSRF (every resolved address checked, pinned connection, redirects re-checked), and the updater fails closed (SHA-256, package, versionCode and signing certificate all checked).
- Every error code used in the source (250 call sites) exists in the catalog.

Limits of this review:
- Source review only. MediaProjection, Health Connect, transcription and timed-settings behaviour on real hardware still needs the device checks in section 3.
- New agents get `caps = null`, meaning **every capability** (`AgentRegistry.add`, `Agents.kt:131`). Per-agent restriction is opt-out. `docs/planned-features-implementation.md` documents this as intended, so I don't count it as a defect. It is still a risk for this release: switching on Notifications, Health or Usage exposes that data to every unrestricted agent.
- I did not read the layout, drawable, menu, colour or theme XML (see section 4).

## 2. Confirmed findings

### P1-1. OCR fails on every request (division by zero)
- **Evidence:** `planned/MediaFeatures.kt:153` runs `BitmapFactory.Options().apply { while (bounds.outWidth / inSampleSize > 4096 …) inSampleSize *= 2 }`. `Options.inSampleSize` starts at 0, so this throws `ArithmeticException` (and `0*2` would never grow anyway).
- **Trigger:** any `POST /v1/ocr` (or the MCP `ocr` tool) once the switch is on.
- **Failure:** every call returns 500 `internal_error`. This is source-confirmed. Device acceptance only tests the switched-off path (`tools/planned_acceptance.py:42`).
- **Affected:** the whole OCR feature.
- **Fix:** set `inSampleSize = 1` before the loop, or reuse `Sizing.sampleSize`.
- **Test:** move the sample-size calculation into a pure function and unit-test 1×1, 4096×4096, 8192×100 and 20000×20000.

### P2-2. Idempotency results are lost whenever the listener rebinds, so a retry can capture twice
- **Evidence:** the cache belongs to each server instance (`api/ApiServer.kt:82`, `private val idempotency = IdempotencyCache()`). `BridgeService.kt:129-133` throws the server away and builds a new one whenever the Tailscale addresses or the port change. The capture rate limiter (`:80`) resets the same way.
- **Trigger:** a Tailscale address disappears and comes back (VPN drop or reconnect), or the owner changes the port. The client retries with the same `Idempotency-Key`, which is exactly when retries happen (`tools/bridgit` retries automatically).
- **Failure:** the new server finds no stored result, so the photo, clip, video, ring or DND change runs a second time. This breaks API-13 as documented ("a retry never takes two photos"). The mechanism is source-confirmed; how often Android/Tailscale changes addresses needs device validation.
- **Fix:** move `IdempotencyCache` (and the capture `RateLimiter`) into `BridgeApp` or `BridgeService` so it outlives server instances.
- **Test:** an instrumented test that finishes a keyed request, rebuilds the `ApiServer`, then replays.

### P2-3. A second timed change overwrites the owner's manual setting with a stale value
- **Evidence:** `planned/Hardware.kt:117-118` keeps the old lease's `before` (`previous?.getInt("before") ?: before…`) without checking whether the setting still equals `previous.applied`.
- **Trigger:**
  1. An agent sets DND "none" for 60 minutes (saved value: off).
  2. The owner manually switches DND to "priority".
  3. An agent sets DND "alarms" for 10 minutes.
  4. When that runs out, `restoreDue` sees current == applied and restores **off**.
- **Failure:** The owner's manual "priority" is silently lost, breaking "Later manual changes are preserved" (`WhatCanData.kt:86`). Source-confirmed.
- **Affected:** The owner's DND, ringer, volume and brightness.
- **Fix:** only reuse `previous.before` when `before[k] == previous.applied`; otherwise save the current value.
- **Test:** move the lease merge/restore decision into a pure function and test this exact sequence.

### P2-4. MCP `camera_video` records a real video, then returns nothing usable
- **Evidence:** `PlannedEndpoints.kt:31` returns raw `video/mp4`. `McpTools.result` (`api/McpProtocol.kt:229`) turns any non-image/audio/JSON/text reply into "binary; use the HTTP endpoint", and `camera_video` is still listed in `tools/list`.
- **Trigger:** an MCP agent calls `camera_video`.
- **Failure:** the camera records for up to 30 s with the notification showing, and the MP4 is thrown away. `gallery_video` and `files_get` (non-image files) are unusable the same way, but they don't capture anything.
- **Affected:** The owner (a pointless capture) and MCP agents.
- **Fix:** leave capture endpoints with binary results out of MCP, or return an MCP resource/blob.
- **Test:** a `McpTest` check that every listed capture tool's result type is representable.

### P2-5. The owner's "Play aloud" stops whenever any agent makes a request
- **Evidence:** Play aloud queues speech with agent id `"owner"` (`BridgeApp.kt:85-87`). The registry listener at `BridgeApp.kt:79` stops any current speech whose agent id isn't registered, so it stops `"owner"`. That listener runs on every authenticated request, because `ApiServer.kt:170` calls `recordUse`, which calls `changed()` (`core/Agents.kt:191`).
- **Trigger:** The owner taps Play aloud while any agent is polling (`/v1/events`, a text stream, a tour).
- **Failure:** speech is cut off and the owner's queued items are dropped. Source-confirmed.
- **Fix:** skip `"owner"` in that check, or only stop speech for agents that were actually removed.
- **Test:** a unit test of that listener with a fake registry.

### P3-6. Tour location data survives a process restart (breaks TOUR-02)
- **Evidence:** tour state lives only in memory (`tour/Tour.kt:116-159`). Events are scrubbed only in `finish()` (`:383-397`), and `TourManager.init` (`:207-210`) does no recovery.
- **Failure:** if the process dies mid-tour, `tour.tick` and `tour.question` events keep their location and compass data for 7 days (`InteractionStore.EVENT_KEEP_MS`), the agent can read them again through `/v1/events`, and no `tour.ended` is sent.
- **Fix:** at startup, scrub tour events whose `tour_id` isn't the current tour, and emit `tour.ended(reason=restart)`.

### P3-7. `image/last?wait` returns a photo after the Gallery switch was turned off during the wait
- **Evidence:** `capability/Modules.kt:298` checks the switch once, before the 55 s wait (`:312-323`). `runGuarded` only cancels for a switch change on capture endpoints (`ApiServer.kt:310`), and its after-check (`:318`) only re-checks the grant.
- **Fix:** call `mode()` (or `requireUsable()`) again after the wait.

### P3-8. Interaction `speak` ignores the Speaker switch and the agent's speaker grant
- **Evidence:** `interact/InteractionNotifier.kt:176-187` queues speech without checking `isOn("speaker")` or `allows("speaker")`. A revoked agent's speech is only stopped by the next registry change (`BridgeApp.kt:78`), so it's racy.
- **Fix:** check both before `speakText`, or document `speak` as part of the Interactions permission and stop the racy cleanup.

### P3-9. `image.gallery_id` in an interaction ignores the agent's gallery grant
- **Evidence:** `interact/InteractionModule.kt:70-77` only checks the global switch, while OCR checks the grant (`MediaFeatures.kt:142`).
- **Impact:** an agent without the gallery grant can have any of the owner's photos shown in a notification, and can tell which ids exist (`photo_not_found`). Tour ticks similarly send photo ids to the tour agent even without the gallery grant (`Tour.kt:290-305`).
- **Fix:** add `allows("gallery")` to both.

### P3-10. The callback-secret dialog can be screenshotted (no FLAG_SECURE)
- **Evidence:** `ui/agents/AgentDetailFragment.kt:223-249` shows the secret in a dialog without `FLAG_SECURE`. The setup prompt and its bottom sheets are secured.
- **Impact:** an agent with Screenshots or an active screen-share grant can capture agent B's secret and forge signed callbacks to B (for example a fake `interaction.answered`). This needs timing.
- **Fix:** add `window.addFlags(FLAG_SECURE)` to that dialog.

### P3-11. If transcription fails, the recorded audio is lost
- **Evidence:** in `capability/Modules.kt:238-239`, if `Transcriber.transcribe` throws (any recognizer `onError`, `Transcriber.kt:40`), the request fails even with `return_audio=true`. The mic was used and nothing comes back. Source-confirmed; which recognizer errors trigger it is in NV5.
- **Fix:** on failure, return the audio with `transcript: null` and a `transcript_error` field.

### P3-12. Device tools (`planned/OwnerToolsActivity.kt`) lose state and trust the agent's MIME type
- **Lost state:** `projectionRequest` (`:28-39`) and `exporting` (`:40-44`) are plain fields. Rotating the phone or a process death while Android's consent dialog or the save picker is open silently discards an approved grant or the export.
- **MIME type:** "Open" uses the MIME type the agent supplied (`:101-104`). An agent-sent `application/vnd.android.package-archive` opens Android's installer under Bridgit's install-apps permission (the one used for updates), so it is one confirmation away from installing.
- **Quota:** the transfer quota is shared by all agents (`planned/Files.kt:32-33`), so one agent can block the owner's shares and downloads.
- **Fix:** save the request id and export id in `savedInstanceState`; refuse or neutralise APK MIME types on Open; add a per-agent share of the quota.

### P3-13. The share sheet doesn't show what is being shared
- **Evidence:** `ShareActivity` (`OwnerToolsActivity.kt:139-169`) is exported for `*/*`. It opens any `content://` URI from the sender using Bridgit's own media permission, and the agent picker shows no file names or count.
- **Impact:** a malicious app can make the owner unknowingly forward a gallery photo to an agent. The owner still has to choose the agent.
- **Fix:** show file names, sizes and count before the agent choice.

### P3-14. Flashlight state can get out of sync and block the camera
- **Evidence:** `planned/Hardware.kt:64-80` has no torch callback. If the torch is switched off from Quick Settings, Bridgit still holds the camera lock until its auto-off timer (up to 600 s).
- **Impact:** snapshots and video return `sensor_busy` in that window, and the "Flashlight on" notification is wrong.
- **Fix:** register `CameraManager.TorchCallback`.

### P3-15. A tour can start with an agent that isn't allowed to run one
- **Evidence:** `TourManager.start` (`Tour.kt:244-261`) and the agent picker (`ui/tour/TourFragment.kt:258-290`) don't check `allows("tour")` or `allows("interactions")`. The tour then ends at the next unrelated registry change ("Agent access revoked"), after sending location.
- **Fix:** filter the picker and check in `start()`.

### P3-16. "Allow all capabilities" isn't written to the audit log
- **Evidence:** `AgentDetailFragment.kt:89` doesn't log it, and "Save" (`:88`) logs no before/after.
- **Fix:** log both changes with the old and new sets.

### P3-17. Release metadata and tooling are out of step
- **Version strings:** `app/build.gradle.kts:26` is `1.6.0-dev`, and `ApiChanges.kt:63` records revision 8 as `1.6.0-dev`.
- **Acceptance A01** (`tools/acceptance.py:173`) requires `^1.x.y$`, so it fails on `-dev`.
- **Acceptance A07** (`:223-228`) compares the served helper with `tools/bridget`, which is now an exec shim, while the APK serves `tools/bridgit` (`app/build.gradle.kts:84`), so A07 fails.
- **Publish script:** `tools/publish-apk.sh:117` accepts `-dev` and dirty builds. With `--apk` or `--no-build`, it tags HEAD (`:249-252`) whatever commit the APK was built from.
- **Fix:** set the final version; fix A01 and A07; make publishing refuse `-dev`, `DIRTY_BUILD`, or a `GIT_COMMIT` that doesn't match HEAD.

## 3. NEEDS_VALIDATION (on the device; no security severity assigned)

- **NV1.** `ProjectionService.onStartCommand` calls `stopSelf()` before `startForeground()` when the request has expired or been revoked, or the switch is off (`ScreenSharing.kt:98,103`). It was started with `startForegroundService`, which may trigger Android's "did not call startForeground" crash. Check: approve a request after its 5-minute expiry. If it crashes, call `startForeground` first and then validate.
- **NV2.** MediaProjection capture uses a fixed width×height (`ScreenSharing.kt:116-119`) and `acquireLatestImage()` (`:131`). Check two things: a second capture on an unchanged screen may get no new frame and time out after 5 s; and rotating or resizing the screen during an hour grant may give distorted frames.
- **NV3.** `restoreDue` compares the read-back value with the requested index (`Hardware.kt:139`, `:221`). If Android clamps the value (for example alarm volume can't go below 1, or ring 0 switches the ringer), restoration is treated as an owner change and dropped, which could leave the alarm at minimum. Store the value read back after the write.
- **NV4.** Health Connect. Check whether granting through `RequestMultiplePermissions` (`OwnerToolsActivity.kt:71`) actually grants on Android 14, and whether reads succeed while Bridgit is in the background with only the foreground service running.
- **NV5.** The on-device recognizer, fed from a file, may return only the first utterance of a long clip (no segmented session), and may report an error on silence, which triggers P3-11.
- **NV6.** After a boot or background restart, the listener runs without the location service type, so `/v1/location` and tour ticks may fail until the app is opened. This exists in earlier versions too.
- **NV7.** Timed settings are only restored while the process runs. Every process start restores all of them early, and nothing (such as an alarm) restores them if the process isn't restarted.

## 4. Coverage ledger

**Production Kotlin, read in full (102 files):**
- `BridgeApp`, `BridgeService`
- `api/*` (9 files)
- `capability/*` (6)
- `core/*` (8)
- `feedback/*` (5)
- `interact/*` (8)
- `log/*` (3)
- `planned/*` (12)
- `tour/*` (2)
- every `ui/**` file (46, including `WhatCanData` in three parts)
- `update/*` (3)
- `debug/DebugSetupReceiver.kt`

**Config and XML read:**
- the main and debug manifests, debug `strings.xml`
- `xml/accessibility_screenshot`, `data_extraction_rules`, `file_paths`, `network_security_config`
- `navigation/nav_main`
- `values/strings.xml` (searched for the `cap_*` strings only)
- all five Gradle files

**Tests:** `PlannedRulesTest`, `McpTest` and `ApiLogicTest` read in full. For the other 14 test files I read only the test names.

**Tools:** `bridgit`, `bridget`, `acceptance.sh`, `acceptance.py`, `planned_acceptance.py`, `publish-apk.sh`, `make-launcher-icon.py`, `whatcan-images/gen.py` and `convert.sh` read in full. `spec-page/build_page.py` read in part (first 80 lines; it only generates documentation).

**Docs, consulted after forming findings:** `spec-per-agent-permissions.md`, the GLM review, and searches in `bridgit-spec-v2.md` and `planned-features-implementation.md`.

**Not reviewed:** all of `res/layout`, `drawable`, `menu`, `color`, `mipmap`, `values-night`, `themes.xml`, `styles_interact.xml`, the remaining `values/strings*.xml` and `attrs`/`bools`/`colors`; images; `website/`.

## 5. Test gaps that matter for this release

- **Nothing exercises the OCR decode path.** Device acceptance only tests "switched off" for OCR and video, which is why P1-1 got through.
- **No tests for the request pipeline:** grant revocation during a request, replay after revocation or switch-off, parity between HTTP and MCP dispatch, and replay after a listener rebind (P2-2).
- **The Hardware lease logic is untested** (P2-3, NV3). It should be moved into pure, unit-tested code.
- **No positive device checks** for timed controls and Undo, flashlight, ring, sensors, screen-share approval/capture/expiry, Health, Usage, Notifications, transcription, the share target, owner clipboard approval, or MCP media/video results.
- **Acceptance A01 and A07 fail on this branch** (P3-17).
- **The A28 secret scan misses some secrets:** it doesn't check `cbs_` callback secrets, the `callbacks`, `clipboard_requests` and `transfers` settings files, or `interactions.db`.
- **No regression test for owner Play aloud continuing while agents are active** (P2-5).

## Release remediation and verified scope

The original reports above and in the recovered-report file describe the pre-fix implementation. This release fixes their confirmed defects:

| Finding area | Resolution |
|---|---|
| OCR | Nonzero, bounded sampling; small and 8,193-pixel-wide images recognised on the Pixel. |
| Listener lifecycle | Process-owned idempotency/rate/wait/upload state; duplicate file POST returns the same ID after a real port rebind. |
| Timed settings | Preserve intervening owner settings, persist Android read-back, coordinate media-volume restoration with speaker ceiling, schedule only active deadlines, add an inexact Android alarm backup. |
| MCP media | Binary results are embedded resource blobs with MIME and exact bytes; live PDF round trip passes. |
| Speech | Owner speech survives registry activity; agent speech checks switch/grant at queue admission and playback; failed TTS initialization can retry. |
| Tours and events | Startup scrubs interrupted tours; tours require Tour/Location/Interactions; gallery metadata checks Gallery; pull and push events check family grants; callback retries reload scrubbed stored payloads. |
| Owner UI | Secure callback-secret dialog; saved picker/request IDs; share preview; APK upload/open refusal; owner grant/clipboard/screen/file/control actions audited. |
| Transfers | Separate agent and remote aggregate quotas leave room for owner shares/downloads; early failures close input streams. |
| Capture/access | Gallery waits recheck consent; transcription errors retain requested audio while cancellation propagates; special-access errors are actionable; external torch changes release the camera lease. |
| Audit and claims | Client disconnects log cancellation; capability cards match implemented behavior and transfer descriptions; transcription timeout guidance and helper timeout updated. |
| Release | Stable version 1.6.0/code18; canonical helper acceptance; clean exact-commit provenance embedded in APK and verified before publishing; current manifest validation fails closed; public APK hash verified before advertising it. |

Validation on the release worktree: debug APK assembly, **159 JVM tests** (zero failures/errors), lint, Python APK-provenance tests, Bash syntax and `git diff --check` passed. Lint has existing nonfatal warnings. Website changes: zero.

Pixel validation used the dedicated `cc.bridgit.app.review` package and dummy agents only. The existing release and ordinary debug packages were not replaced or reset. All 22 focused API/security checks passed. Additional checks passed: positive OCR on 1200×400 and 8193×512 text fixtures; binary PDF bytes through MCP; APK upload refusal; same file result after listener rebind from 8737 to 8738. Fixture agents were removed, switches disabled and listener paused afterward. Reproduce the additional checks with `python3 tools/review_acceptance.py --isolated-review-app`; the committed fixtures contain only “BRIDGIT 2026”.

Device limitations remain explicit: MediaProjection still-screen/rotation/lock and expired-consent behavior, Health Connect grants/background restrictions, speech-engine file recognition, real camera/video/audio capture and timed-setting restoration under OS killing are not fully accepted on-device. The attempted direct launch of the private Device tools activity was correctly refused by Android; it is not a projection acceptance pass. Timed setting deadlines after process death use inexact Android alarms and can be delayed; force-stop cancels alarms until the app next runs. Startup restores pending controls early. These limits are documented rather than promised away.

The earlier reports are one full source review and a same-context recovered output, not two independent reviews. A separate fresh Opus 5.5 invocation verifies the fixes before publication.


## Fresh verification follow-up

The independent fresh Opus pass is preserved in `2026-10-29-opus-verification.md`. It found no blocking app source bugs, but identified one release-gate bypass and eight smaller follow-ups. Before shipping:

- Publication requires the checked commit to be on freshly fetched `origin/main`, including `--no-github`; the same commit is used for the release target.
- Multi-setting rollback compares actual Android read-back and retains failed rollback leases for retry, with truthful error text.
- Torch tracking consumes delayed notifications from its own off calls.
- Only a started projection emits an ended grant; failed/expired requests retain their statuses.
- MCP attachment allocation is bounded at 8 MB; larger GETs return authenticated HTTP resource links and MCP video records at most 6 MB.
- Cancelled MCP dispatches log 499/cancelled.
- Eligible tour agents need Speaker too, and the empty picker explains missing permissions.
- Tour startup recovery tolerates malformed rows and runs after callback registration, before the listener can start.
- Restoration retries pause after twelve failures while keeping owner Undo available. Owner action audit lines are explicit, and service shutdown is not attributed to an owner tap.

The full-source verification was followed by a focused final check of these changes.

The focused final Opus report is in `2026-10-29-opus-final-verification.md`. Its only additional P3 finding (retaining the original owner baseline when a rollback itself fails) was fixed using the recommended shared baseline rule before publication.

A concurrent session edited onboarding files after the first review-fix commit. Those edits were preserved in the earlier worktree and excluded from this release by using a separate clean production checkout.
