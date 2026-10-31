# GLM 5.3 Flash implementation review, 28 October 2026

Model: `z-ai/glm-5.3-flash`, via the installed `claude-glm` launcher. Read-only review of this local worktree.

## Initial review

Review complete. I read the full uncommitted diff and all files under `planned/` (the tree changed while reviewing — Transcriber.kt/HealthPrivacyActivity.kt appeared and Hardware/Files/OwnerTools grew; findings below reflect the newest content). Auth posture is solid overall: `requireGrant` re-reads the live agent record in both the HTTP pipeline (`ApiServer.kt:168`) and MCP dispatch (`ApiServer.kt:339`), `Transfers` enforces ownership and UUID-only filenames, clipboard/screen grants are owner-tap-only, and projection validity re-checks switch + grant + agent on every capture. Findings, prioritized:

## P0

**1. `consent_expired` is not in `ErrorCatalog` → the main screen-share error path throws `IllegalStateException` and returns 500.**
`ErrorCatalog.http()` does `byCode[code] ?: error("Unknown error code $code")` (`app/src/main/java/cc/bridgit/app/api/Errors.kt:58`). `ScreenSharing.kt:71`, `:126`, `:139` throw `ErrorCatalog.error("consent_expired", …)` but no such catalog entry exists (only `screen_denied`, unused). So the most common case — `POST /v1/screen/share/capture` with no active grant — dies as 500 `internal_error` instead of the documented 403, and the poll/consent flow the agent is told to follow never explains itself. Add `Entry(403, "consent_expired", …)` to the catalog.

## P1

**2. Permissions dialog omits always-on capabilities; saving any grant set silently revokes log/feedback/tour.**
`AgentDetailFragment.kt:79` builds the checklist from `registry.modules.filter { it.switchKey != null }`, but `Agent.allows` gates *every* endpoint by `caps` (`Agents.kt:34`). `LogModule`, `FeedbackModule` and `TourModule` have no `switchKey` (null = always-on), so they never appear in the dialog. The first time an owner saves a grant set for an agent, `caps` becomes a finite set without `"log"`, `"feedback"`, `"tour"` — the agent immediately loses `GET /v1/log` (including `wait=55` tails), `POST /v1/feedback`, and the whole tour feature, with no per-capability way to get them back (only the "Allow all" reset). Either include always-on modules in the dialog or exempt them in `allows()`.

