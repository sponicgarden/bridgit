# Bridget — Product and Technical Specification v2.0

| | |
|---|---|
| **Document** | `android/docs/bridget-spec-v2.md` in `rahuliofam/finleg` |
| **Version** | 2.0, draft for review, 27 Oct 2026 |
| **Owner** | Rahul Sonnad (sole user, sole device) |
| **Product** | Bridget, a general-purpose Android bridge for personal AI agents |
| **Package** | `net.finleg.bridget` (sideloaded, not on the Play Store) |
| **Device** | Google Pixel 10 Pro XL, Android 17 (API 37); minimum Android 14 (API 34) |
| **Current build** | Bridget 1.2.0 (installed 26 Oct 2026) |
| **Supersedes** | `spec-v1.0.pdf` (as amended), `spec-per-agent-permissions.md`, `spec-agent-inbox.md`, `spec-flashlight.md`, `spec-screenshot.md`, `spec-access-screen.md` |

This document is written to be reviewed by people and by other AI systems
that have **not** seen the conversations that produced it. Everything a
reviewer needs is in here: decisions already made, what changed from v1,
every requirement with an ID, exact API shapes, every screen, and the
acceptance tests.

---

## 0. How to read this document

### 0.1 Requirement IDs and status

Every requirement has an ID such as `AGT-04` and one status tag:

- **[Decided]**: the owner chose this. Reviewers may question it, but it is
  not open.
- **[Proposed]**: the author's recommendation. It stands unless a reviewer
  or the owner objects.
- **[Open]**: needs a decision. It is also listed in section 17 (Open
  questions).
- **[Verify]**: a claim about Android behaviour that must be confirmed on the
  Pixel before the feature is called done.

"MUST", "SHOULD" and "MAY" carry their usual RFC 2119 meaning.

### 0.2 Section prefixes

| Prefix | Section |
|---|---|
| `PRD` | 3. Product |
| `ARC` | 4. Architecture |
| `SEC` | 5. Security and consent |
| `AGT` | 6. Agents and the setup prompt |
| `API` | 7. API conventions |
| `CAP` | 8. Capabilities |
| `EVT` | 9. Events and delivery |
| `LOG` | 10. Logging |
| `FBK` | 11. Bug reports and feature requests |
| `UI` | 12. Screens and design |
| `NFR` | 13. Non-functional requirements |
| `TST` | 14. Testing and release |
| `PER` | 15. Per-agent permissions (deferred) |
| `—` | 16. Roadmap and phases; 17. Open questions; Appendices A-F |

### 0.3 Glossary

| Term | Meaning |
|---|---|
| **Agent** | An AI system Rahul has given access, for example Muse, Grok, Claude or Codex. Each has a name and its own key. |
| **Key** | The secret an agent sends as `Authorization: Bearer <key>`. One per agent. Bridget stores only a salted hash. |
| **Setup prompt** | A block of text Bridget generates when an agent is added. It contains the key, the address and the rules, and is pasted into the agent so it can connect immediately. |
| **Capability** | Something an agent can ask the phone to do, for example take a photo or read the location. |
| **Master switch** | The on/off switch for a capability. In v2 it applies to all agents. |
| **Interaction** | Something an agent sends to Rahul: a one-way **notice**, or a **question** that waits for his answer. Never called "prompt" or "query" in the product. |
| **Grant** | A time-limited approval Rahul gives on the phone, for example "screenshots for 1 hour". |
| **Listener** | Bridget's always-on HTTP server, which runs inside a foreground service. |
| **Tailnet** | Rahul's private Tailscale network. Bridget is reachable only from it. |
| **Audit log** | The record of every request, setting change and event. Readable by agents through the log API. |

---

## 1. Decisions log

Decisions made by the owner while specifying and building 1.0-1.2. Reviewers
should treat these as settled unless a serious problem is found.

