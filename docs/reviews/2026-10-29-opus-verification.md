Verdict: **no blocking source bugs in the app code.** One release-process gap does need closing before a production publish: the push requirement isn't enforced when GitHub is skipped (R1 below). I used only Read/Grep/Glob. `Speaker.kt` and `CallbackDispatcher.kt` changed while I was reading them; the findings below reflect the latest version of each.

## Findings that need action

**R1 (P2, release gating): a production publish can skip the "pushed revision" check**
- `tools/publish-apk.sh:124-128`: the `git branch -r --contains` check only runs when `GITHUB=1`. The R2 upload of the APK and `latest.json` (`:238-243`) happens either way.
- Reproduction: on a clean, committed but unpushed HEAD, run `tools/publish-apk.sh --notes x --no-github`. The clean-tree, provenance, signature and version checks all pass, and the in-app updater then serves an APK from a commit that was never pushed.
- The check itself also has two weaknesses:
  - It reads local remote-tracking refs, which can be stale. It never fetches or compares against `origin/main`.
  - `:269` recomputes `COMMIT` from HEAD instead of reusing the value checked at `:122`.
- Fix: always run the push check, against freshly fetched refs (`git fetch` plus `merge-base --is-ancestor`, or `git ls-remote`), and reuse the `:122` commit.

**R2 (P3): a failed multi-setting request can leave a setting changed while claiming it was undone**
- `planned/Hardware.kt:147` only undoes a key if `read(k) == changes[k]`, i.e. if the phone still shows the requested value. But Android can adjust the value it actually stores. That's why line `:141` now saves the read-back value.
- Example: `POST /v1/phone/sound {"volume":{"alarm":0,"ring":0},"duration_minutes":10}`.
  1. Android stores alarm volume as 1, its minimum.
  2. The ring write throws (on some devices a `SecurityException` without Do Not Disturb access).
  3. Because 1 ≠ 0, the alarm change isn't undone, and `:148` deletes its timed restore.
- Result: the alarm stays at minimum indefinitely, while the error says "earlier changes … were rolled back."
- Whether step 2 throws depends on the device, but the logic flaw is confirmed in source.
- Fix: record `read(k)` after each `put` and compare against that.

**R3 (P3): flashlight tracking can be lost on a quick off-then-on**
- `Hardware.kt:46-48, 70-76`. Android reports torch changes asynchronously on the main thread. If Bridgit turns the light off and an agent turns it on again before that "off" report arrives, `torchLost` runs after `torch = id`.
- It then clears `torch`, cancels the auto-off timer and frees the camera lock, while the light is physically on.
- Fix: ignore "off" reports caused by Bridgit's own `setTorchMode(false)`, e.g. with a counter that each turn-on increments.

**R4 (P3): screen-share requests that never started are reported as "ended"**
- `planned/ScreenSharing.kt:101, 108, 125, 155`. `grantId` is set before the validity check, so a request that is expired, revoked or failed still gets this in `onDestroy`:
  - the audit line "Screen sharing ended"
  - its status overwritten to `ended` (replacing `expired` or `failed`)
  - a `grant.ended` event
- Fix: only do this when the projection was actually created.

**R5 (P3): MCP binary results have no size cap**
- `api/McpProtocol.kt:229-231` base64-encodes binary results into a JSON blob without a limit. `camera_video` can be up to 40 MB (`MediaFeatures.kt:80`) and files up to 25 MB (`Files.kt:106`).
- That means more than 150 MB of temporary strings, compared with the 12 MB base64 cap used elsewhere. It may run out of memory and take the listener down. Needs a device check.
- Fix: cap the blob, or lower the video length/resolution limits over MCP.

**R6 (P3): cancelled MCP tool calls are logged as successful**
- The HTTP path now logs 499/`cancelled` (`ApiServer.kt:264-266`). The MCP path (`:426-428`) rethrows with `http` still 200, so the per-tool log entry says "ok".

**R7 (P3, UX): tour agent picker**
- `ui/tour/TourFragment.kt:260-264` now filters agents by grants, but when none qualify it still shows `tour_no_agents` ("You have no agents yet. Add one…", `strings_tour.xml:12`) and Add agent.
- `TourManager.start` (`Tour.kt:247`) doesn't require the speaker grant, so a chosen agent may be unable to narrate.

