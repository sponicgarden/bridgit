# Bridgit Android agentic control audit

> Implementation update: the integrated build now includes the production 1.6.0 code from `de26bf8` and the additive control platform. This audit describes its earlier checkout snapshot. See [current implementation and acceptance](../implementation/agentic-control.md) for the corrected baseline and delivered scope.

29 October 2026. Audit of the local working tree on `codex/agent-appliance-launch-kit`, starting at HEAD `5546eba`, including pre-existing uncommitted code. Feature proposals below are not implementation approvals. Existing application, website and specification changes were preserved.

Bridgit already gives an agent useful physical context. To support a wide range of assisted tasks, its next major addition should be an **observe → authorize → act → verify** contract. A large collection of device endpoints alone will not make cross-app work reliable. The control contract, typed integrations and the proposed appliance runtime should share identity, grants, execution records and cancellation.

## What exists and what remains proposed

| Area | Implementation evidence | Audit conclusion |
|---|---|---|
| Agent identity | `Policy.kt:26`, `ApiServer.kt:53`, `AgentBranding.kt` | Named independent credentials, rotation/removal, self-service names and artwork exist locally. Artwork is presentation, not verified identity. |
| Physical sensing | `ApiServer.kt:61`, `Media.kt:93` | Camera snapshots and 1–120 second audio recordings exist. |
| Images | `ApiServer.kt:63`, `ApiServer.kt:269` | Gallery listing/fetch and latest saved image exist. A saved screenshot is not a live screen capture. `since`/`wait` behavior is not implemented in `lastImage`. |
| Health and audit | `ApiServer.kt:59`, `ApiServer.kt:304`, `AuditLog.kt:13` | Battery/charging/listener status and a bounded file log exist. Audit reads are not filtered to the caller. |
| Authority | `Policy.kt:25`, `ApiServer.kt:323` | Only three global capability switches: camera, microphone, gallery. Distinct keys do not provide distinct capability grants. |
| Android services | `AndroidManifest.xml:40`, `BridgeService.kt:40` | One foreground listener service. No Accessibility service, notification listener, MediaProjection service, share receiver or device-policy controller is declared. |
| Agent protocol | `ApiServer.kt:50`, `tools/bridgit` | REST API and a Python HTTP/Tailscale helper. No MCP server or live OpenAPI/guide route. |
| Wider platform | v2 specification and v3 appliance proposal | Questions, files, speech, events, location, screen capture, workflows, streaming and help-station behavior remain proposals in this checkout. |

The inspected application is documented as version 1.2.0. The current minimum is Android 14 (`minSdk=34`), with compile/target SDK 35 (`app/build.gradle.kts`, `android` configuration). Supporting older spare phones requires implementation and device testing; it is not already delivered by the appliance proposal. Build-version handling changed concurrently during this audit; the SDK values and endpoint inventory were rechecked.

The website catalog currently labels **24 entries as current**, including unimplemented live screenshots, questions, interactive cards, location, events, MCP, discovery and Quick Settings pause. `website/content/features.json:22`, `:95`, `:167`, `:184`, `:314`, `:330`, `:363` are examples. Pairing exists, but its proposed generated setup prompt is also not the current flow. Reconcile these labels before using the website as the capability inventory. Add evidence fields to the catalog: implementation, build, tested devices and verification date. This audit does not edit those concurrent changes.

Before expanding control, close several current contract gaps. `ApiServer.require()` does not enforce effective notification/channel visibility, although v3 REQ-22 requires it. Camera capture checks `isDeviceLocked`, while microphone recording has no equivalent explicit check; choose and test the intended lock policy. `withSensor()` has one mutex shared by camera and microphone but no operation-level cancel/lease contract. The request pipeline copies non-profile POST values into audit parameters, which must change before introducing message bodies or typed text. These are implementation findings; they do not establish that a particular runtime failure was reproduced.

## Android feasibility and product modes

