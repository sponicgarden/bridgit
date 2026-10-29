I found one new defect, a low-severity (P3) problem in the rollback path. Everything else in R1–R8 checks out in source. I only used Read/Grep/Glob and ran nothing.

## New defect

**When an undo fails, an earlier timed setting loses its original value.** This is in `planned/Hardware.kt:158-165`.

When undoing a key fails, the code replaces that key's saved timed lease with a retry lease whose `"before"` is `before.getValue(k)`. That is the value just before *this* request. Line 165 then skips restoring the old lease (`filterKeys { it !in retry }`). So if a timed lease already existed for that key, the owner's original value is thrown away. Example:

1. Agent A sets DND for 60 minutes. The lease records the owner's value as `before`=B0 and A's value as `applied`=A1.
2. Agent B sends a request with two settings. Its write to DND succeeds, its second write throws, and undoing the DND write also throws.
3. The retry lease now says `{before: A1}`. When it later succeeds, DND goes back to A1 and the lease is deleted. A's "timed" change becomes permanent and B0 is never restored.

This needs two separate Android refusals, so it's rare. The fix is small: build the retry lease's `before` with `Rules.leaseBaseline(before[k], savedLeases[k]?.before, savedLeases[k]?.applied)`, the same way line 145 does.

## Verified

- **R1 (publisher):** `tools/publish-apk.sh:124-125` fetches `origin main` and checks the commit with `merge-base --is-ancestor` before any upload. This now runs even with `--no-github` and in dry runs. `COMMIT` is taken once at `:122` (after the clean-tree check), checked against provenance, and reused for `--target` at `:272`.
- **R2 (multi-setting undo):** the value Android actually stored is recorded at `:149`. Undo compares against it at `:159`, and a failed undo keeps a retry lease. The baseline gap above is the only problem.
- **Retries:** after 12 failures, `retry_paused` stops `scheduleRestore` and `restoreDue(false)` from trying again. `restoreDue(true)` (Undo, the endpoint, the switch listener, process start) ignores the pause and tries again.
- **R3 (flashlight):** `ownTorchOff` goes up before `setTorchMode(false)` and back down if that call throws. A pending "off" report uses up the count, so the quick off-then-on case keeps the light tracked. Edge cases where a report goes missing or Android sends "unavailable" instead correct themselves on the next "off" report. The torch callback and `torchLost` share one lock.
- **Audit lines:** the Device tools buttons now log (`OwnerToolsActivity.kt:94-96`). `ControlsReceiver` passes `owner = true`, and `BridgeService` uses the default "stopped with the listener" text.
- **R4 (screen sharing):** `started` is set only after the virtual display is created (`ScreenSharing.kt:121`). "Ended" is now reported only for sessions that really started (`:157`).
- **R5 (MCP size cap):** the 8 MB cap is checked before any base64 encoding of non-JSON results (`McpProtocol.kt:212`). `downloadUri` returns a link only for GET endpoints. It percent-encodes path and query values and includes no key. The base URL already ends in `/v1` and `spec.path` doesn't, so links come out right.
  - Video over MCP is limited to 6 MB versus 40 MB over HTTP (`MediaFeatures.kt:68`). The endpoint description says so, and the `X-Bridget-Duration-Limit-Seconds` header correctly describes a limit, not the actual length.
  - `viaMcp` is set only in `dispatch` (`ApiServer.kt:379`), on a fresh `Req` each time.
  - Peak memory for an 8 MB payload is about 4–6× that size. That's acceptable, but still worth a device check.
- **R6 (cancelled MCP calls):** `ApiServer.kt:430` sets 499/`cancelled` before rethrowing, so the per-tool log in `finally` records it correctly.
- **R7 (tour agent picker):** the speaker grant is now required in all four places (`Tour.kt:246, 280`, `BridgeApp.kt:80`, `TourFragment.kt:260`). The empty-state text tells the owner which grants to turn on, and there are no stale translations.
- **R8 (startup recovery):**
  - Order: the dispatcher is created and its listener registered (`BridgeApp.kt:84-85`) before `recoverInterruptedTours()` (`:86`).
  - Malformed rows are skipped at `InteractionStore.kt:257`.
  - `onEvent` only adds to the queue, so lock order is still interaction store, then callback queue.

## Tests (read, not run)

- `McpTest.oversizedMediaUsesAnAuthenticatedHttpLink` covers the URL format, the `resource_link` reply and the POST case returning no link.
- `test_apk_provenance.py` covers a clean build, a dirty build, an unknown git state and a commit mismatch.
- `PlannedRulesTest` covers `leaseBaseline`.
- Not covered by any test: the shell-level fetch/ancestor check, Hardware undo (including the defect above) and the flashlight counter. All of these need an Android or shell test harness.

## Final disposition

The one reported P3 retry-baseline gap was fixed exactly as recommended: failed rollback retries now use the same `Rules.leaseBaseline(current, previous.before, previous.applied)` calculation as normal leases. The existing owner-baseline regression test covers that rule. No other source defect was reported in this final pass. Model usage for every review invocation was exclusively `claude-opus-5-5`.