| # | Date | Decision | Status |
|---|---|---|---|
| D1 | 26 Oct 2026 | The listener is **always on** (one foreground service with a permanent notification), not "only while the app is open". | Decided |
| D2 | 26 Oct 2026 | Gallery access is **full** access. Selected-photos mode is still detected and reported. | Decided |
| D3 | 26 Oct 2026 | Source lives in `android/` in the private `rahuliofam/finleg` repo. Releases are private GitHub releases. | Decided |
| D4 | 26 Oct 2026 | The app is named **Bridget** (was "Almusca Device Bridge"). Package `net.finleg.bridget`. | Decided |
| D5 | 26 Oct 2026 | Bridget is **general-purpose**: any number of named agents, each with its own key, with nothing tied to one agent vendor. | Decided |
| D6 | 26 Oct 2026 | Four agents are paired: **Muse, Grok, Claude, Codex**. Keys are in Bitwarden as "Bridget — <Name> agent key". | Decided |
| D7 | 26 Oct 2026 | Fast "last image" endpoint, sized for AI vision: long edge **1568 px, JPEG quality 85**, optional raw JPEG body. | Decided |
| D8 | 26 Oct 2026 | Agents reach Rahul through **interactions**: a **notice** (one-way) or a **question** (needs an answer). Either may be **spoken aloud**. | Decided |
| D9 | 26 Oct 2026 | "Controls" idea #7 is narrowed to the **flashlight** first. Other controls are listed in this spec as Proposed. | Decided |
| D10 | 26 Oct 2026 | Screenshots need an on-phone approval: **Allow once / Allow for 1 hour / Allow for 16 hours / Deny**. | Decided |
| D11 | 27 Oct 2026 | **Per-agent permissions are deferred.** In v2 all switches are global. The data model keeps room for them (section 15). | Decided |
| D12 | 27 Oct 2026 | **Logging is available programmatically to every agent** (section 10). | Decided |
| D13 | 27 Oct 2026 | Adding an agent produces a **setup prompt** containing the key that can be pasted into any agent for immediate access (section 6). | Decided |
| D14 | 27 Oct 2026 | Bug reports and feature requests copy the **Spotka** apps' reporter (sponic repo), adapted to Bridget (section 11). | Decided |
| D15 | 27 Oct 2026 | Screens use **native Kotlin Android Views with Material 3 components**, with a "techie but friendly" look (section 12). | Decided |
| D16 | 27 Oct 2026 | Spec **"maximum capabilities"**: describe everything Android allows that's useful to a personal agent, phased (section 16). | Decided |

## 2. Changes from spec v1.0

v1.0 (26 Oct 2026, "Almusca Device Bridge") is the PDF in this folder. These
are the deliberate departures. Anything not listed still applies.

| v1.0 said | v2.0 says | Why |
|---|---|---|
| One agent (Almusca), one token | Any number of named agents, max 16, each with its own key | D5 |
| Pairing: on-screen code, the agent calls `/v1/pair` | Primary: a **setup prompt** with the key, copied from the phone. The code flow stays as an alternative. | D13 |
| "Smallest possible surface. Three capabilities in v1." | Many capabilities, each behind its own master switch, phased in | D16 |
| "No network calls other than answering API requests" | Also: (a) **callbacks** to agent webhook URLs Rahul approves on the phone, (b) **feedback upload** if Rahul turns it on, (c) optional address lookup for location. Each is off by default. | Sections 9, 11, 8.8 |
| "No third-party SDKs" | Still none for analytics or ads. Allowed: Ktor, CameraX, ZXing, AndroidX, Material, and Google ML Kit's **bundled, offline** text recognition (if OCR is built) | 8.15 |
| No access to messages, contacts, other apps' data | Unchanged for v2. Communications is listed as a possible later module, **not scheduled** (8.22) | Owner decision pending, Open Q1 |
| "Version 1 accepts Android stopping the listener" | Same, plus the always-on service (D1) and battery exemption on the Access screen | D1 |
| Audit log: 1,000 entries, read via `/v1/audit` | Structured log store with query, tail and agent notes; 20,000 entries / 30 days; `/v1/audit` kept as an alias | D12 |
| Token: 64 hex characters | New keys `brg_` + 43 base64url characters. Existing 64-hex keys keep working. | 5.3 |

---

## 3. Product

### 3.1 Purpose

- **PRD-01 [Decided]** Bridget gives Rahul's personal AI agents controlled,
  visible, logged access to his phone: its sensors (camera, microphone,
  location), its data (photos, screenshots, files, phone status), its
  controls (flashlight and similar), and Rahul himself (notices and questions).
- **PRD-02 [Decided]** It's for one person and one phone. It is not a
  product for others, and has no accounts, cloud relay, analytics or ads.

### 3.2 Principles (carried from v1, extended)

- **PRD-03 [Decided] Request-driven.** Bridget does nothing with a sensor
  until an authenticated request arrives, and stops as soon as the request
  completes. The only standing exceptions are time-limited grants Rahul
  approves on the phone (screenshots).
- **PRD-04 [Decided] Visible by design.** Every camera, microphone,
  location and screen capture is announced on the phone: a notification
  naming the agent, Android's own privacy indicator, and the log.
- **PRD-05 [Decided] Fail closed.** A missing permission, a switch turned
  off, an expired grant or a bad key returns an error. Nothing falls back to
  a weaker path.
