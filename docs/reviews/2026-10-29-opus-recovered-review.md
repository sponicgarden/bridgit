# Bridgit `codex/planned-capabilities` (app base 1.5.4): full read-only review

## 1. Is it ready for production?

**No, not yet.** It needs one P1 fix and decisions on four P2 findings first.

Most of the security design is sound. These parts held up when I read them:

- **Auth and key rotation.** Keys are stored as salted hashes, compared in constant time, and kept as tombstones after rotation or removal.
- **Per-agent grants.** The HTTP pipeline and MCP dispatch both check the grant before running a call and again after it finishes. A watcher cancels work in progress if the grant changes.
- **MCP.** MCP itself, pairing, the helper and openapi.json are not tools. A direct `tools/call` still goes through `requireGrant`. Cached replays are scoped to the agent and refused once the switch is off.
- **Network, uploads and updates.** SafeHttp pins the checked address, re-checks every redirect and verifies TLS hostnames. Uploads are admitted before the body is read. The update flow refuses redirects and checks SHA-256, package, version and signing certificate.
- **Files.** Transfers use UUID filenames and are checked for ownership.
- **WebView cards.** The CSP is forced and the card is sandboxed.
- **Error codes.** All 250 error-code call sites use codes that exist in `ErrorCatalog`.

**Limits of this review:**

- **Nothing was run.** Everything below comes from reading the source. Device behaviour for MediaProjection, Health Connect, settings read-back and foreground-service types is not proven (see section 3).
- **The version is still a dev build.** It is `1.6.0-dev`, versionCode 17 (`app/build.gradle.kts:25-26`). Two acceptance checks are stale and would fail (finding 18).
- **Policy choices I accepted as intended:**
  - New agents start with `caps = null`, which means they can use every capability once its master switch is on (`Agents.kt:131`, `:36`).
  - Crash reporting to Sentry is on by default (`CrashReports.kt:21`).
  - With the default log visibility `"all"`, every agent can read every other agent's audit entries.

## 2. Confirmed findings

### P1

**1. OCR always fails: integer division by zero**
- **Evidence:** `planned/MediaFeatures.kt:153`: `BitmapFactory.Options().apply { while (bounds.outWidth / inSampleSize > 4096 || …) inSampleSize *= 2 }`. `BitmapFactory.Options.inSampleSize` defaults to `0`, so the first division throws `ArithmeticException`.
- **Trigger:** any `POST /v1/ocr` (HTTP or MCP) whose image decodes.
- **Result:** HTTP 500 `internal_error` every time. The OCR feature cannot work. The acceptance script only checks the disabled case (`planned_acceptance.py:42`), so it never caught this.
- **Fix:** set `inSampleSize = 1` before the loop.
- **Test:** a JVM/Robolectric or instrumented test that runs OCR on a real 5000×3000 JPEG and a small PNG and expects HTTP 200.

### P2

**2. Callbacks ignore per-agent grants**
- **Evidence:** `interact/CallbackDispatcher.kt:55-62` (`onEvent`) and `:82-85` (`attempt`) check only that a callback URL exists and the agent exists. `GET /v1/events` is refused without the `interactions` grant (`ApiServer.kt:335-338`), but pushes never check any grant.
- **Trigger:** the owner sets a callback URL for an agent, then saves an explicit grant set without `interactions` (or without `tour`).
- **Result:** the agent keeps receiving signed POSTs. These include interaction answers with text values, `tour.tick` with precise location and photo ids, and `clipboard.completed`, `file.received`, `share.created`, `grant.ended`.
- **Who is affected:** the owner's answers and location, sent to an agent whose access was revoked.
- **Fix:** in `attempt()` and `onEvent()`, require `allows("interactions")`. Also require the event family's own capability (`tour.*` needs `tour`, `file.*` needs `files`, and so on). Drop the queued items when a grant changes.
- **Test:** a dispatcher test with a fake store; set caps without `interactions` and assert nothing is delivered.

**3. Tour events keep precise location after the process dies**
- **Evidence:**
  - The running tour is held only in memory (`tour/Tour.kt:116-159`).
  - Tour events are scrubbed only in `finish()` (`Tour.kt:383-397`), via `InteractionStore.scrubTourEvents` (`InteractionStore.kt:236-249`).
  - Events are kept for 7 days (`InteractionStore.kt:296`).
  - `BridgeApp.onCreate` has no startup cleanup.
