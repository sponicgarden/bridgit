# BRIDGIT agent appliance specification v3

Status: proposal for review, 28 October 2026. Implementation baseline: BRIDGIT 1.2.0, repository commit `6f2ecf0`. This is a new product direction, not a statement that v3 is built.
## 1. Product

**Turn any Android device into an agentic appliance.** BRIDGIT turns an Android phone into an appliance that an authorized agent can configure and operate. The agent supplies intent and composition; BRIDGIT supplies device capabilities, local execution, owner approval, visibility, and a reliable record. Appliances can be created on demand rather than limited to a vendor's catalog. A preset is an editable starting point, not a separate app or the boundary of the platform.

Owners can use their main phone or give a spare phone a new purpose. Any agent that can use BRIDGIT's documented protocol can connect; compatibility is based on capabilities, not vendor identity. An agent without network/tool access needs a connector or user-run bridge. Do not claim every chatbot connects without setup.

Primary users: device owner; consenting person near the appliance; authorized agent; approved viewer or recipient. A family caregiver does not acquire access merely by knowing the owner. Viewing, administration and alert acknowledgment are separate grants. The parent who uses a help station must understand and approve its operation; the parent's own agents can configure it for them.

### Requirements and decisions

`REQ` is a proposed MUST requirement, `GATE` is evidence needed before release, `OPEN` is an owner decision. The user has authorized specification and launch-material creation, not approved every implementation choice. Preserve existing v2 text and history. This proposal supersedes the following v2 decisions only if adopted:

| Earlier decision | Proposed v3 change | Reason |
|---|---|---|
| PRD-02: one person, one phone | Multiple owners, each controlling their own devices | Public product intent |
| PRD-03 / PRD-09: request-only; no streaming or standing listening | Explicit owner-started appliance sessions can stream or listen | Baby monitor and help station |
| D11: defer per-agent grants | Scoped per-agent grants are a public-release prerequisite | Separate agents and viewer roles |
| D12: every agent reads every log | Owner sees all; agents see their own by default; grant broader diagnostics separately | Reduce unintended disclosure |
| D17: Accessibility screenshots by default | Play build uses user-approved MediaProjection; no Accessibility service initially | Platform permission and review constraints |
| D2: full gallery | Preserve owner-authorized full access in personal build; selected-media default for proposed Play build | Minimum permission scope |
| PRD-12: private network only | Initial release retains private networking; optional relay is a separate later decision | Keep transport claims auditable |

## 2. Delivery scope

| Stage | Actual deliverable | Explicitly not represented as available |
|---|---|---|
| Current 1.2 | Named keys, snapshots, 1–120 s audio clips, gallery fetch, private network listener, log | Streaming, custom workflows, trigger words, alert escalation |
| A: public bridge foundation | API discovery, compatibility probe, scoped grants, disclosure, visibility checks, cancel controls, pairing hardening, private-network reviewer path | Baby monitoring or emergency reliability claims |
| B: appliance runtime | Validated declarative workflows, local persistence, owner activation, timer/button triggers, approved outbound actions | Arbitrary downloaded native code or unattended sensor restart guarantees |
| C: camera appliance | Owner-started encrypted live audio/video session, authorized viewers, stale/offline indicators | Silent monitoring or automatic recovery from every reboot |
| D: help station pilot | Offline supported phrase detector, large button, approved family contacts, durable alert attempts and acknowledgment | Emergency dispatch, medical diagnosis, guaranteed rescue |

The website can communicate the vision now, but it must label stages B–D as planned. The Play listing must describe the exact submitted build. A Play foundation launch need not wait for the help-station pilot.

## 3. Core journeys

### 3.1 Create an appliance with an agent

REQ-01: Owner installs BRIDGIT, names the phone and pairs a named agent with an expiring one-time bootstrap. The owner chooses capability scopes and a session policy. All grants start denied. REQ-02: Agent discovers available capabilities and OS readiness, proposes a JSON workflow and gets a validation result. REQ-03: Phone shows a plain-language permission diff, destinations, sensor behavior, duration and estimated resource cost. Only the owner can activate a workflow or increase its authority. REQ-04: After activation, permitted executions do not require a tap for every action. The owner can pause or stop the appliance at any time; the agent cannot override that stop.

