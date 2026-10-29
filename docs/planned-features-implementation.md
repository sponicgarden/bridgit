# Planned capabilities: Bridgit 1.6.0

This development branch starts from app 1.5.2 and merges the latest main (including 1.5.4 branding/fixes) and implements the catalog's 19 planned/later cards, including the already present City tour. Four IDEA cards remain out: relay, local trigger words, communications, and location history/places. The app-only release is version 1.6.0, code 18. New feature switches start off. The website is excluded from this release.

## Phone interface

- **More → Features**: master switches and readiness for the new modules. Every new switch starts off.
- **More → Features → Device tools, files and special access** (also reachable from Access): review clipboard requests, screen-sharing requests, received/shared/selected-download files; export/delete/open files; stop flashlight/ring; undo timed settings; open each relevant Android access page.
- **Interactions → Files / Shared**: open the corresponding transfer history. Questions/notices retain their current list.
- **More → Agents → agent → Permissions**: choose capabilities. The list includes Log, Feedback and City tour, as well as switched features. An explicit grant set denies future unknown capabilities; legacy unrestricted agents retain their existing access until edited. “Allow all capabilities” explicitly restores unrestricted access. Master switches and Android permissions still apply.
- Android's **Share → Bridgit**: choose one agent and copy up to ten selected content files, or shared text/links, into its private transfer history. Each agent reads only its own transfers.

## Implemented API and bounds

All paths have `/v1` prefix, a per-agent bearer key, normal request/audit handling, and generated guide/OpenAPI/MCP entries. The complete parameter reference is generated from EndpointSpec and `PlannedEndpoints` and is available on the phone at `/v1/guide` and `/v1/openapi.json`.

| Catalog card | API / implementation |
|---|---|
| Inbox | Questions/notices plus Files/Shared entry points in Interactions. Transfer histories are separate views in Device tools. |
| Phone status and context | `GET /phone/status`, `/phone/context`. Battery, network transport, screen/lock and sound. Context reports `in_use` only for an interactive unlocked screen; otherwise `unknown`, with its rule. No speculative driving/sleep inference. |
| Sensors | `GET /sensors?which=light,pressure,proximity,steps,orientation,motion`. Up to 1.1 seconds per selected sensor; values, accuracy, age, or explicit unavailable/no-fresh-event. Steps need Physical activity permission. Motion is a fresh acceleration vector; orientation is a rotation vector. |
| Flashlight | `GET /flashlight`, `POST /flashlight {on,auto_off_seconds}`. 1–600 seconds (default 60), shared camera lock, visible ongoing control notification, owner Stop, listener/switch-off cleanup. |
| Sound/display | `POST /phone/dnd`, `/phone/sound`, `/phone/brightness`, `/phone/undo`. DND/Modify settings special access as required. Optional 1–1440 minute restoration; successive changes retain the baseline unless the owner changes it. Android read-back handles clamped values. A running-process deadline and an Android inexact alarm restore it; startup also restores early. Force-stop cancels Android alarms, and power/background restrictions can delay them: do not rely on this as an alarm-clock guarantee. Subsequent manual changes are preserved. Undo is also on the owner notification. No radio toggles. |
| Find my phone | `POST /phone/ring {duration_seconds}`, `DELETE /phone/ring`. 1–60 seconds, default 15; ring/vibration respect Android sound/DND policy. Stop in Device tools/notification. No full-screen intent or emergency override. |
| Videos/downloads | `GET /gallery/videos/recent`, `/gallery/video/{id}` (raw up to 40 MB; optional `frame_at_s` JPEG). Separate gallery-video grant and Android accessible-media permissions. Downloads are owner-selected with Android's document picker, privately copied to the chosen agent; `GET /downloads[/{id}]`. No arbitrary Downloads browsing or caller-supplied content URIs. Gallery photo listing also gains provider-side type, album, since/until filters. |
| Receive files | `POST /files`, `GET /files[/{id}]`, `DELETE /files/{id}`. Raw-body uploads use `filename` query (HTTP `name` also accepted); JSON uses name, MIME and data_base64. Private UUID storage; owner opens or saves a copy via Android. 25 MB/file, 100 files/100 MB total, 30-day retention. Agent uploads have a separate 25 MB / 25-file budget per agent and 50 MB / 50-file combined budget, reserving capacity for owner shares and downloads. App packages cannot be uploaded or opened here; use the verified updater. No automatic opening/execution. |
| Share with agent | `GET /shares[/{id}]`, `DELETE /shares/{id}`. Android share target shows names, sizes, count and shared text before explicit recipient selection. `share.created` events use the existing per-agent event/callback channel. |
| Short video | `POST /camera/video {camera,duration_seconds,audio,resolution}` → raw MP4. 1–30 seconds; 720p or 1080p; audio defaults off and needs the microphone master switch and agent grant. Camera/mic locks and foreground readiness apply. 40 MB hard recording cap. |
| OCR | `POST /ocr {gallery_id}` or `{data_base64}` → Latin-script text, processed by bundled ML Kit on the phone. Gallery source also needs this agent's gallery grant. Decoding is downsampled to 4096 pixels per side. Other scripts are outside this build. |
| Transcription | `POST /mic/record {duration_seconds,transcribe:true,return_audio,language}`. Existing AAC recording is decoded locally to PCM for `createOnDeviceSpeechRecognizer`; default still returns audio. No cloud-recognizer fallback. Availability depends on installed on-device service/language and support for file-source recognition; unsupported engines return `not_supported`. If recognition fails after recording and `return_audio=true`, the audio is retained with `transcript:null` and `transcript_error`; cancellation/revocation still stops the request. Allow duration + 90 seconds for transcription. Not continuous listening. |
| Screen-share fallback | `POST /screen/grant {duration_seconds:0\|3600\|57600}`, `GET /screen/grants`, `POST /screen/share/capture`, `DELETE /screen/grant`. Request returns 202; owner reviews in Device tools and approves Android's own MediaProjection prompt. Grant belongs to one agent. Once means one capture within five minutes; timed grants end earlier if Android stops them (including lock/power behavior). One active grant/device. Visible foreground notification with Stop. Existing Accessibility screenshot path remains separate. |
| App usage | `GET /usage?since=&until=`. Usage-access grant, maximum seven days/100 packages. Android buckets may extend beyond requested interval; no claim of exact minute boundaries or live foreground-app detection. |
| Health Connect | `GET /health/records?type=steps\|sleep\|heart_rate&since=&until=`. Android 14+ platform Health Connect, explicit read grants, maximum seven-day query/100 source records. No writes, workouts, background reads or retained health records. Source records can overlap; these are not deduplicated step totals. |
| Notifications | `GET /notifications`. Owner enables separate Android notification access and feature switch. At most 100 active notifications since listener connection, RAM only; excludes own-package and secret-visibility notifications; clears on pause/disable/disconnect. Android can further redact sensitive fields. |
| Clipboard | `POST /clipboard {text}`, `GET /clipboard/{id}`. Five-minute request; exact text shown on phone; only the owner's Copy tap writes it while foreground. Denied/expired text is removed; old status records pruned after a day. No clipboard reads. |
| Per-agent permissions | HTTP and MCP check current grants before execution and again before returning data. Revocation/key rotation cancels active coroutine operations; active projection ends on grant/switch/agent changes. Explicit sets round-trip through agent storage. Pulled and pushed events require Interactions plus their family grant. City tours require Tour, Location, Speaker and Interactions; gallery metadata needs Gallery. Owner grant changes are audit logged. |