- **Trigger:** a crash, OOM kill or app update (`MY_PACKAGE_REPLACED`) during a tour.
- **Result:** `tour.tick`, `tour.question` and `tour.photo` events keep full coordinates, compass readings and photo ids for up to 7 days. The agent can read them through `/v1/events` and they are pushed to callbacks. `tour.ended` is never sent. This breaks TOUR-02 ("nothing kept after the tour").
- **Fix:** when `InteractionStore` (or `TourManager`) starts, scrub every `tour.*` event, since no tour can be running after a process start. Optionally emit `tour.ended` with reason `process_restarted`.
- **Test:** an instrumented test that inserts a `tour.tick` with a location, recreates the store and manager, and asserts the location is gone.

**4. Timed phone settings are only restored by an in-process timer**
- **Evidence:** restoration runs only from a main-looper `Handler` tick and `restoreDue(true)` when the process starts (`planned/Hardware.kt:39-43`, `:131-150`). There is no AlarmManager or WorkManager job.
- **Trigger:** an agent sets `POST /v1/phone/dnd {"mode":"none","duration_minutes":60}`, which mutes alarms. Then the Bridgit process stops without restarting: force-stop, the user swiping it away, or an OEM background kill.
- **Result:** DND "none" (alarms muted), a silent ringer, or a changed brightness stays until Bridgit next runs. This can mean missed alarms.
- **Which parts are proven:** the absence of any OS-scheduled restore is source-confirmed. How often the process dies without a sticky restart depends on the device.
- **Fix:** schedule an exact alarm (`setExactAndAllowWhileIdle`) for each lease's `until`, with a receiver that calls `restoreDue(false)`. Keep the startup restore. Consider refusing `mode=none` unless a duration is given.
- **Test:** a unit test that a lease schedules an alarm, plus a device test with `am force-stop` that checks restore at the scheduled time.

**5. City tour ignores the agent's own location and gallery grants**
- **Evidence:**
  - `TourManager.start` checks only global switches through `missing()` (`Tour.kt:235-261`).
  - `tick()` sends precise location and new gallery photo ids and timestamps whenever the global `gallery` switch is on (`Tour.kt:286-305`).
  - The only per-agent check is `allows("tour")` (`BridgeApp.kt:77`).
- **Trigger:** the owner restricts an agent to a set without `location` (or `gallery`) and then starts a tour with it.
- **Result:** the agent gets precise location every interval, and gallery item ids, despite being denied those capabilities. The owner does choose the agent, but nothing on the Tour screen says the agent's grants are overridden.
- **Fix:** add per-agent checks to `missing()`, or to the tick and photo builders: `allows("location")` for location, `allows("gallery")` for `new_photos` and `latest_photo`.
- **Test:** a TourManager test with a denying agent, asserting that start is refused or the tick has no location.

### P3

**6. A file shared into Bridgit is not shown before the owner picks an agent**
- **Evidence:** `ShareActivity` is exported and accepts any `content:` URI (`AndroidManifest.xml:93-100`, `OwnerToolsActivity.kt:146-149`). The dialog shows only an agent list (`:150-151`). It then reads the URIs with Bridgit's own permissions (`:158-160`).
- **Trigger:** any installed app sends `SEND`/`SEND_MULTIPLE` with MediaStore image URIs, or up to 200 KB of injected text.
- **Result:** one owner tap copies gallery items, which the sending app could not read itself, or attacker-written text to an agent. The owner never sees what is being shared.
- **Fix:** show file names, count and a text preview before the choice. Reject URIs whose authority is Bridgit's own. Accept only URIs the caller granted (`getLaunchedFromUid()` / `checkUriPermission`).
- **Test:** an instrumented test launching ShareActivity with a MediaStore URI and no grant flag, expecting refusal.

**7. An HTML interaction image can show any gallery photo without the agent's gallery grant**
- **Evidence:** `interact/InteractionModule.kt:70-77` checks the gallery switch and Android permission, but not `agent.allows("gallery")`.
- **Result:** an agent with only `interactions` can show any gallery photo in a notification and the Interactions UI, and can tell which gallery ids exist (`photo_not_found` versus 201).
- **Fix:** require `app.agents.get(a.id)?.allows("gallery") == true`.