**3. Timed-control leases that can't be restored are retried every second forever.**
`Hardware.restoreDue` (`Hardware.kt:114-126`) drops a lease only when `read(k) == applied` *and* the write succeeds. The intended "owner overrode it → preserve" case (`read != applied`) leaves the lease in place, and the 1-second `tick` (`Hardware.kt:39`) then calls `read()` on it every second indefinitely — a cross-process binder `Settings.System.getInt`/audio read per stuck lease per second, plus `notification()` re-posting id 704 every second (`Hardware.kt:124`). Same for keys whose `put` keeps failing (DND access revoked mid-lease). When `read(k) != applied`, delete the lease instead of keeping it (that's the "preserve owner change" outcome), and consider stopping the tick when `prefs` is empty.

## P2

**4. Failed `put` in `change()` deletes a pre-existing lease, losing the original value.** `Hardware.kt:109`: if a timed lease for `k` already exists and the new `put` throws, `prefs.edit().remove(k)` destroys the *old* lease — the earlier change will never be restored. Only remove the entry the call just wrote (track "wroteNew").

**5. Approving a second agent's screen request kills the active grant and strands the new one.** `ProjectionService.onStartCommand` (`ScreenSharing.kt:95`) returns early on `projection != null` → `stopSelf()` ends the running agent's grant (owner sees no warning) while the just-approved request stays "pending" until its 5-minute expiry, wasting the Android consent dialog. Refuse in `OwnerToolsActivity` ("stop the current grant first") or hand the request to the running service.

**6. `POST /v1/files` has no `admit` check.** Every other large-body endpoint admits (switch, rate, queue) before the body is read (`ApiServer.kt:111` API-11); the 25 MB transfer body is fully received and written before `requireUsable()` can refuse a switched-off capability, and there's no upload-slot/rate limit — an agent can hammer it regardless of switch state. Wire an `admit` like the camera/gallery endpoints.

**7. Shared notification id 701 for three different events.** `OwnerToolsActivity.notify` (id 701) is used for clipboard requests, screen-access requests, and now "file received" (`Files.kt:91`) — each new event overwrites the previous notification, so a screen-consent request can be hidden by a routine file notice. Use distinct ids (704 is already taken by the controls notification).

**8. `ScreenGrants.create` dedupe ignores the new duration.** `ScreenSharing.kt:33` returns the earlier pending request as-is, so a re-request with a different `duration_seconds` silently reports the old duration; the agent polls against a grant it thinks it asked for.

**9. ML Kit client/`bmp` leak on cancellation.** `OcrModule` (`MediaFeatures.kt:146-152`): no `invokeOnCancellation` — if the 30 s `withTimeout` fires or the client disconnects, the recognizer client stays open and the task keeps running until ML Kit completes on its own. Add `c.invokeOnCancellation { client.close() }` (recycle `bmp` in it too).

**10. `Transcriber.decode` trusts the input track's sample rate/channels.** `Transcriber.kt:66,82`: rate/channels come from the input format and are only refreshed on `INFO_OUTPUT_FORMAT_CHANGED`; if the decoder's output format differs and the change arrives after buffers were consumed (or never fires), the recognizer gets PCM described with the wrong rate/channels. Also consider preferring `KEY_MAX_INPUT_SIZE`-safe streaming rather than copying each buffer (fine at these sizes). Low priority since clips are ≤120 s.

**11. `GET /v1/gallery/recent?since=` (empty value) now errors.** `Modules.kt` passes `query("since")` (empty string, not null, for `?since=`) into `UsageModule.time`, which throws `invalid_parameter` for unparseable input. Treat blank as absent.

**12. Clipboard prefs grow forever.** `ClipboardRequests` (`Clipboard.kt:13-24`): denied/expired records are persisted and never removed (text is stripped but the record stays); the 20-pending cap doesn't bound total records. Prune entries older than, say, 24 h in `all()`.

**13. Fragile Access-screen injection.** `AccessFragment.kt:104-107` appends the button to `b.scroll.getChildAt(0) as? ViewGroup` — if the layout's root child changes, the entry point silently disappears. Give the LinearLayout an id and use it. (FeaturesFragment's `build()` is safe — `removeAllViews()` first.)

Non-issues I checked so you don't have to: capability strings match module ids for all endpoints (incl. `"status"` bypass); `caps` round-trips through agent JSON; `ShareActivity`/`ControlsReceiver`/`HealthPrivacyActivity` are all declared in the manifest with the right exported/permission attributes; transfer files are UUID-named with no caller-controlled path; `Image.use`, bitmap recycling, and the rowStride padding crop in `ProjectionService.capture` are correct; sensor listeners unregister on timeout/cancel; mic/camera locks are released in `finally` on every path; `READ_MEDIA_VISUAL_USER_SELECTED` was already declared and is now requested for video via `UiActions`.


## Focused second review

Review complete. Verified all listed P0/P1 fixes are in place:

- **consent_expired** — now in `ErrorCatalog` (Errors.kt:32), 403, so the screen-share paths resolve correctly. ✔
- **Always-on grants** — the dialog now lists every module except `status` (AgentDetailFragment.kt:81), so log/feedback/tour survive a save; all planned modules are registered in `Capability.kt:74-81`, so none are silently dropped. ✔
- **Screen second session** — `OwnerToolsActivity` refuses to open the consent dialog while `ProjectionService.instance != null` (OwnerToolsActivity.kt:71), and `onStartCommand` no longer calls `stopSelf()` on the early return (ScreenSharing.kt:99). ✔
- **Pre-upload admit** — `TransferModule` wires `admit = { _, _ -> requireUsable() }` for all transfer endpoints (Files.kt:75), which the pipeline runs before the body is read (ApiServer.kt:180-184); uploads are additionally bounded by the 2-slot semaphore. ✔
- **Timer restoration / failed writes** — `change()` no longer deletes a pre-existing lease on failure (savedLeases restored in the catch, Hardware.kt:125), and `restoreDue` backs off 60 s via `retry_after` instead of retrying every second. ✔
- **Queue retention** — transfers prune at 30 days and when the owning agent is removed (Files.kt:58-64); clipboard records prune after 24 h past expiry (Clipboard.kt:22-26). ✔
- Prior issue 3 misread (runCatching/owner-overridden leases) and issue 11 (blank `since=`) — confirmed as you described; not re-raised.