- **PRD-06 [Decided] The phone is the record.** The log lives on the phone.
  Agents can read it; Bridget doesn't send it anywhere on its own.
- **PRD-07 [Proposed] Agent-friendly.** The API describes itself
  (`/v1/guide`, `/v1/openapi.json`), returns data sized for AI models by
  default, and gives every error a stable code and a plain next step.
- **PRD-08 [Proposed] Two taps or fewer** for any setting Rahul must change.
  Bridget deep-links to the exact Android page (section 12.12).

### 3.3 Users

| User | Needs |
|---|---|
| **Rahul** (owner) | Add an agent in under a minute; see at a glance what's on and what agents did; answer agents quickly; switch anything off instantly; report a bug in seconds. |
| **Agents** (Muse, Grok, Claude, Codex, future) | Connect from a pasted prompt with no other setup; discover what's available; get fast, small, readable images; know exactly why a request failed; read the log; reach Rahul. |
| **Developer agents** (Claude, Codex working on Bridget) | Read bug reports, feature requests and diagnostics straight from the phone through the API. |

### 3.4 Not in scope

- **PRD-09 [Decided]** No continuous or scheduled capture, live streaming,
  call recording, always-on listening, or location history/tracking.
- **PRD-10 [Decided]** No access to other apps' private data (messages
  inside WhatsApp, banking apps and so on). Android forbids it, and Bridget
  doesn't try.
- **PRD-11 [Decided]** Nothing is done invisibly. Bridget never suppresses
  Android's camera, microphone, location or screen-share indicators.
- **PRD-12 [Decided]** No public internet exposure. Bridget listens only on
  the Tailscale interface.

---

## 4. Architecture

### 4.1 Shape

```
Agent host (on tailnet) ──WireGuard (Tailscale)──▶ Pixel: Bridget
                                                   ├─ BridgeService (foreground service, always on)
                                                   │   ├─ HTTP listener (Ktor CIO), bound to 100.x only
                                                   │   ├─ Capability modules (camera, mic, gallery, …)
                                                   │   ├─ Event bus → long-poll waiters, callbacks
                                                   │   └─ Log store (SQLite)
                                                   └─ UI (single Activity, fragments, Material 3)
```

- **ARC-01 [Decided]** Two parts only: the agent (anywhere on the tailnet)
  and the app. No server in the middle.
- **ARC-02 [Decided]** One long-lived foreground service (`BridgeService`)
  runs the listener and owns every sensor. It starts from a tap while the
  app is visible, so Android grants it camera, microphone and location access
  while it runs. Its foreground-service types are the union of the granted
  ones among `specialUse`, `camera`, `microphone`, `location` and, during a
  screenshot grant, `mediaProjection`.
- **ARC-03 [Decided]** If Android restarts the service in the background, it
  comes back with `specialUse` only. Gallery, logs, interactions, files and
  status keep working; camera, microphone, location and screenshots return
  `service_start_blocked` until Rahul opens the app once. Home shows a banner
  saying so.
- **ARC-04 [Decided]** The listener binds only to the phone's Tailscale IPv4
  (100.64.0.0/10), checks every 10 s, and rebinds when the address changes. It
  never binds `0.0.0.0`. It also rejects any caller outside 100.64.0.0/10
  (`forbidden_source`).
- **ARC-05 [Proposed]** Code is organised as **capability modules**. Each
  module declares its endpoints, the Android permissions or special access it
  needs, its foreground-service type, its master switch, and a `status()`
  used by `/v1/capabilities`, the Features screen and the Access screen.
  Adding a capability means adding one module.

### 4.2 Code structure (target)

- **ARC-06 [Proposed]** Single `MainActivity` with the AndroidX Navigation
  component and one Fragment per screen. ViewModels expose `StateFlow`.
  Layouts are XML with ViewBinding. Lists use `RecyclerView` with
  `ListAdapter`/`DiffUtil`. This replaces 1.2's single programmatic screen.
- **ARC-07 [Proposed]** Packages: `api` (Ktor routes, auth, rate limits,
  errors), `core` (agents, policy, settings), `log` (SQLite store and query),
  `events`, `capability.*` (one per module), `ui.*` (one per screen),
  `feedback`.
- **ARC-08 [Proposed]** Storage, all app-private and excluded from backup:
  - `SharedPreferences` for settings and switches;
  - SQLite (framework `SQLiteOpenHelper`, no Room/KSP) for the log, the
    inbox, events, shares, files metadata, grants and the feedback outbox;
  - files under `filesDir` for inbox files and queued feedback screenshots;
  - `cacheDir` for temporary captures, deleted after every response.