**8. Interaction "speak" ignores the Speaker switch and the agent's speaker grant**
- **Evidence:** `InteractionNotifier.kt:176-187` speaks whenever `speak:true`. Meanwhile `BridgeApp.kt:78` stops every non-speaker agent's audio on every registry change, and `recordUse` fires that on every request.
- **Result:** audio plays when the Speaker switch is off. For an agent without the speaker grant, playback starts and is then cut off by whichever request comes next.
- **Fix:** require `isOn("speaker")` and `allows("speaker")` in `maybeSpeak`, or document that speak belongs to interactions and exempt it in BridgeApp.

**9. Wrong error for missing special access**
- **Evidence:** `Capability.kt:46-59` maps any blocked reason it doesn't recognise to `service_start_blocked` (503, "open Bridgit once"). That covers `notification_access_off` (`PrivateContext.kt:52`), `usage_access_off` (`:64`) and `location_off` (`Location.kt:132`).
- **Result:** agents get a retry-style 503 instead of `access_needed` (403), and the guidance is wrong.
- **Fix:** map `*_access_off` and `location_off` to `access_needed` with a `setting` field.

**10. Owner security actions are not audit-logged**
- **Evidence:**
  - "Allow all capabilities" (`AgentDetailFragment.kt:89`; Save is logged at `:88`).
  - Clipboard approve and deny (`Clipboard.kt:31-45`).
  - Screen-share grant start and end (`ScreenSharing.kt:97-125`, `:148-155`; only an event, no log line).
  - Transfer deletion and Stop/undo (`OwnerToolsActivity.kt:106`, `Hardware.kt:151`).
- **Result:** granting an agent 16 hours of screen access, or approving a clipboard copy, leaves no audit entry.
- **Fix:** add `app.log.system("audit", "grant"/"setting", …)` calls at these points.

**11. A cancelled request is logged as a success**
- **Evidence:** `ApiServer.kt:142-143` starts with `http = 200`, `result = "ok"`. When a CancellationException happens because the client disconnected, `:256-260` rethrows it without setting a status, and `finally` (`:266-282`) logs 200/ok with 0 bytes.
- **Fix:** set `http = 499` (or similar) and `result = "cancelled"` before rethrowing.

**12. Transcription can lose a clip that was already recorded, and can outrun client timeouts**
- **Evidence:** `Modules.kt:238-239` records first, then transcribes. Any failure throws (`Transcriber.kt:40`), so the audio is discarded even with `return_audio=true`, and the capture is still charged. The worst case is 120 s recording + 30 s decode + 45 s recognition. That is beyond the documented "mic: duration + 30 s" timeout (`SelfDescription.kt:89`) and the helper's 170 s limit (`tools/bridgit:72`).
- **Fix:** return the audio with `transcript_error` instead of failing. Document a longer timeout when `transcribe=true`.

**13. Binary media is useless over MCP**
- **Evidence:** `McpProtocol.kt:229` turns non-image, non-audio bytes into a text note. This hits `camera_video` (a capture-flagged recording, `PlannedEndpoints.kt:31`), `gallery_video` and `files_get` / `shares_get` / `downloads_get`.
- **Result:** the video is recorded and the capture is charged, but the agent gets nothing back.
- **Fix:** hide these tools from MCP, or return `data_base64` for small payloads, and say so in the tool description.

**14. The speaker volume ceiling can discard an agent's volume lease**
- **Evidence:** `Speaker.kt:373-389` lowers `STREAM_MUSIC` during playback. If a timed media-volume lease expires during playback, `Hardware.kt:139-141` sees `current != applied`, decides the owner changed it, and drops the lease. `releaseAudio` then restores the agent's value.
- **Result:** the original volume is never restored.
- **Fix:** skip the lease comparison while the speaker owns the volume, or coordinate the two.

**15. Text-to-speech stays broken if it fails once**
- **Evidence:** `Speaker.kt:420-430`. `ready` is a one-shot `CompletableDeferred`. After one failed init (for example, the engine updating), `tts` is non-null and `ensure()` never retries.
- **Result:** `/speaker/say`, tour narration and Play aloud all fail until the app restarts.
- **Fix:** when init fails, reset `tts` and `ready`.

**16. Device tools loses state on rotation or process death**
- **Evidence:** `projectionRequest` and `exporting` are plain fields (`OwnerToolsActivity.kt:28`, `:40`). After a rotation during the consent or save dialog, the result arrives with a null request. The Android consent token is discarded and the request stays pending. A related source-confirmed case: approving after the 5-minute pending window (`ScreenSharing.kt:44`, `:102-103`) also discards the consent.
- **Fix:** keep the request id in saved state, and accept a decision made inside the dialog even if the window passed.