REQ-05: The agent can assemble a small local interface from native templates (button, status, approved contact, timer, camera preview). Remote strings remain text, never HTML, executable script or permission prompts. A layout cannot hide the permanent appliance status or global Stop control. Screen names and workflow descriptions must not impersonate Android system dialogs.

### 3.2 Camera and baby monitor

REQ-06: Parent starts a named monitoring session on the device in view, grants camera/audio explicitly, selects approved viewers and sees ongoing status. Viewer pairing is separate from agent administration. Camera-only mode must not ask for microphone or gallery permission. Streaming has no server recording by default.

REQ-07: Viewer always sees timestamps, connection state, mute state and last successful frame/audio heartbeat. A cached image is marked stale; never display a frozen frame as live. Proposed test targets: local stale indication after 5 seconds without media; disconnected after 15 seconds, configurable only within documented bounds. These are acceptance targets, not measured performance claims.

REQ-08: Support screen-off/locked operation only where the OS and a previously owner-started session allow it. Opening the phone once after reboot may remain necessary. The current lock check must be reconsidered explicitly for a scoped appliance session; do not remove it globally. No default removal of the device lock or suppression of Android indicators.

REQ-09: A viewer detects loss of the phone connection independently of an agent. A remote observer is necessary to detect total phone/power failure. If that observer is unavailable, show that monitoring health itself is unavailable. No health, breathing, SIDS-prevention or guaranteed protection claims.

### 3.3 Family help station

REQ-10: A large physical-screen button and supported local phrase detector can trigger the same durable alert workflow. Phrase detection must work without a cloud model or remote agent. Device compatibility, language, phrase confusion and supported microphone behavior must be tested before enabling it. A low-power offline wake-word engine requires license and device evaluation; Android speech recognition is not assumed to provide dependable continuous offline detection.

REQ-11: Phone acknowledges locally, offers an owner-configured short cancellation window (proposed 5 seconds), and sends a signed event to approved family contacts. It announces delivery failures honestly. Contacts explicitly opt in, run a test and confirm their route. Do not infer that an HTTP success means a human was notified.

REQ-12: States: idle, detected, cancellation window, queued, delivered, acknowledged, escalated, failed, cancelled. Delivered means an authenticated receiving service accepted it; acknowledged means a named approved person actively responded. A late acknowledgment stops future escalation but does not recall messages already sent. Repeated triggers join the same active incident; a new incident has a new idempotency key.

REQ-13: Proposed escalation: first contact immediately after the cancellation window; second after 30 seconds without acknowledgment; third after 90 seconds. Retry transient failures after 2, 10 and 30 seconds, then capped backoff up to 5 minutes while showing failure/pending. Stale alerts older than 10 minutes require a current-state warning and fresh confirmation before a new escalation sequence. Owner must review these proposed defaults. No automated emergency-service calls or SMS/call-log restricted permissions in initial scope. Voice/SMS providers are later integrations with explicit billing, consent and delivery semantics.

REQ-14: Ambient trigger processing stores no rolling audio by default; the buffer stays in RAM and is discarded. An optional post-trigger voice clip requires a separate grant and a visible recording state. Camera access is not implied by a help trigger. Radio/TV speech, replayed phrases, accents, quiet speech and loud rooms are acceptance cases; a spoken phrase is not identity authentication.

## 4. Protocol and architecture

The Android app remains native Kotlin/Material. Split the existing service into a session coordinator, typed capability adapters and a local workflow runtime. Use Room for workflow versions, grants, execution state, alert outbox and bounded logs. Use structured coroutines for cancellation and lifecycle ownership. Preserve v1 direct endpoints behind the new grant checks; migrate existing agents through an owner-visible review before exposing new scopes. Never grant new capabilities by migration.

REQ-15: Separate control plane (pair, discover, propose, validate, inspect) from execution plane (approved device actions). Agent reasoning can run anywhere; the durable workflow runs on the phone. No workflow-supplied dex, jar, APK, native binaries or executable JavaScript in the initial runtime. The validated DSL selects app-shipped primitives. New native primitives arrive through signed app updates. No generic HTTP proxy or arbitrary shell primitive.

