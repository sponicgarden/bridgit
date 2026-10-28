# Hand-over: Bridgit P1a (1.3.0) → P1b

> Update 27 Oct 2026: the owner asked the same session to go on with P1b (Spotka-style bug and feature
> reports), a "Check for updates" feature like Spotka's, and a new app icon, as 1.4.0. This file
> still describes the state at the end of P1a.

Written 27 Oct 2026 by the P1a session. The P1b session starts from this file.

## 1. Read first (in this order)

1. This file.
2. `docs/bridgit-spec-v2.md`: section 1 (decisions, especially **D20**), section 11 (bug reports and
   feature requests: all of P1b), 12.10 (UI-20), AGT-15 (Developer switch, already built), section 16
   (the P1b row), Appendix C (error codes).
3. `docs/ui-spec.md` 5.11 (Report form) and 5.12 (My reports); mockups `Report.dc.html` /
   `ReportDark.dc.html` in the side-session notes folder (path in section 7). ui-spec is read-only.
4. The code map in section 3 below, then `app/src/main/java/net/finleg/bridget/capability/Modules.kt`
   (how a module adds endpoints) and `api/ApiServer.kt` (the request pipeline).
5. The latest acceptance results in `docs/acceptance/`.

## 2. What P1a built

Bridgit **1.3.0** (versionCode 4), installed on the Pixel as the release build on 27 Oct 2026 after
uninstalling 1.2.0 (D20: nothing migrated).

- **Keys and agents:** `brg_` keys (SEC-09), salted SHA-256, shown once in the setup prompt; up to 16 agents;
  rotate / remove with `unauthorized` + `reason` (`key_rotated` / `agent_removed`) for 7 days (AGT-12);
  Developer switch (AGT-15); notes; Pair with code kept (AGT-13).
- **Setup-prompt flow (section 6):** Name → Connection (direct / userspace; relay hidden) → self-check
  (listener, Tailscale address, raw-socket port check) → prompt (Copy / Share / QR / Copy key only, FLAG_SECURE,
  sensitive clip cleared next time the app has focus) → live "waiting for first call" → "connected".
  AGT-05a: the key survives a crash for 30 min, encrypted with an Android Keystore key.
- **API:** `/v1/guide`, `/v1/openapi.json`, `/v1/tools/bridget` (from `tools/bridget`, single source),
  `/v1/capabilities` in the API-09 shape, API-10 size presets, `raw=1` on snapshot and gallery photo,
  `image/last?since=&wait=`, Idempotency-Key (API-13), pending schema defined (API-12, no producer yet),
  2 MB body cap, `X-Request-Id` on every reply, SEC-06/07 limits as refined, SEC-08a wait limits.
- **Log (section 10):** SQLite store (LOG-01..05), `/v1/log` with filters, cursor, tail mode, `/log/{id}`,
  `/log/stats`, `/log/export` (jsonl/csv), `POST /v1/log` notes, `/v1/audit` alias; visibility setting
  "all / only their own" applied to every log path; redaction of keys, secrets, media, long text.
- **Service:** IPv4 + IPv6 Tailscale bind; VPN network callback + 5-minute re-check (ARC-04a); boot and
  update receiver with specialUse only (ARC-02b); a background start never downgrades a running
  listener; Pause / Open notification; capture notification names the agent.
- **Screens (light + dark, Material 3):** First run, Home, Agents, Add agent, Pair with code, Agent detail,
  Activity (filters, live, entry sheet, export, clear, diagnostics), More, Features, Access, Settings.
  Four tabs; Inbox comes in P2.
- **Tests:** 30 JVM unit tests (`./gradlew testDebugUnitTest`); `tools/acceptance.sh`, 30 device checks,
  last run 30/30 on the debug build of 9c34070 (`docs/acceptance/2026-10-27-0334-1.3.0-debug.md`).

**Re-pairing (27 Oct 2026, release 1.3.0, through the real Add-agent screens):**