**17. "Open" on a transfer hands agent-chosen MIME types to system handlers**
- **Evidence:** `OwnerToolsActivity.kt:101-104` opens agent-supplied bytes with the agent-supplied MIME. With `application/vnd.android.package-archive`, Bridgit's install-unknown-apps permission (granted for updates) can launch the package installer. Android still asks for confirmation, but this contradicts "No automatic execution" (`WhatCanData.kt:90`).
- **Fix:** refuse or relabel APKs and executable types, or show a warning first.

**18. Stale release and acceptance tooling**
- **Helper check (A07):** the APK serves `tools/bridgit` (`app/build.gradle.kts:83-87`), but A07 compares it with `tools/bridget` (`acceptance.py:223-228`), which is now a 6-line shim. A07 will always fail.
- **Version check (A01):** `acceptance.py:173` requires `^1\.x\.y$`, which `1.6.0-dev` doesn't match.
- **Publish script:** `publish-apk.sh` never checks for a clean tree (`:96-101`). It can publish a dirty build and tag HEAD (`:250-252`), and it would publish versionName `1.6.0-dev`.
- **Fix:** fix both checks, add `git diff --quiet` and a no-`-dev` guard to the script, and set the release version.

**19. User-facing claims don't match the code**
- **Evidence:** these cards say things the code doesn't do. They also go onto the public API page.
  - "Ring and vibrate at full volume, even on silent" (`WhatCanData.kt:242-243`), but ringing respects sound settings (`Hardware.kt:86-88`).
  - "headphones … a guess like driving" (`:227-228`), which isn't implemented.
  - "or blink it" (`:233-234`), which isn't implemented.
- **Also:** all three transfer modules use `cap_files_desc` (`Files.kt:73`), so Shares and Downloads show the Files description.

**20. A Handler tick wakes the main thread every second for the app's lifetime**
- **Evidence:** `Hardware.kt:39`, `:43`. It reads prefs and rebuilds notification state even when there are no leases.
- **Fix:** run the tick only while leases exist, or use the alarm from finding 4.

## 3. NEEDS_VALIDATION (device or OS behaviour; no security severity assigned)

1. **Repeated screen-share captures of a still screen.** `ProjectionService.capture` closes each frame and keeps no copy (`ScreenSharing.kt:131-133`). If the VirtualDisplay produces frames only when the screen changes, a second capture of a static screen times out with `capture_timeout`.
2. **Possible crash when a screen-share start is rejected.** `ProjectionService` is started with `startForegroundService` (`OwnerToolsActivity.kt:34`). On the rejection paths it calls `stopSelf()` before `startForeground` (`ScreenSharing.kt:98-103`). This may trigger Android's "did not call startForeground" crash, which would also take down the listener.
3. **Screen sharing after rotation, single-app consent and locking.** The VirtualDisplay size is fixed when consent is given (`ScreenSharing.kt:116-119`). Check behaviour after rotation, with Android 14's single-app sharing, and after the screen locks during a 16-hour grant.
4. **Health Connect permission request.** Health Connect reads are requested with `RequestMultiplePermissions` (`OwnerToolsActivity.kt:71`). Android 14 may require Health Connect's own permission contract instead.
5. **Settings read-back.** Brightness, volume and DND are read back and compared exactly (`Hardware.kt:139`). Brightness int↔float mapping, ring volume 0 switching the ringer to vibrate, and DND/ringer coupling could make Bridgit treat its own change as an owner change, so no restore happens.
6. **Location after a background start.** After a background start (specialUse type only, `BridgeService.kt:92-93`), fresh location fixes and tour ticks may fail. `LocationModule.blockedReason` (`Location.kt:129-134`) doesn't report this.
7. **File transcription support.** Whether the on-device recognizer accepts `EXTRA_AUDIO_SOURCE` file transcription (`Transcriber.kt:43-49`).
8. **Video with audio.** Video recording with audio from a `LifecycleService`, with the current foreground-service types (`MediaFeatures.kt:56-77`).

## 4. Coverage ledger

**Production Kotlin, read in full (98 main + 1 debug):**