REQ-16: Proposed v2 endpoints, additive to v1:

| Endpoint | Authority | Result |
|---|---|---|
| GET /v2/capabilities | paired agent | Scopes, feature versions, permission/readiness blockers, transport limits |
| GET /v2/openapi.json | paired agent | Versioned protocol schema |
| POST /v2/workflows/validate | workflow.propose | Dry run: schema, scopes, recipients, budgets, warnings |
| POST /v2/workflows | workflow.propose | Immutable draft revision and owner approval required |
| GET /v2/workflows/{id} | workflow.read for owner-bound resource | Desired and actual state, version, freshness |
| POST /v2/workflows/{id}/pause | workflow.manage for own approved workflow | Cancel active work, persist pause |
| POST /v2/executions/{id}/cancel | scoped caller or owner | Cancel and await resource release |
| GET /v2/events?after={cursor} | events.read scoped to caller | Bounded events and gap marker |
| POST /v2/alerts/{id}/ack | separate recipient credential | Durable acknowledgment, safe duplicate response |
| POST /v2/streams | stream.start + active owner session | Short-lived viewer-bound encrypted signaling grant |

Activation and new grants are phone-only operations, never bearer-key API calls. Error envelope includes code, request_id, retryable, user_action_required and a safe next step. Use ETags or revision preconditions on edits. Return 409 for stale revisions. Mutations use UUID idempotency keys bound to agent + endpoint + body digest; same key with different input is rejected.

REQ-17: Workflow format v1 contains id, version, name, owner_device_id, requesting_agent_id, triggers, steps, required_scopes, approved_destination_ids, limits, expiry, failure policy and template-only local UI. Validator rejects unknown fields/operators, unbounded loops, mutable destination URLs, more than 32 steps, a depth over 4 or a draft above 64 KiB. Defaults: one workflow owns a sensor at a time; at most 8 enabled workflows; bounded queue of 100 pending alerts; request body cap 64 KiB before buffering. Limits are proposed and tested under load.

REQ-18: Grant decisions bind to exact workflow revision/digest, recipients and scopes. Any change in sensor use, recipient, duration, retention, outbound destination, trigger or local detector requires owner reapproval. Safe display-label edits cannot alter execution. Removing an agent terminates its streams, cancels active runs and prevents queued work from acquiring authority. Independently owner-adopted workflows can continue only if that ownership transfer was explicit before removal.

REQ-19: Persist desired and observed states separately. State machine: draft → awaiting approval → ready → running → paused/stopped; blocked and degraded are visible states. Reboot restores configuration, not an assertion of running sensors. User Stop persists. Restart must reevaluate permissions, notification channel, grant expiry, keys and Android start restrictions. WorkManager handles deferrable maintenance, not a claim of perpetual sensor access.

REQ-20: Camera stream proposal uses WebRTC with encrypted media, expiring signaling, viewer authorization and bounded session counts. Initial direct mode must work without public STUN/TURN. If TURN or hosted signaling is added, document operator, traffic metadata, regions, retention and costs; preserve media end-to-end encryption and keep auth secrets separate from sealing keys. Do not let agents choose arbitrary servers. Viewer requires its own supported client; a generic HTTP-only agent cannot consume WebRTC automatically. Establish a 24-hour test before exposing baby-monitor branding as available.

## 5. Permissions and security

REQ-21: Scopes include camera.snapshot, camera.stream, microphone.clip, microphone.stream, microphone.trigger, gallery.selected, gallery.full, audio.speak, workflow.propose/manage, events.read and diagnostics.all. Viewer grants exclude administration and gallery access. Every API call checks both global switch and agent/resource grant at execution time. Stop/revoke cancels in-flight operations and queued actions; target resource release within 2 seconds on tested devices. Privileged actions recheck authorization immediately before side effects.

