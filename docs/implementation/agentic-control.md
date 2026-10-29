# Android agent control implementation and acceptance

29 October 2026. The new control layer is additive to production commit `de26bf8`, which contains the existing 1.6.0 features. The earlier audit used a stale 1.2-era checkout; its missing-feature inventory is historical, not a description of the integrated app. Existing agent credentials, settings, logs, rich interactions, file transfers, City tour, updates and other 1.6.0 functionality remain on their production stores and v1 API. There is no new agent/key database.

## Implemented scope

The twenty audited feature families are accounted for in `website/content/agent-control.json`, with source paths and tool names. `GET /v2/capabilities` reports this device's identity, scopes and readiness. `GET /v2/tools` is the current tool contract; `/v2/guide` and `/v2/openapi.json` describe the additive API. Existing v1 health now points to this discovery surface. New MCP tools are at `POST /mcp`; existing v1 MCP remains available.

| Family | Implementation and limits |
|---|---|
| Readiness | Stable device identity, version, OS, scope, feature switch and concrete Android blockers. |
| Grants | Phone-only per-agent grants with expiry. Existing camera/microphone/gallery/audit authority is preserved; new scopes start denied. |
| Control sessions | Owner-approved app list and duration, heartbeat, generation/fence, exclusive controller and visible Stop. |
| Durable operations | Transactional idempotency reservation, owner-pending actions, cancellation and unknown-outcome recovery. Deleted result history retains request tombstones. |
| Screen observation | Private Accessibility capture/tree or explicitly owner-started MediaProjection. Password/sensitive nodes are excluded; Android capture restrictions remain. |
| Element targeting | Snapshot-bound refs, exact app/tree checks and refusal of missing or ambiguous targets. |
| App actions | Reviewed tap, long press, text replacement, scrolling, back/home; gesture fallback is tied to the approved element. Up to eight exact steps may be reviewed together as a bounded sequence. |
| Verification | Explicit postconditions, bounded waits, per-step re-observation and no automatic replay of unknown results. Dispatch is not external task completion. |
| Human input | Identity snapshots on questions, session requests, workflow proposals and action approvals. First answer wins; expiry/cancellation are terminal. |
| Typed integrations | Supported app/link opening, calendar/message/email drafts, dialer, contact picker, alarms/timers, clipboard and selected-file opening. Drafts require owner completion in the target app. |
| Agent tools | Shared new command dispatcher for REST and MCP; media returned as MCP media content. Authentication uses the existing agent registry. |
| Notifications | Owner-selected app allowlist and live notification fingerprint; reply requires a supplied RemoteInput action and an exact owner review. The existing listener component is retained. |
| Files | Selected image, PDF, text or audio transfers; 512 KiB per new v2 transfer, 50 files/20 MiB per agent. Existing v1 larger-transfer API is unchanged. |
| Voice and text | Bundled Latin OCR, barcode recognition, TTS, supported on-device SpeechRecognizer and ML Kit translation. Translation model downloads require approval and Wi-Fi. No cloud recognition fallback. |
| Productivity | Typed drafts and owner-approved app UI assistance. No unrestricted SMS, call-log access or automated financial commitment endpoint. |
| Local workflows | Fixed native steps, button/interval/charging/geofence triggers, immutable approval digest and native UI templates. No downloaded code, shell, arbitrary HTTP or unbounded loops. |
| Diagnostics | Power, lock, storage, network, Android access links and actionable blocked states. |
| Monitoring/help | Explicit time-limited JPEG sessions, freshness reporting and viewer-only credentials. Help incidents have a cancellation window, approved HTTPS recipients, retry/escalation records and separate recipient acknowledgment. |
| Multiple devices | `tools/bridgit-fleet` routes to an explicitly registered device and verifies its stable identity before a call; it stores key-file paths, never inline keys. |
| Specialist adapters | Bounded BLE scans/GATT reads and writes, USB bulk transfers, owner-started NFC reads, barcode recognition and foreground geofence checks. All require the relevant hardware/access. |

## Distribution and authority