`file.received`, `share.created`, `clipboard.completed`, and `grant.ended` use the existing event delivery. No new webhook destination or cloud infrastructure is configured. Request bodies containing file/image/text payloads are excluded from audit params.

## Historical validation and review (implementation branch)

Verified on the final source:

- `assembleDebug`, `testDebugUnitTest`, and `lintDebug`: passed; 150 tests, zero failures/errors. Lint has existing/nonfatal warnings; it is not warning-free.
- Website build, 16 Node tests, and `check`: passed; nine pages and 267 local links/assets verified.
- `git diff --check`: passed.
- GLM 5.3 Flash performed an initial review and a focused second review. Its P0/P1 findings were fixed; the second review reported none remaining. Remaining actionable race/cancellation issues were corrected afterward.
- A separate `.planned` application was installed during testing; it does not replace `net.finleg.bridget` or `net.finleg.bridget.debug`. The installed test copy is an earlier iteration, not the final APK. A concurrent-build/network install attempt failed to parse its APK; the final APK is available locally. The isolated app was explicitly stopped.
- Live Pixel acceptance is **pending**. USB disconnected; the network-connected device reported **Device locked**, and the ADB test setup did not open a pairing window. No successful live API/sensor/owner-approval acceptance is claimed. Release/debug user app data were not reset or overwritten.

The final debug APK uses `net.finleg.bridget.planned`, port `8736`, version `1.6.0-dev` / code `17`, using the isolated build command below. Default debug builds retain their ordinary `.debug` suffix/8735 port unless those properties are supplied. New feature switches default off.

`tools/planned_acceptance.py --isolated-planned-app` is an opt-in starter harness for the dedicated test package; it has not passed on the locked Pixel. It never targets the release/debug packages.