- **ARC-09 [Proposed]** Libraries: Kotlin 2.x, AndroidX (core, appcompat,
  fragment, navigation, lifecycle, recyclerview, exifinterface, health
  connect client if built), Material Components 1.12+, CameraX, Ktor server
  CIO, ZXing core, ML Kit text recognition (bundled) if OCR is built. No
  analytics or crash SDKs.

### 4.3 Transport

- **ARC-10 [Decided]** Reachable only over Tailscale. MagicDNS name
  `pixel-10-pro-xl.tail9c9221.ts.net`, IP 100.94.27.79, port 8734
  (configurable).
- **ARC-11 [Decided]** Agent hosts running Tailscale in **userspace mode**
  (for example Grok's box) can't open direct connections to tailnet devices.
  They use the `android/tools/bridget` helper, which tunnels through
  `tailscale nc`, or enable tailscaled's SOCKS5/HTTP proxy. The setup prompt
  covers both (section 6.5).
- **ARC-12 [Proposed]** HTTP only (WireGuard encrypts the tunnel). HTTPS with
  Tailscale certificates is listed as Open Q8.

### 4.4 Relay mode: reaching Bridget without Tailscale (push-woken mailbox)

Direct mode (4.3) needs every agent on the tailnet. Many useful agents can't
join it: hosted assistants, cloud automations, ChatGPT-style actions, email or
SMS gateways. Relay mode adds a second way in where **the phone never accepts
inbound connections**: agents leave jobs in a mailbox on the internet, a push
message wakes the phone, and the phone fetches the job, runs it and posts the
result back.

```
Agent (anywhere) ──HTTPS──▶ Relay (Supabase edge function + tables + storage, finleg project)
                                │  stores job (encrypted), sends push
                                ▼
                     FCM high-priority data message ──▶ Pixel: Bridget wakes
                                                          fetches job ─▶ runs capability
                                                          uploads result (encrypted) ─▶ Relay
Agent ◀── long-poll / webhook ── Relay ◀─────────────────────────────────────┘
```

- **ARC-13 [Proposed]** Relay mode is **off by default** and turned on per
  agent ("How does this agent connect? Tailscale / Relay"). Direct mode stays
  the default and the fastest path.
- **ARC-14 [Proposed]** The relay API mirrors the direct API: an agent sends
  the same method, path and body to
  `https://<relay>/functions/v1/bridget-relay/v1/...` with the same agent key.
  The relay queues it as a job and answers with the phone's response when it
  arrives (held up to 55 s, then `202 {"job_id"}` to poll with
  `GET .../jobs/{id}?wait=55`). The agent doesn't need to know which mode
  it's using beyond the base URL.
- **ARC-15 [Proposed] End-to-end encryption.** When an agent is created in
  relay mode, the phone derives a per-agent content key from the agent key
  (HKDF-SHA256, info `"bridget-relay-v1"`) and stores it encrypted with an
  Android Keystore key. Request bodies and results (including images) are
  sealed with AES-256-GCM using that content key. The relay sees only
  metadata (agent id, time, size, endpoint name), never content or keys.
  Agents that use the relay must use the `bridget` helper or an equivalent
  library to seal and open payloads.
- **ARC-16 [Proposed] Wake-up.** Firebase Cloud Messaging high-priority data
  messages wake the phone even in Doze. FCM is the only option with
  Pixel-level delivery guarantees; it needs a Firebase project and the
  Firebase Messaging library (an exception to "no third-party SDKs", Open Q9).
  The alternative is UnifiedPush through a self-hosted ntfy server (no
  Google, but delivery in Doze is weaker). The relay sends at most one push
  per job and coalesces bursts.
- **ARC-17 [Decided] Android rules still apply.** A push doesn't let Bridget
  open the camera or microphone from the background. Relay mode works
  through the same always-on service (ARC-02), and if that service lacks
  camera or mic access, relay jobs get the same `service_start_blocked`.
- **ARC-18 [Proposed]** Relay rate limits mirror SEC-06/07 per agent, plus a
  relay-side cap of 600 jobs per agent per day. Jobs expire after 10 minutes
  if the phone doesn't pick them up (`phone_unreachable`, 504).
- **ARC-19 [Proposed]** Relay mode changes spec v1 "no cloud relay". It is
  listed as Open Q10 for the owner; nothing in Direct mode depends on it.

**Scenarios relay mode enables (beyond direct calls):**

| Scenario | How |
|---|---|
| A hosted agent (for example a cloud Muse) uses the phone without joining Tailscale | Relay mode, same API |
| Phone on mobile data with Tailscale off or broken still reaches agents | Phone talks outbound HTTPS only |
| "Ask Rahul" from anywhere: any system can send a notice or question by HTTPS, and the answer comes back by webhook or long poll | Interactions (8.13) over the relay |
| Bridget's listener can sleep: the push wakes it, saving battery (later, if always-on proves costly) | FCM wake + on-demand listener (Open Q11) |
| Email-to-phone or SMS-to-phone triggers (forward an email to an address, it becomes a notice) | Relay inbound adapter, later |
| Several devices (a second phone, a tablet, a watch) behind one agent key | Relay routes by device id, later |

---

## 5. Security and consent

### 5.1 Layers

- **SEC-01 [Decided] Network:** tailnet only (ARC-04, ARC-10). Recommended
  hardening: a Tailscale ACL that allows only agent hosts to reach port 8734.
- **SEC-02 [Decided] Key:** every request except `POST /v1/pair` needs a
  valid agent key. Keys are compared in constant time against salted SHA-256
  hashes; plaintext keys are never stored.
- **SEC-03 [Decided] Master switches:** each capability has a switch,
  changeable only on the phone. All switches start **off** on a fresh install.
- **SEC-04 [Decided] Grants:** screenshot access additionally needs a
  time-limited grant approved on the phone (8.10).
- **SEC-05 [Decided] Visibility:** PRD-04.

### 5.2 Rate limits

- **SEC-06 [Decided]** 5 failed key attempts per minute per source IP, then
  every request from that IP gets `rate_limited` for the rest of the minute.
- **SEC-07 [Decided]** 10 sensor captures (camera, mic, video, screenshot,
  location fresh fix) per minute per source IP.
- **SEC-08 [Proposed]** Per-capability limits are listed with each
  capability in section 8. Long-poll waits don't count.

### 5.3 Keys

- **SEC-09 [Proposed]** New keys are `brg_` + 43 base64url characters (256
  random bits). The prefix lets secret scanners (gitleaks custom rule, GitHub
  push protection custom pattern) catch leaked keys. Existing 64-hex keys
  keep working until rotated.
- **SEC-10 [Decided]** A key is shown exactly once, inside the setup prompt
  (6.4). Afterwards it can't be shown again; **Rotate key** issues a new one
  and a new prompt, and the old key stops working immediately.
- **SEC-11 [Proposed]** Copying the prompt or key marks the clip as
  sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`), so Android hides the
  preview. If Bridget is in the foreground 2 minutes later and the clip is
  still Bridget's, Bridget clears it.
- **SEC-12 [Decided]** Pasting a key into a hosted AI service is Rahul's
  accepted risk. The UI says so in one line, and makes rotation one tap.

### 5.4 Data handling

- **SEC-13 [Decided]** Captures are returned per request and not kept.
  Agent photos never enter the gallery.
- **SEC-14 [Decided]** The log never contains media, keys or full message
  bodies. Free-text parameters are truncated to 200 characters.
- **SEC-15 [Decided]** App backup is disabled (`allowBackup=false` and data
  extraction rules), so keys, logs and the inbox never leave the phone
  through Google backup or device transfer.
- **SEC-16 [Proposed]** Outbound network calls are limited to: approved
  callback URLs (9.3), the feedback endpoint if upload is on (11.6), and
  Google's geocoder if address lookup is on (8.8). Each one is logged.

### 5.5 Threat model (summary)

| Threat | Mitigation |
|---|---|
| Another tailnet device calls Bridget | Needs a valid key (SEC-02). ACL hardening (SEC-01). |
| A key leaks (pasted chat, log, repo) | One-tap rotation (SEC-10); `brg_` prefix for scanners (SEC-09); Bitwarden as the store of record. |
| A paired agent misbehaves (captures too much) | Notification and indicator every time (PRD-04); full log; rate limits; master switches; remove agent in one tap; per-agent permissions later (section 15). |
| Phone lost | Remove it from the tailnet in the Tailscale admin console; Find My Device erase; rotate agent keys (v1 section 5.5, unchanged). |
| LAN attacker sends packets to the 100.x address | Listener binds only to the Tailscale address, and the source check rejects non-tailnet callers (ARC-04). |
| Malicious HTML in an interaction | HTML cards run with no network, no storage and a bridge that can only submit an answer (8.13). |
| Spoofed agent in the UI | Bridget always draws the agent name itself; agent content can't style the header (8.13). |
| Prompt injection through data an agent reads (screenshots, shared files) | Out of Bridget's control; the setup prompt tells agents that phone content is data, not instructions (Appendix B). |
