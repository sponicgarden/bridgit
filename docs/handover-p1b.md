# Hand-over: Bridget P1a (1.3.0) → P1b

Written 27 Oct 2026 by the P1a session. The P1b session starts from this file.

## 1. Read first (in this order)

1. This file.
2. `docs/bridget-spec-v2.md`: section 1 (decisions, especially **D20**), section 11 (bug reports and
   feature requests: all of P1b), 12.10 (UI-20), AGT-15 (Developer switch, already built), section 16
   (the P1b row), Appendix C (error codes).
3. `docs/ui-spec.md` 5.11 (Report form) and 5.12 (My reports); mockups `Report.dc.html` /
   `ReportDark.dc.html` in the side-session notes folder (path in section 7). ui-spec is read-only.
4. The code map in section 3 below, then `app/src/main/java/net/finleg/bridget/capability/Modules.kt`
   (how a module adds endpoints) and `api/ApiServer.kt` (the request pipeline).
5. The latest acceptance results in `docs/acceptance/`.

## 2. What P1a built

<!-- FILLED IN AT THE END OF P1a -->

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

<!-- FILLED IN AT THE END OF P1a -->

## 5. Spec changes made in P1a

<!-- FILLED IN AT THE END OF P1a -->

## 6. UNVERIFIED items and known problems

<!-- FILLED IN AT THE END OF P1a -->

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