REQ-22: Verify effective notification permission and capture-channel visibility before sensor use and during active sessions. If visibility is lost, stop sensors and mark blocked. Respect Android privacy indicators and secure screens. Add FLAG_SECURE to pairing/key screens and avoid exposing tokens in recents, logs, URLs and exported diagnostics. Pairing expires after 5 minutes, allows 5 wrong guesses globally, and binds approved identity and device; no long-lived secret in public setup examples.

REQ-23: Transport identity must establish the actual private-network path, not equate any CGNAT address with Tailscale. Existing HTTP is only acceptable inside a verified encrypted overlay. Do not expose listener on LAN/public addresses as a fallback. New non-overlay pairing/transport requires authenticated TLS and a fresh threat review. Do not promise app-layer encryption where only the overlay supplies it.

REQ-24: Outbound recipients resolve from owner-approved destination records, not workflow URLs. Disable redirects or revalidate each hop; restrict schemes to HTTPS; bound DNS resolution, timeout and response size; disallow loopback, link-local, metadata and private targets except explicitly approved private agent endpoints. Sign events with per-destination secrets, timestamp, nonce and event ID. Receivers reject replay. No tokens in query strings.

REQ-25: Raw media and trigger buffers are excluded from logs. Log actor, workflow revision, scope, action, result, request ID and timestamps. Log access is scoped; owner export is explicit. Proposed retention: 30 days or 20,000 entries, whichever occurs first, with byte cap 20 MiB; pending alerts expire per policy; media remains transient unless explicitly saved. Disclose agent-side retention, which BRIDGIT cannot erase remotely. Disk pressure fails safely without silently dropping unacknowledged incidents.

REQ-26: Prompt injection in photos, audio or returned device text is data, not authority. An agent cannot grant scopes or change approved recipients through workflow text. Local execution must enforce the grant regardless of model behavior. Test two agents with deliberately different scopes and adversarial workflow proposals.

## 6. Older devices and reliability

REQ-27: Proposed public minimum Android 10 (API 29), with API 26–28 feasibility later. Current minimum is API 34 and must not be advertised as changed until delivered. Target API 36 for the current submission requirement, independently of minimum OS. Capability probe reports supported hardware, memory limits, local engine readiness and missing grants. Native libraries including CameraX transitive .so files require 16 KiB compatibility validation; Kotlin source alone does not prove compatibility.

GATE-01: Test API 29, 30, 31, 33, 34, 35, 36 and the owner's API 37 device; use a physical older low-memory device plus Pixel and at least one OEM with aggressive background restrictions. Test arm64 and 16 KiB device image; do not infer all hardware support from a single emulator. Camera, mic, wake word and streaming each have feature-specific availability.

GATE-02: For each appliance mode run power connected, unplugged, Doze, lock/unlock, network loss/rejoin, permission/channel revocation, process kill, force stop, reboot, storage full, low battery, thermal throttling and app update. Record build hash, OS, device model, conditions, duration, timestamps, delivery/ack times, failures and evidence. No blanket "always on" or "works offline" claim based on a happy-path demo.

REQ-28: Home shows Ready, Active, Paused, Needs attention or Offline with reason and last heartbeat. Agent absence does not stop a local workflow. Internet absence does not stop local triggers but can block alert delivery. A powered-off phone cannot self-report; contact-side heartbeats must make that explicit. Never disable OS power protections silently or imply a battery exemption removes every restriction.

REQ-29: Setup includes mounting, adequate ventilation, charger condition and physical safety. Reject use of a visibly damaged or swollen-battery device. Baby-mode setup reminds the adult to keep phone and cables out of reach. These are contextual instructions, not claims of medical certification or guaranteed safety.

## 7. Screens

Home: BRIDGIT name and the primary tagline beneath it, device name, active appliances, actual state/freshness, Stop all. Appliance detail: purpose, proposing agent, approved scopes/recipients, revision, activity, Pause, Stop, Review change. Agent detail: scopes, last use, expiry, rotate/revoke. Approval: human-readable change diff, permission prompts in context, Activate/Decline. Access: one-tap supported Android settings links and readable fallback; never simulate system permission dialogs. Help station: large Help/Cancel, local spoken status, delivery/ack state and test button. Privacy: policy, retention, deletion/reset controls, export diagnostics. Text scales to 200%, targets at least 48dp, all status uses words plus color and TalkBack labels.