| Mode | Useful capabilities | Boundary |
|---|---|---|
| Ordinary app | Owner-approved sensors, selected files, share targets, agent questions, speech, permitted intents and provider integrations | Permissions, foreground/background rules and app-specific interfaces still apply. Play approval is separate from technical feasibility. |
| User-approved screen sharing | Live visual context through MediaProjection | Capture grants do not grant input control. Lock, system Stop and process death can end projection; report invalidated sessions. [Android MediaProjection](https://developer.android.com/media/grow/media-projection) |
| Private sideload control | Explicitly enabled Accessibility service for supported UI nodes, semantic actions, gestures and global navigation | New scope beyond v2's screenshot-only service. Missing nodes, custom surfaces, sensitive content and OEM behavior require fallback or a visible block. Sideloading does not remove Android restrictions. [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) |
| Developer companion | Paired ADB/UI Automator adapter for app tests, diagnostics and controlled device operations; optionally evaluate Shizuku | Separate setup, privilege boundary and lifetime. Non-root Shizuku startup must be repeated after reboot. Never expose a generic remote shell as the default agent tool. [UI Automator](https://developer.android.com/training/testing/other-components/ui-automator), [Shizuku manual](https://shizuku.rikka.app/guide/setup/) |
| Provisioned appliance | Dedicated-device policies, allowlisted kiosk apps and managed configuration | Requires appropriate device-policy provisioning. Device owner is not arbitrary access to every app's data or a lock bypass. [Lock task mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode) |
| Root/system privileges | Possible specialist experimentation | Exclude from the general product contract and first control milestone. Do not make ordinary users depend on it. |

Google Play explicitly prohibits Accessibility automation that enables autonomous initiation, planning and execution, with an exception for qualifying accessibility tools whose core purpose serves people with disabilities. An ordinary general assistant does not qualify merely because some users benefit. A remote planner should not be assumed to create an exemption. Keep general autonomous Accessibility control out of the proposed Play product; review that product around supported APIs, explicit user interaction and applicable policies. [Google Play Accessibility policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)

AppFunctions is a promising adapter, but currently experimental, Android 16+, dependent on app-provided functions and caller permission. It is not a universal cross-app API. Prove that Bridgit can obtain the required caller access before depending on it. [Android AppFunctions](https://developer.android.com/ai/appfunctions)

Background app launches also depend on Android's activity-launch rules. Prefer a visible owner interaction when no supported launch path is available; do not turn the older spec's blanket statement into a universal technical impossibility. [Activity security](https://developer.android.com/guide/components/activities/secure-bal)

## Prioritized additions

Priority is dependency order, not a release estimate. S means a bounded addition; M means a new subsystem; L means several interacting subsystems and substantial device validation. Effort is relative, not a calendar commitment.

| Priority | Feature | Scenarios unlocked | Effort and dependency | Existing anchor / addition |
|---|---|---|---|---|
| P0 | Capability and readiness registry | Agents discover what is actually possible on this device, OS and build, and get an exact recovery action | M; first | Extend `capabilities()` with supported/enabled/granted/ready, backend, limits, blockers, feature versions and build identity. v3 REQ-16/27. |
| P0 | Scoped, expiring authority | A research agent can read a selected image without controlling messaging; temporary support access expires | M; before new sensitive actions | Replace global-only authorization with master AND agent AND session/resource grants; owner-only escalation. v3 REQ-21. |
| P0 | Owner-started control sessions | One agent helps in selected apps for a bounded task while the owner can pause or take over | M; grants | New session scope: device, display, apps, operations, duration, budgets, visible identity and Stop. One UI controller per display. |
| P0 | Durable operations and results | Timeouts/reconnects do not cause duplicate sends or purchases; stop/revoke affects queued work | L; shared foundation | Reuse v3 execution/idempotency/cancellation design; add `unknown_outcome` and reconciliation before retrying external effects. |
| P1 | Fresh screen observation | Explain the current screen, diagnose an error, read forms, translate a menu | M; capture backend and consent | Timestamped screenshot plus optional compact UI tree, app/window/display/rotation, keyboard and secure-content state. New observation contract; CAP-25/26 only cover part. |
| P1 | Semantic element targeting | Reliably locate a field or button across screen sizes and text scaling | M; observation | Snapshot-bound element refs, role/label/state/actions, bounded text search and ambiguity refusal. Coordinate fallback must use the same display transform. |
| P1 | Bounded UI actions | Tap, long-press, scroll, type/replace text, back/home and keyboard handling | L; sessions, refs, adapter | New control scope beyond CAP-26. Validate target/app/state immediately before dispatch; record actual backend result. |
| P1 | Wait, assertions and verification | Confirm a form changed or an app reached the expected page; recover from slow loading | M; observations/actions | `wait_for`, bounded scrolling/search, postconditions and fresh evidence. Dispatch accepted is not task completed. |
| P1 | Human intervention | Owner answers a question, enters a credential or approves a reviewed final action | M; durable pending operations | Implement v2 CAP-31/32 and repeat authenticated agent identity. Bind approval to exact action, recipient, content and expiry. |
| P1 | Typed app and intent adapters | Maps, dialer drafts, calendar drafts, document opening and supported provider actions | M; per adapter | Prefer supported semantic integration before UI automation. Resolve handlers safely; report launch, draft and completion separately. CAP-41/43. |
| P1 | Standard agent tools | Codex, Claude and other compatible agents use the same discoverable phone tools | M; registry and authorization | OpenAPI plus MCP/tool adapters backed by the same dispatcher, validation and grants. Never create a second privilege path. Existing competitor review item 1. |
| P2 | Notification actions and reply | Triage a chosen messaging app and draft or reply to a specific live conversation | M; notification access, scoped approvals | App allowlists, notification/action identity, expiry and exact preview. Inline reply only works where the app supplies RemoteInput; not arbitrary conversation access. [RemoteInput](https://developer.android.com/reference/android/app/RemoteInput) |
| P2 | Share, files and document workflows | Share a receipt to an agent, annotate a document, return a file to the phone | M; storage grants and inbox | Android Sharesheet target, selected-document access, URI permissions, bounded binary transfers and recipient-scoped storage. v2 CAP-27–30. |
| P2 | Voice, reading and translation | Hands-free questions, dictation into a reviewed field, read-aloud and translated visual context | M; interactions, audio and UI scope | TTS, local OCR, supported transcription/languages, cancellation and explicit recording state. v2 CAP-36 and v3 microphone scopes. |
| P2 | Productivity and communications | Appointments, contact selection, reminders, alarms, meeting notes and message drafts | M per adapter; explicit product scope | Reopen CAP-43/Q1 deliberately. Separate lookup/draft/send and preserve recipient identity; do not assume unrestricted SMS/call-log access. |
| P2 | Local triggers and workflow runtime | Timers, charging routines, an information panel or a repeatable inspection station keep working without an agent | L; durable operations and approved scopes | Adopt v3 REQ-15–19: validated bounded DSL, revision approval, local state/outbox and template UI. Triggers cannot independently expand authority. |
| P2 | Diagnostics and recovery | Explain missing permissions, connection failures, stale views, low storage or a stopped service | M; registry/events | Exact setting links plus reason and next step; bounded diagnostics export. Handle lock, Doze, process death, force stop and network changes honestly. |
| P3 | Appliance media and monitoring | Camera station, consensual household monitoring, help button with acknowledgment | L; local runtime, viewers and delivery semantics | Keep v3 streaming/help plans; separate viewer from controller. Display freshness/offline state; no guarantee of emergency response. |
| P3 | Multi-device coordination | Route a task to the tablet, use a spare phone's camera, test multiple Android configurations | M; stable device identity | Device-scoped grants/leases/events and explicit routing. Never silently move a sensitive task to another device. |
| P3 | Specialist peripherals and contexts | Barcode inventory, BLE instrument readings, USB accessories, NFC actions, geofence routines, media controls | M–L per adapter; hardware and OS probe | Capability plugins implemented in signed app code, with narrowly selected devices/data. Prioritize only after representative demand and compatibility tests. |

Supporting privacy behavior belongs in each subsystem: app exclusions, no password-field export, selective capture, content redaction where feasible, bounded retention and safe diagnostics. Redaction must report its limits; it cannot promise to identify every secret. Screen/notification/document content remains untrusted task data and cannot grant new authority.

## Coverage across assisted scenarios

| Scenario | Minimum useful addition | Important completion boundary |
|---|---|---|
| Explain a confusing app screen | Live capture, OCR/vision, questions | Capture freshness and blocked secure content are visible. |
| Help configure an app | UI tree/actions, settings links, verification | Permission/security dialogs stay owner-controlled. |
| Fill an address or repetitive form | Field targeting, text input, validation | Review sensitive submission; do not retain entered secrets. |
| Reply to a message | Typed provider integration or live notification action; UI fallback in private mode | Correct app, conversation and exact reviewed text; no assumed delivery from dispatch. |
| Schedule a meeting | Calendar adapter or owner-confirmed calendar draft | Correct timezone, calendar and invitees; duplicate-safe result. |
| Compare products and prepare checkout | Screen navigation, extraction, cross-app context | Stop at a reviewed commitment boundary; reconcile uncertain outcomes. |
| Plan a trip or use a boarding pass | Maps intents, selected documents, notifications | Distinguish information lookup, reservation and payment. |
| Translate a menu or sign | Camera, OCR/vision, speech | Language/model availability and capture consent are explicit. |
| Assist a user with motor or vision impairments | Accessible UI, speech, semantic control and tested assistive workflows | Research actual needs and coexistence with TalkBack/switch access; qualification is not a marketing label. |
| Process receipts and invoices | Share target, selected files, extraction, return files | User chooses sources and destinations; evidence remains traceable. |
| Meeting or voice notebook | Audio, transcription, questions, export | Recording is visible and stoppable; consent context is respected. |
| Find or quiet a phone | Ring, status and temporary sound/DND controls | Restore prior settings where supported and report restrictions. |
| Troubleshoot an Android device | Readiness diagnostics, screenshots, supported settings links | Explain the precise owner step; no claimed privilege escalation. |
| Reproduce an app bug | Companion test adapter, observation/assertions, bounded evidence | Use isolated fixtures; replay excludes credentials and irreversible effects. |
| Run a kiosk or household panel | Native templates, approved local workflow, optional managed provisioning | Stop and real operating state remain visible. |
| Use a camera/help appliance | Streaming or local trigger plus reliable event delivery | Stale media and failed delivery differ from live/acknowledged. |
| Inventory a workshop | Barcode/photo capture, selected records and optional peripheral adapter | Hardware support is discovered, not inferred from Android alone. |
| Coordinate several phones/tablets | Device routing and separate leases | Task destination and recipient authority never silently change. |

## Shared execution contract

Suggested additive paths are illustrative, not existing APIs: `/v2/control/sessions`, `/v2/observations`, `/v2/actions`, `/v2/operations/{id}`. Keep the v3 workflow/execution proposal compatible rather than introducing a competing job system.

1. **Discover.** Resolve the device and backend; inspect actual readiness, permissions, app scope and resource conflicts. A key cannot enable its own capability.
2. **Authorize.** Bind owner approval to agent ID, device, session, app scope, permitted actions and duration. New scopes remain denied during migration. A control lease carries a generation/fencing token so a replaced controller cannot keep acting.
3. **Observe.** Return observation ID, monotonic/UTC time, boot/session generation, package/window/display and coordinate transform. UI tree and pixels are not assumed atomic: detect changes between captures and retry or mark inconsistent. Never persist actionable node refs across reboot.
4. **Validate.** An action names the observation/ref, expected app/state and a bounded timeout. Recheck authorization and target immediately before the effect. Refuse stale or ambiguous targets instead of guessing.
5. **Act.** Prefer typed adapters, then semantic UI actions, then explicitly allowed visual-coordinate fallback. Keep arbitrary shell, unbounded loops and remotely supplied executable code outside the runtime. Start with one action per observation; add bounded sequences only after checks work.
6. **Verify.** Separate accepted, dispatched, observed-result, failed, cancelled, blocked and unknown-outcome. Gesture completion proves input dispatch, not that a purchase, message or appointment succeeded. Use an app/provider receipt or an explicit postcondition as evidence.
7. **Recover.** Persist operation identity and safe checkpoints. On disconnect, expire/pause UI control rather than replay old taps. Read current state before retrying uncertain external effects. Idempotency in Bridgit cannot provide exactly-once behavior inside an unrelated app.
8. **Yield and stop.** Owner Stop/revoke invalidates queued actions and releases resources; target the v3 two-second bound and measure it. Define takeover behavior and test detection of user interaction across supported input methods. Physical owner control must remain usable.

Events need durable cursors, gap markers, bounded retention and caller scoping. Audit evidence should connect session → approval → observation → action → verification, with raw sensitive payloads excluded by default. Use one dispatcher for REST, MCP, local workflows and any developer adapter.

## Recommended implementation sequence

**First, establish truthful discovery, consent and scoped authority.** Implement the registry, grant migration, capture visibility enforcement, cancellable operations, a minimal question/answer flow, scoped durable events and corrected release capability inventory. These serve both the public bridge and private control mode. Current `/audit` visibility and broad global-only switches need explicit migration decisions. Verify question expiry, revocation, idempotent creation, answer delivery without webhooks, event gaps after restart and absence of private text in audit exports.

**Then prove one private cross-app assistance session.** In a clearly private, owner-enabled mode, allow a named agent to inspect a supported app, locate fields, fill a reversible sample form, scroll, and verify the result. Include one owner question and one explicit handback. Use an isolated fixture app and a supported system-app journey; do not test by sending real messages or making purchases. Compare an Accessibility adapter with the existing developer-companion options before choosing the first production backend.

Acceptance cases for that slice:

- Fresh observation and successful verified form entry on the actual tested phone; record latency rather than asserting a target passed.
- A changed window, duplicate button label, rotation, open keyboard or stale node produces resnapshot/refusal rather than a wrong tap.
- The second agent cannot interleave UI control; an expired lease cannot dispatch.
- Wrong device/app, expired scope, key revocation and owner Stop block queued and in-flight follow-up actions.
- Lock, secure content, permission loss and service shutdown return actionable blocked state without fallback around the restriction.
- Network loss after dispatch produces an uncertain result that is reconciled before retry; reconnect never replays cached taps.
- Owner takeover, TalkBack coexistence, text scaling and non-English labels are tested rather than assumed.
- The activity record proves which agent acted, which reviewed authority applied and how completion was verified, without leaking entered content.

**After that, add breadth through typed integrations.** Start with share/files, maps/calendar drafts and a selected messaging action. Add local workflows and appliances on the same operation model. Leave large on-device models, a general autonomous planner, root dependence and public relay infrastructure out of the first control slice.

## Product decisions this audit exposes

- Whether general cross-app control is a supported private mode. v2 CAP-26 deliberately prohibits node reading and gestures; adding them changes that decision.
- Whether to adopt v3 scoped grants now, superseding v2 D11's deferral.
- Whether communications/productivity move into scope; CAP-43/Q1 currently leave them unscheduled.
- Which distribution modes to support and which capabilities each build declares. A mode toggle is not evidence that a Play-distributed general control feature complies.
- Which Android versions/OEMs are supported. The old-device ambition and present Android 14 minimum differ materially.

## Review and verification record

This is a source and feasibility audit, not a device certification or penetration test. Existing test coverage inspected here concentrates on agent profiles/branding (`app/src/test/java/net/finleg/bridget/AgentProfileTest.kt`); it does not establish cross-app control behavior. No device operations, runtime captures, application changes, builds, publication or Git mutation were performed for the audit. Prior competitor research was used as a lead list, not re-certified as current fact.

The independent review is saved as [Opus 5.5 agentic control review](2026-10-29-opus-5.5-agentic-control-review.md). It was requested with the explicit model ID `claude-opus-5-5` and read-only file tools; the CLI reported success with no permission denials. Its model-usage metadata also includes Claude's configured `claude-fable-5-1` advisor. The requested Opus model was not silently substituted.

### Agreements and resolved differences

- **Agreement:** the current code is a sensor/image bridge, not cross-app control; website status exceeds implementation; per-agent authority and cancellation must precede additional sensitive operations.
- **Sequence adopted from Opus:** implement the consent/event foundation before the private observation/action pilot. This gives both product modes a useful first release and avoids building UI actions before there is an owner response channel.
- **Retained from this audit:** favor typed integrations before UI fallback, use display-bound leases and fencing, detect inconsistent observations, represent uncertain outcomes, and prohibit blind replay after reconnect. UI action idempotency cannot prove exactly-once behavior in another app.
- **Narrowed advisor claims:** owner approval is not verified task completion; dispatch may be reported as successful dispatch but never as task success. App-launch restrictions have exceptions. Root does not mean every operation is possible. Shizuku startup and pairing are different lifecycles. Automatic takeover detection must be tested rather than promised. Hash-chained local logs alone do not establish tamper resistance against an attacker able to rewrite the chain; prioritize scoped, redacted execution evidence first.
- **Estimates:** Opus supplied calendar ranges; this combined audit uses relative effort because staffing, supported devices and product mode are not yet fixed.

Only the two new review documents belong to this audit. No pre-existing implementation or website changes were edited, committed, pushed or deployed.