**R8 (P3, hardening): tour recovery at startup**
- `InteractionStore.kt:255` parses event data with `JSONObject(...)` without `runCatching`, inside `Application.onCreate`. One corrupt row would crash the app on every start.
- The restart `tour.ended` event is emitted at `BridgeApp.kt:76`, before the callback listener is registered at `:83`. So it reaches `/v1/events` but is never pushed to a callback.

**Minor (P3)**
- **Retries that never end:** a timed setting whose restore keeps failing (access revoked) retries every 60 s forever. "Undo" can't clear it (`Hardware.kt:174-176`).
- **Missing audit lines:** the Device tools buttons "Turn flashlight off", "Stop ringing" and "Undo timed phone settings" (`OwnerToolsActivity.kt:94-96`) aren't logged.
- **Wrong audit attribution:** `BridgeService.onDestroy` → `hardware.stop()` logs "Owner stopped…" even when the service stopped without an owner action.

## Checked and found sound

- **Init order:** `registry`, then `hardware`, then `speaker`, then `tours` (which runs tour recovery), then `callbackDispatcher`. Hardware's startup restore talks to `AudioManager` directly because `speakerReady()` is still false, so nothing touches an uninitialised field. The request state (`RequestState`) is created with the app object, so the idempotency cache and rate limiters now survive a listener rebind (fixes the original P2-2).
- **Lock ordering:** I found no cycle.
  - Agent registry changes call their listeners while holding the registry lock. Those listeners take, in order, the tour lock, the interaction-store lock and the callback queue/store locks, or the speaker queue lock.
  - Hardware's lock leads to the speaker's own monitor.
  - `Settings` takes no lock, and `agents.get` takes none, so nothing holding a later lock ever waits for the registry lock.
- **Restore and read-back:**
  - `Rules.leaseBaseline` together with saving the read-back value fixes P2-3 and NV3, apart from R2.
  - The speaker volume ceiling now goes through `controlVolume()` (fixes #14).
  - The exact alarm was replaced by a `setAndAllowWhileIdle` backup, so the timing is explicitly approximate.
- **Callback revocation:**
  - `EventAccess` is enforced at enqueue, attempt and deliver, and on `/v1/events` pulls.
  - The queue is dropped on any grant change.
  - Retries reload the event from the database, so scrubbed tour data is what gets sent.
- **Cancellation:** `transcribeKeepingAudio` rethrows real cancellation and keeps the audio on a timeout or failure. `runGuarded` maps timeout, revocation and switch-off correctly.
- **Publishing fails closed (apart from R1):** the provenance file uses the full commit and treats an unknown git state as dirty. `check-apk-provenance.py` requires exactly `dirty_build:false` and a matching commit. Every check runs before any upload, and the APK hash is verified before `latest.json` goes up.
- **Other original findings resolved:**
  - OCR sample size, `image/last` re-check after the wait
  - Play aloud (owner speech is exempt)
  - Interaction `speak` and gallery grants
  - `FLAG_SECURE` on the callback-secret dialog, grant audit logging
  - Transcription keeps the audio
  - MCP blob results (subject to R5)
  - Device tools saved state, APK "Open" refused, per-agent quotas, share preview
  - `access_needed` error mapping, TTS re-initialises after a failure
  - Screen share: `startForeground` is now called first (NV1)
  - Tour grant checks, version 1.6.0/18, acceptance checks A01 and A07, user-facing claims and descriptions
- All feature switches default to off (`Settings.kt:20`).

## Still needs on-device checks (not source bugs)

- **Screen sharing:** NV2 (a still screen returns no new frame, `ScreenSharing.kt:132`, plus rotation and single-app sharing).
- **Health Connect:** NV4 (whether the `RequestMultiplePermissions` contract works on Android 14).
- **Background behaviour:**
  - NV5: recognizer behaviour on long or silent clips.
  - NV6: location after a background start.
  - NV7: every process start restores timed settings early, and the backup alarm is inexact.
- **Ringer and ring volume:** Android switching the ringer mode when ring volume is set to 0 can make Bridgit treat its own change as the owner's.
- **Related to my findings:** MCP video memory (R5) and the flashlight report timing (R3).
- **Test gaps:** the A28 secret scan still skips `cbs_` secrets, the `callbacks`/`clipboard_requests`/`transfers` settings files and `interactions.db` (`acceptance.py:664-670`). Nothing tests the dispatcher or Hardware against a fake store or device.