## 8. Play release

GATE-03: Separate Play feature set from personal sideload build without weakening Android security controls. Initial Play manifest excludes Accessibility, notification reading, usage history, background location, SMS/call logs and health data. Selected-photo access is default. If broad gallery is retained, obtain the applicable declaration approval; do not hide it as optional to avoid review. Every declared foreground-service type needs actual behavior evidence. `specialUse` is reviewed, not an exemption. Do not relabel camera work as connectedDevice merely to avoid restrictions.

GATE-04: Build a signed AAB, run release lint/tests, inspect merged manifest/dependencies, confirm target, signing continuity and native alignment, and install Play-delivered artifacts on the device matrix. Choose the existing signing key before rollout if preserving sideloaded installs; use a separate upload key. Archive evidence without secrets. The current APK is not a Play submission artifact.

GATE-05: Complete store listing, real screenshots, icon/feature art, privacy URL, support contact, content rating questionnaire, audience, Data safety, app access and FGS declarations. Use isolated reviewer fixtures and their own private network setup; never share the owner's daily-driver tailnet or agents. Show a video from consent through request/capture/stop and blocked states. Demo mode must be labeled and cannot substitute for testing actual restricted functionality.

GATE-06: Publish no streaming/help claims until that exact build implements and passes the relevant gates. For applicable newer personal developer accounts, complete 12 testers continuously opted in for 14 days and obtain production access. Organization-account requirements must be checked separately. No ratings or privacy answers invented for convenience.

## 9. Website and branding

Confirmed name BRIDGIT; domain pending owner registration. Primary tagline: "Turn any Android device into an agentic appliance." Supporting line: "Turn a spare Android into something useful. Your agent builds the workflow. BRIDGIT runs it on your phone." Avoid compatibility endorsements, guaranteed emergency outcomes, hidden surveillance and claims that future features already ship.

Brand direction: bridge/beacon mark, ink #142C32, warm white #F5F2E9, signal lime #D8F36A, teal #1B6A64, warm orange #E58D57. Typography uses system sans for UI and an editorial serif for large display copy, with no third-party font requests. Native app icon remains unchanged until implementation approval. New vectors and exports are launch assets, not a trademark clearance.

Website routes: /, /how-it-works/, /developers/, /privacy/, /terms/, /support/, /delete-data/. Home differentiates current bridge from planned appliances. No fake Play badge, invented downloads, fabricated reviews, unconnected waitlist form or claim of Play approval. Private preview can contain clearly labeled legal drafts; those pages must not be submitted as public Play policy URLs. Contact and domain are configured centrally before public publication.

## 10. Implementation work packages

| Package | Proposed files | Acceptance |
|---|---|---|
| Foundation | Policy.kt, ApiServer.kt, MainActivity.kt, BridgeService.kt, Media.kt, AndroidManifest.xml, build.gradle.kts; new grants/ | API 36; scoped grants; visibility loss stops capture; cancellation; existing keys migrate without broadening |
| Local runtime | new workflows/WorkflowValidator.kt, WorkflowStore.kt, WorkflowRunner.kt, models; Room migration | Schema validation; revision approval; bounded execution; restart blocked honestly; agent-offline operation |
| Appliance UI | new ui/appliances/, approval, grants, privacy/access screens | Reviewable owner activation; accessible controls; Stop latency target |
| Streaming | new streaming/, viewer client, signaling and auth | Authorized viewers only; encrypted sessions; stale/disconnect tests; 24h trial |
| Help pilot | new triggers/, alerts/, outbox; consented contact enrollment | Offline detection verified; duplicate-safe alerts; failure and human acknowledgment distinction |
| Release | Play flavor, AAB/signing flow, tests; launch-kit | Evidence matches advertised features; Console declarations supported by actual behavior |