| Agent | Connection | New key in Bitwarden | One real call |
|---|---|---|---|
| Claude | direct (this Mac) | "Bridget — Claude agent key", updated | **Worked**: helper + key from Bitwarden → `GET /v1/health` ok |
| Codex | direct (this Mac) | "Bridget — Codex agent key", updated | **Worked**, same way |
| Grok | userspace (grokbox) | "Bridget — Grok agent key", updated | **Not done**: grokbox didn't answer (ssh timed out, no Tailscale ping). Its `~/.config/bridget/env` still holds the old key. Key itself checked from the Mac. |
| Muse | direct | "Bridget — Muse agent key", updated | **Not possible yet**: Muse runs on Meta's servers, which can't reach the tailnet (needs relay mode, Q10). Key itself checked from the Mac. |

## 3. Code map

| Package | What lives there |
|---|---|
| `core` | `Keys` (brg_ keys, hashing, scrub), `Agents` (Agent record AGT-01, `AgentRegistry` with 7-day tombstones), `Settings` (switches, port, log visibility, retention, appearance), `PendingKeyVault` (AGT-05a, Keystore AES-GCM, 30 min), `Net` (tailnet address check) |
| `api` | `ApiServer` (Ktor CIO, the one request pipeline: source check → key → limits → body → Idempotency-Key → endpoint → one redacted log entry), `Endpoints` (every endpoint's spec: drives routes, guide, openapi), `Errors` (`ErrorCatalog` = Appendix C, `Pending` = API-12), `Limits` (`RateLimiter`, `WaitGate`, `IdempotencyCache`, `Sizing` presets), `SelfDescription` (`SetupPrompt`, `Guide`, `OpenApi`) |
| `log` | `LogModel` (`LogEntry` = LOG-01, `Redactor`), `LogQuery` (filters → SQL with bound args, cursors), `LogStore` (SQLite, single writer thread, tail waits, retention, clear) |
| `capability` | `Capability` base + `Registry` (ARC-05), `Modules` (status, camera, mic, gallery, log), `Media` (CameraX, MediaRecorder, MediaStore, image resize) |
| `ui` | `MainActivity` (single Activity, Navigation, 4 tabs), one package per screen, `common` (avatar, formats, log-row sentences, `LogRowAdapter`) |
| root | `BridgeApp` (process state, `ListenerState` flow, channels), `BridgeService` (foreground service, binding, VPN callback, notification), `BootReceiver` |
| `src/debug` | `DebugSetupReceiver`: ADB-only test hook (switches, add/rotate/remove agents, developer flag, log visibility) |

Adding the P1b feedback module: a new `FeedbackModule` in `capability/` (endpoints in `api/Endpoints.kt`,
add it to `Registry.modules`), a `feedback` table in its own SQLiteOpenHelper or a v2 of `log.db`,
the `forbidden` / `developer_only` error already exists in `ErrorCatalog`, and `agent.developer` is already
stored and switchable in Agent detail.

## 4. Decisions made in P1a

- **D20** (owner): no migration from 1.2; recorded first, Appendix F dropped.
- Version **1.3.0** (owner). Debug builds listen on **8735** (BuildConfig).
- Four tabs in P1a; Inbox in P2 (owner instruction; ui-spec Q-D3).
- ARC-12a: no outgoing plain HTTP in 1.3.0, so cleartext stays off; the Add-agent check uses a raw socket.
- Idempotency (API-13): in memory, per agent; only 2xx kept; media bodies 15 min (SEC-13), then a "done"
  marker until 24 h and the new code `idempotency_expired` (409); 48 MB body budget; replays get their own
  `request_id` plus `idempotent_replay_of`.
- Wrong pairing codes count toward the SEC-06 per-address failure limit; pairing obeys that block.
- Log visibility "own" also hides Bridgit's own (agent-less) entries from normal agents.
- System monospace font (Q7 not bundled yet). Notification channels: only `listener` and `capture`.
- Quick switches: Camera, Microphone, Gallery (Location isn't built).
- Codex `gpt-6.1-sol` reviewed the plan and the server code; its 13 code findings were fixed.

## 5. Spec changes made in P1a

All in `docs/bridgit-spec-v2.md` (page rebuilt with `tools/spec-page` after each change):
D20 and Appendix F dropped; changes table (section 2) for keys and `/v1/audit`; SEC-09, LOG-04, LOG-12;
ARC-02b (starts at first unlock after a reboot; update restart checked); ARC-04a (IPv6 checked on the
Pixel); ARC-10 (debug port 8735); ARC-12a (no outgoing HTTP); API-12 (no producer yet); API-13 (as built);
AGT-10 (prompt length test); UI-03 (system mono); UI-07 (four tabs); UI-21 (two channels); UI-26 (text
steps only); TST-03 (script as built); Appendix C (`idempotency_expired`); P1a row version 1.3.0.

## 6. UNVERIFIED items and known problems

**Not checked on the Pixel:**
- A real reboot (ARC-02b). Only the update restart (`MY_PACKAGE_REPLACED`) was tested.
- Tailscale going off and on while Bridgit runs (ARC-04a callback). ADB runs over Tailscale, so it
  can't be tested remotely. The bind and IPv6 were checked.
- Grok's and Muse's real calls (see the table in section 2).
- UI at 200 % font scale and with TalkBack (UI-29): built for it, not walked through on the phone.
- Portrait layout: the phone was lying in landscape (Bedtime mode) during the screen checks, so the
  screens were checked in landscape, dark (Bedtime) and light (app setting).
- CAP-04 `device_locked` with the screen off but unlocked: not tested.

**Known problems / gaps:**
- First run in landscape: page 1 text runs under the page dots (it scrolls; fine in portrait).
- Grok's box still has its 1.2 key: it needs `~/.config/bridget/env` with the new key from Bitwarden,
  `BRIDGET_PORT=8734`, and the new helper from `GET /v1/tools/bridget`.
- Muse needs relay mode (Q10) before it can reach the phone at all.
- Not built in P1a (later phases): Inbox and interactions, events, callbacks, Quick Settings tile (P2);
  bug reports and feature requests (P1b); phone status, location, flashlight (P3); files, sharing (P4);
  screen-share grants, video, OCR (P5); usage, Health, notifications, clipboard (P6); per-agent
  permissions (P7); relay (Q10). SEC-08 daily data quotas, the UI-26 Pixel screenshots, the UI-27
  "Blocked" card (no P1a row can be blocked) and TST-04 CI are also not built.
- The 1.3.0 debug build (`net.finleg.bridget.debug`) is still installed on the Pixel, on port 8735, with
  two test agents.

## 7. Practical notes

- Pixel over ADB: `adb -s pixel-10-pro-xl.tail9c9221.ts.net:5555` (a tablet is also connected: always pass `-s`).
- Release API `http://100.94.27.79:8734/v1`; debug build `net.finleg.bridget.debug` on **8735**.
- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; ./gradlew assembleDebug assembleRelease testDebugUnitTest`.
  Copy `local.properties` and `keystore.properties` from the main checkout into your worktree (gitignored; never commit
  them, the keystore or its password). The release must stay signed with cert SHA-256 `6bbd482d…2f61`
  (`apksigner verify --print-certs`), or updates stop installing over 1.3.0.
- A freshly installed debug build is in Android's "stopped" state: launch it once
  (`adb shell am start -n net.finleg.bridget.debug/net.finleg.bridget.ui.MainActivity`) before the debug
  hook's broadcast is delivered. `tools/acceptance.sh` does this.
- `tools/acceptance.sh --apk app/build/outputs/apk/debug/app-debug.apk` runs the device checks and writes
  `docs/acceptance/<date>-<version>-debug.md`. Camera and mic checks need the phone unlocked; otherwise they are
  UNVERIFIED. Add P1b checks (report filed → developer agent reads it → status moves to `fixed`) to it.
- The original spec page artifact (claude.ai/artifact/VgpQreXxSXza4MRVDxXWMr) belongs to another account;
  `tools/spec-page` rebuilds the HTML locally.
- UI mockups and notes: `/Users/otter/Library/Application Support/Claude/side-session-notes/local_015a33ab-92e8-4624-9608-30d67f550a23/`.
- Second opinions: `codex exec -m gpt-6.1-sol -s read-only ...` (the owner asked for Codex as the second reviewer).