Remaining concrete issues (all P2):

**P2-1 — Stuck restore leases never terminate, and notification 704 re-posts forever.** `restoreDue` (Hardware.kt:135-137) keeps a lease when `read(k) != applied` (owner permanently overrode it) or when the write keeps failing (e.g. DND access revoked). Each 60 s retry rewrites `retry_after`, which changes `prefs.all` in the notification-state string (Hardware.kt:53), so id 704 is re-posted every minute — silent via `setOnlyAlertOnce`, but it never cancels and the tick loop runs indefinitely. The prior fix added backoff but no termination: drop the lease after N failed attempts, or immediately when `read(k) != applied` (owner's change wins — the semantics the comment at Hardware.kt:134 claims but the code doesn't implement). Related nit: a corrupt prefs entry is skipped forever, never removed (Hardware.kt:132).

**P2-2 — `runGuarded` cancellation race produces a dropped connection logged as a 200.** The guard logic is otherwise correct: watcher cancel → `work.await()` throws CancellationException → `ensureActive()` distinguishes caller-disconnect from watcher-cancel → every watcher-cancel reason (removed/rotated/revoked/switched-off) is re-checked and converted to a structured `ApiError`. But if the state flips back between the watcher's cancel and the catch's re-check (grant revoked and re-added, or capture switch toggled within the ~200 ms window), `throw e` (ApiServer.kt:324) lets the original CancellationException escape. The pipeline rethrows it (ApiServer.kt:256-259) after the `finally` logged `http=200, result="ok"` with 0 bytes — so the client gets a reset connection with no response and the audit log records a success. Convert that path to a 409/503 reply (or log it as failed) instead of rethrowing.

**P2-3 — A second consent grant can still be stranded (race-only).** `onStartCommand`'s early return (ScreenSharing.kt:99) discards the freshly granted Android consent token and leaves the new request pending until its 5-minute expiry. The OwnerTools guard makes this reachable only if a session starts between the guard check and the second service start; harmless but worth a `decide(grantId, "failed")` on that path.

Not re-raised (fixed or acceptable): notification id collision (705/706 now distinct), `ScreenGrants.create` duration conflict, OCR `invokeOnCancellation { client.close() }` with bitmap recycled in the completion listener (double `close()` on cancel-then-complete is safe), transfer UUID filenames and ownership checks, `capture()`'s pre/post validity re-checks and bitmap recycling, sensor listener cancellation, `WaitGate`/`IdempotencyCache` bounds. No P0/P1 issues remain.


## Resolutions after the second review

- Missing `consent_expired`, omitted always-on modules, second-session handling, upload admission, failed-setting rollback, notification IDs, screen-duration conflicts, OCR cancellation, and clipboard retention were addressed.
- The reviewer twice misread the original `runCatching { if (...) put(...); true }` branch: it already removed a lease when the owner changed the value. That branch has now been written explicitly, so preserving owner changes is unambiguous. Corrupt leases are removed. A restoration blocked by revoked Android permission is retained for recovery with a 60-second retry interval; notification comparison ignores retry timestamps, so there is no per-minute repost. Deliberately do not silently discard the original owner setting after an arbitrary number of failures.
- The authorization-cancellation race now returns a structured conflict if access is restored between cancellation and error handling. Inner operation timeouts return `capture_timeout`.
- A raced second projection start closes its request as failed without stopping the active session.
- Empty ISO timestamp parameters remain invalid intentionally, rather than silently broadening a query.
- Final review reported no remaining P0/P1 findings. Remaining device acceptance is documented separately; the review is not a claim of live hardware acceptance.