Remaining device checks: actual camera/video and microphone capture, offline transcription with installed language support, OCR known-image accuracy, selected-media/Downloads and share/export round trips, clipboard approve/deny/expiry, screen grant once/timed/revoke/lock/rotation, torch/ring stop and timeouts, timed setting restoration after restart/manual override/permission loss, usage and Health Connect permission grant/revoke, notification-memory cleanup, and per-agent HTTP/MCP revocation during active work. On-device speech language support, MediaProjection OS termination, special-access behavior, camera hardware, and Health Connect data availability are device-dependent. A compiled path is not a promise of completed acceptance on every device.

For isolated device testing without replacing either the installed release or debug app:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PbridgetApplicationIdSuffix=.planned -PbridgetDebugPort=8736
```

GLM 5.3 Flash reviewed the implementation. Its actionable findings and resolutions are recorded in `docs/reviews/2026-10-28-glm-planned-features.md`.

## External API references

- Android SpeechRecognizer: https://developer.android.com/reference/android/speech/SpeechRecognizer
- Android foreground service restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Bundled ML Kit text recognition: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- CameraX video capture: https://developer.android.com/media/camera/camerax/video-capture
- Health Connect: https://developer.android.com/health-and-fitness/guides/health-connect

The earlier implementation pass was local. The subsequent 1.6.0 release and Opus review are recorded in `docs/reviews/2026-10-29-opus-codebase-review.md`.


## Pixel follow-up acceptance, 29 October 2026

After the owner unlocked the Pixel, the final code-17 `net.finleg.bridget.planned` APK was installed successfully over the network using a stable APK copy and `adb install --no-streaming -r`. This supersedes the earlier locked-device/failed-install status above. The release and ordinary debug packages were untouched.

`python3 tools/planned_acceptance.py --isolated-planned-app` passed **22 live checks** on the Pixel 10 Pro XL:

- Authenticated health, missing-key refusal, phone status and context.
- Real light/pressure sensor responses and invalid sensor-name rejection.
- Disabled OCR and video refusal.
- Exact-byte file receive/read/delete, traversal-name rejection and removed-file 404.
- Clipboard request remains pending until an owner decision; debug owner-denial hook closes it as denied.
- Generated OpenAPI and MCP discovery.
- Separate-agent file-read and file-delete isolation.
- Live HTTP per-agent grant changes, hidden MCP tools, and refused direct MCP calls for ungranted capabilities.
- Screen-share capture without consent returns an actionable 403.

The harness was corrected to wait for the asynchronously started listener before pairing. A first attempt raced listener startup; clean startup plus readiness/settling resolved it without app-source changes. Test-agent keys stayed in process memory and were not printed or written to a credential file. Both test agents were removed and the fixture's phone/files/clipboard/sensors/screen-share switches and listener were turned off afterward.

Still unverified: live camera/video/audio capture, speech recognition, positive OCR accuracy, actual Android consent/picker and clipboard Copy UI, positive screen-projection capture/revocation, hardware-control restoration, Health Connect data and notification/usage special access. The 22 checks are a focused API/security acceptance run, not full acceptance of every planned capability.

Pixel screenshots, website visuals and Play Store collateral were moved at the user's request to a separate chat titled **Pixel screenshots and Play Store collateral**. That work has its own brief; no collateral is being produced in this chat.


## 1.6.0 release review

Opus 5.5 reviewed the local Android source through the installed Claude CLI; the website was excluded. Original findings and the recovered same-context report are in `docs/reviews/2026-10-29-opus-codebase-review.md` and `2026-10-29-opus-recovered-review.md`. A fresh Opus invocation verifies remediation.

MCP returns media up to 8 MB as embedded content or resource blobs. Larger GET results return HTTP resource links requiring the existing Bearer key. MCP video recording has a 6 MB file limit and may end before the requested duration; HTTP retains the 40 MB limit. Request rate limits, wait admission and idempotency caches outlive listener rebinds, within the same process. Process death still clears these in-memory caches.

Timed settings coordinate with the speaker's temporary volume ceiling. The owner can always Stop/undo, and manual changes remain authoritative. Do Not Disturb may mute alarms; Android scheduling after process death is approximate, and a force-stop prevents restoration until the app next runs.

Tour startup recovery removes retained location/compass/photo payloads from interrupted tours before history becomes available. Callback retries reload scrubbed stored events and recheck live grants. New grants cannot turn old revoked queued deliveries back into new pushes.

The publisher refuses prerelease names, uncommitted source, dirty APK provenance and APKs from a different commit. It verifies the public APK hash before updating the latest manifest. Signing and the existing in-app updater remain the supported release rail. This is not a Play Store submission.

If control restoration keeps failing (for example Android access was revoked), automatic retries pause after twelve attempts. The pending control stays visible; owner Undo retries it after access is restored.