| Package | Files |
|---|---|
| root | BridgeApp, BridgeService |
| `api` | ApiChanges, ApiDocs, ApiServer, Endpoints, Errors, Limits, Mcp, McpProtocol, SelfDescription |
| `capability` | Capability, Location, Media, Modules, Screen, Speaker |
| `core` | Agents, CallbackStore, Callbacks, CrashReports, Keys, Net, PendingKeyVault, SafeHttp, Settings |
| `feedback` | FeedbackModel, FeedbackModule, FeedbackStore, FeedbackSupport, ImageDownscale |
| `interact` | CallbackDispatcher, CardRules, InteractionActionReceiver, InteractionModel, InteractionModule, InteractionNotifier, InteractionStore, LinkActivity |
| `log` | LogModel, LogQuery, LogStore |
| `planned` | Clipboard, Files, Hardware, Health, HealthPrivacyActivity, MediaFeatures, OwnerToolsActivity, PlannedEndpoints, PrivateContext, Rules, ScreenSharing, Transcriber |
| `tour` | Tour, TourNotifier |
| `ui` | MainActivity |
| `ui/activity` | ActivityFragment, ActivitySheets, DayHeaderDecoration |
| `ui/addagent` | AddAgentFragment, AddAgentViewModel, PairCodeFragment |
| `ui/agents` | AgentDetailFragment, AgentStats, AgentsFragment |
| `ui/common` | Common |
| `ui/feedback` | FeedbackUi, LastScreenCapture, MyReportsFragment, ReportDetailFragment, ReportFormFragment, ReportFormViewModel |
| `ui/firstrun` | FirstRunFragment |
| `ui/home` | BeaconDotView, HomeFragment, ListenerTile, UiActions |
| `ui/interactions` | CardWebView, InteractionDetailFragment, InteractionUi, InteractionsAdapter, InteractionsFragment, SpeakHook, TextPanel |
| `ui/more` | AccessFragment, FeaturesFragment, MoreFragment, SettingsFragment, WhatCanData, WhatCanFragment |
| `ui/tour` | AppletsFragment, TourFragment, TourSpeech, TourViews |
| `update` | UpdateDialog, UpdateInstaller, UpdateLogic |
| debug | DebugSetupReceiver |

**Supporting files read in full:**
- Main and debug `AndroidManifest.xml`; debug `strings.xml`.
- `xml/accessibility_screenshot.xml`, `data_extraction_rules.xml`, `file_paths.xml`, `network_security_config.xml`; `navigation/nav_main.xml`.
- `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle-wrapper.properties`.
- `tools/bridgit`, `tools/bridget`, `acceptance.sh`, `acceptance.py`, `planned_acceptance.py`, `publish-apk.sh`, `make-launcher-icon.py`, `whatcan-images/gen.py`, `whatcan-images/convert.sh`.
- Tests read in full: `PlannedRulesTest`, `McpTest`, `ApiLogicTest` (including `ApiChangesTest`).

**Partly read:**
- `tools/spec-page/build_page.py`: lines 1-80 only.
- `values/strings.xml`: only the `cap_*` entries (via grep).
- The other 14 test files: test names only (via grep), not their bodies.

**Not read:**
- All `layout`, `drawable`, `color`, `menu` and `mipmap` XML; the other `values*` files (themes, colors, attrs, bools, the `strings_*` files); `values-night`.
- `website/` (excluded by you).
- Docs were consulted only after my own findings: `spec-per-agent-permissions.md`, the GLM review, and grep hits in `bridgit-spec-v2.md` and `planned-features-implementation.md`.

## 5. Test gaps that matter for release

- **No coverage of the planned Android modules.** Nothing exercises OCR success, video, gallery video, Health, Usage, Notifications, Sensors values, or screen-share capture on a device. This is how finding 1 slipped through.
- **No grant tests outside HTTP and MCP.** There are no tests for grants on callbacks, tour events, interaction images or speak (findings 2, 5, 7, 8).
- **No restart tests.** Nothing checks process death during a tour (finding 3), during a timed setting (finding 4), or during an owner consent dialog (finding 16).
- **No Hardware lease tests.** `change` / `restoreDue` rollback, owner-override and retry logic is untested. A fake `read`/`put` seam would make it testable on the JVM.
- **No pipeline-level tests for `runGuarded`.** Revocation mid-call, timeout mapping and how a cancelled request is logged are untested (finding 11).
- **No ShareActivity tests.** URI provenance and display are untested (finding 6).
- **Acceptance scripts are stale.** A01 and A07 need updating, and the planned acceptance needs positive-path checks for each new capability (finding 18).