The private flavor includes the explicit Accessibility control service and optional notification access. The limited flavor excludes Accessibility and notification-listener declarations; this is preparation for store review, not a claim of Play approval. Both retain the established app capabilities where Android permissions and policy allow them. Android 14 remains the minimum; older-device support is not represented as delivered.

Every new mutation needs an `Idempotency-Key` and returns a durable operation. Poll `/v2/operations/{id}`. A completed operation can contain an `unverified` dispatch result or an `opened_for_owner_completion` draft. The caller must inspect that outcome. Key rotation/removal, grant revocation and owner Stop invalidate queued control authority. Local workflow definitions cannot raise their own authority or change approved destinations.

Control observations expire and cannot be carried across a restart. UI sequences re-observe before each step, require a unique exact target and stop if a postcondition does not match. A sequence approval covers the exact reviewed steps, rather than authorizing an unrestricted remote planner. Physical takeover detection is best-effort; Stop is authoritative.

Recipients are configured only on the phone. HTTPS is pinned to a validated resolved address; redirects are not followed. Private-network access requires explicit approval and still excludes loopback, link-local/metadata and multicast. Events are signed over `timestamp.nonce.event_id.raw_body`; receivers must verify signatures, freshness and deduplicate event IDs. HTTP acceptance is not a human response. An acknowledgment credential is bound to one incident and recipient. No emergency dispatch or guaranteed rescue is claimed.

## Validation

- Private and limited unit suites cover existing API/keys/media/interaction logic plus grant expiry, cross-agent isolation, canonical idempotency, crash recovery, event pagination/gaps, immutable approvals, workflow validation, recipient signatures, exclusive leases and MCP media content.
- Lint passes without a baseline or suppression. Lint 31.7 examines source overlays independently, so each flavor repeats the real inherited foreground-service declaration; compiled manifest verification remains required.
- The USB Pixel fixture run passes fourteen checks: retained v1 discovery, scoped v2 readiness, invalid authentication, MCP initialization/discovery, idempotent retries/conflicts, phone answers/events, file isolation, approved workflows, observed text entry, stale-target rejection, verified form save, real screen capture, local OCR, approved two-step execution and cancellation/control-center behavior. The report groups related assertions into fourteen checks.
- Device testing uses `net.finleg.bridget.agentictest`, port 8740, a DUMP-protected debug entry and a reversible form with synthetic values. Production pairing/data are untouched. The test restores its own Accessibility changes. A separate active UiAutomation driver suppresses normal Accessibility services; the device-testing chats coordinated a clean test window.
- Website tests retain the versioned published 1.6.0 catalog and add a separately labeled development catalog. The builder validates implementation paths. Link checks cover all generated pages and assets.

Hardware/OEM acceptance is not complete for BLE/USB/NFC accessories, all speech languages, external alert recipients, geofences, camera/microphone monitoring durations, Doze/reboot/thermal behavior and every third-party app. Continuous WebRTC/audio streaming, dependable wake-word detection, public relay hosting and a universal AppFunctions adapter are not claimed. These are separate extensions or device acceptance work, not silently simulated endpoints.

## Build and install evidence

Every APK-producing Gradle invocation reserves fresh versions through `tools/allocate-apk-version.py`, coordinated by the Git-common-dir lock and ledger across registered worktrees. APK manifest, signature, size and SHA-256 are inspected after packaging. The verified private release is **1.6.40 / code 57**, package `net.finleg.bridget`, built from clean source commit `9a2da2615bccf8927b140fa7be587b2da28d8fcb`. Both variants pass 186 unit tests each; private/limited lint and private release vital lint pass. `agentic-control-release.json` contains the artifact hash, certificate and complete validation counts. The original signing key and `adb install -r` preserve production data and pairings; no downgrade, uninstall or reset is part of the update.

The USB Pixel `57091FDCQ0081F` was updated successfully in place to 1.6.40 / code 57. Installed-package verification, startup and fatal-crash checks passed. Its UID, original first-install timestamp, app data and all three existing agent pairings were preserved. No website deployment or Git push was performed.