Proposed filenames above are implementation sketches, not claims that files exist. Existing touchpoints: ApiServer.kt:75 request pipeline; ApiServer.kt:309 capability checks; Media.kt:93 capture; Media.kt:151 recording; BridgeService.kt:58 service types; MainActivity.kt:288 UI; Policy.kt:25 global switches; app/build.gradle.kts:17 SDK configuration.

## 11. Decisions for review

OPEN-01: Public launch scope: recommended foundation + workflow runtime first, streaming/help later; alternate wait for camera appliance. OPEN-02: Old-device minimum: proposed Android 10; alternate Android 12 first. OPEN-03: Distribution: recommended Play-safe build plus preserved personal build; alternate sideload-first only. OPEN-04: Hosting/alerts: private network first, no hosted relay/provider costs until selected. Name/domain, confirmed legal publisher and verified support mailbox are operational inputs. Pricing remains undecided; no billing code or subscription promises in this release kit.

## 12. Sources checked 28 October 2026

- Target API: https://support.google.com/googleplay/android-developer/answer/11926878?hl=en
- User data and privacy: https://support.google.com/googleplay/android-developer/answer/10144311?hl=en
- Data safety definitions and exceptions: https://support.google.com/googleplay/android-developer/answer/10787469?hl=en
- FGS declarations: https://support.google.com/googleplay/android-developer/answer/13392821?hl=en
- FGS background/while-in-use: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Boot restrictions: https://developer.android.com/about/versions/15/changes/foreground-service-types
- Photo permissions: https://support.google.com/googleplay/android-developer/answer/16935362?hl=en
- Accessibility: https://support.google.com/googleplay/android-developer/answer/10964491?hl=en
- Store assets: https://support.google.com/googleplay/android-developer/answer/9866151?hl=en
- Signing: https://support.google.com/googleplay/android-developer/answer/9842756
- Native page sizes: https://developer.android.com/guide/practices/page-sizes
- Reviewer access: https://support.google.com/googleplay/android-developer/answer/9859455?hl=en
- Personal-account testing: https://support.google.com/googleplay/android-developer/answer/14151465?hl=en

Policy interpretation is a release-planning input, not a promise of approval. Recheck current requirements at submission.

Brand clarification, 28 October 2026: BRIDGIT is deliberately Android-specific. The primary tagline appears beneath the app name. “Any Android device” is the product ambition; state the actual supported OS/hardware nearby. bridgetandroid.com is under consideration, not registered or selected by this document. Check Android trademark guidance before public branding: https://developer.android.com/distribute/marketing-tools/brand-guidelines

Naming update, 28 October 2026: The approved public name is BRIDGIT Device Manager for Android, short brand BRIDGIT, domain https://bridgit.cc. The tagline is unchanged. Historical filenames and Android package/API identifiers remain unchanged for compatibility.


## Agent branding clarification — 29 October 2026

User requirement: agents can supply their name, branded icon and wordmark through
the API. Display that identity at the top of interactions and repeat it on the
confirmation screen, so the owner sees which agent is asking before confirming.
Use the same identity on notices, questions, workflow proposals, permission and
action approvals, and their confirmation/result screens when those surfaces are
implemented. Keep the name visible if artwork is absent; preserve BRIDGIT
attribution, action details, destinations and owner controls. Branding is
self-supplied presentation and never grants permissions or verifies identity.
Bind the displayed identity to the authenticated agent ID, not an interaction's
arbitrary content. If a profile changes while approval is pending, keep the
reviewed identity consistent through confirmation, or require fresh review.

Current local implementation: authenticated `GET /v1/agent/profile` and
`POST /v1/agent/profile` with optional `name`, `icon_base64` and
`wordmark_base64`, as documented in the repository README. Agent list, pinned
capture header and existing key-rotation/removal dialogs use a shared identity
display. Capture notifications use the name and icon. Inbox, workflow and
per-action approval screens remain planned; this change does not implement those
interaction systems.


Default-logo clarification: agents featured on the home page already have
optimized logo assets. Use those by default in interaction headers and
confirmation screens, including existing interactions such as Grokbot Demo.
Custom agent-supplied artwork overrides the default. Unknown agents retain a
text/initial fallback. Refer to the home-page agent catalog rather than a fixed
count; the current source lists seven options.
