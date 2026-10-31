# Relay implementation and remaining acceptance

29 October 2026. Baseline: integrated agent-control commit `4219561`, including
production 1.6.0 functionality and private 1.6.40/code57 release. Work is isolated
in `/Users/otter/.codex/worktrees/bridgit-relay/bridgit`, branch
`codex/relay-end-to-end`. No other checkout or website changes were overwritten.

The implementation and exact wire/privacy/deployment contracts are in
[`relay/README.md`](../../relay/README.md). These decisions refine §4.4.1 and
resolve the relay design questions in §8.23a; they do not declare every
hardware or production acceptance check complete.

| Gate | Decision and code | Evidence/remaining acceptance |
|---|---|---|
| Outbound phone connection | Keystore-authenticated TLS WS, signed issuance and one-use challenges, heartbeat, jittered reconnect, independent Tailscale listener | Local Worker/SQLite/WS enrolment, reconnect, isolation tests; actual Pixel Doze measurement still blocked |
| Sealed construction | RFC9180 Auth HPKE; P-256/HKDF-SHA256/AES128-GCM, pinned peer keys, direction/device/agent/generation/request/deadline/sequence AAD | Real Bouncy Castle ↔ hpke-js interoperability, wrong-key/device/sequence/tamper rejection |
| Persistent replay | Phone synchronous sequence high-water before execution plus 24h request-ID tombstones; helper atomic durable sequence reservation | Store recreation and duplicate/lower sequence and duplicate request-ID tests |
| Enrolment | Owner Access JWT issuer/audience verification, five-minute one-use token, phone confirmation; Keystore signing identity separate from agent credentials | Worker token-reuse and handshake-replay tests; actual Access application/audience must be provisioned/verified before publishing |
| Connector authority | New relay-only normal agent, explicit v1 capability/route and v2 tool allowlists; direct-key acceptance rejects relay agents | Real v1 health handler and v2 scope-denial path tested without an HTTP listener; no localhost proxy |
| Approval and cancellation | Existing platform mutation pipeline; sensitive owner approvals, job-clipped deadlines, persistent relay operation association, retry mapping and cancellation; transport loss invalidates relay approvals | Source review fixes, unit scope tests; physical owner approval/cancel lifecycle needs actual-device validation |
| Storage/deletion | Connector content memory-only; sealed ciphertext pages in SQLite DO; same absolute deadline for content/results/abandoned jobs, immediate logical expiry, earliest alarm cleanup, explicit content_lost | Worker restart, expiry, deletion, large payload/result tests; provider physical erasure timing is not guaranteed |
| MCP/OAuth | Fixed 2025-11-25, agent/generation-bound sessions (8/agent), exact Origins, preregistration, S256 PKCE, exact redirect/client/resource and one-use code; OAuth tokens restricted to MCP and same-agent MCP-job polling/cancel | Real local runtime OAuth/form exchange, code reuse, audience/session/Origin checks; no actual hosted vendor connection claimed |
| Tool exclusions | Feedback/developer, workflow mutations, stream starts, peripheral writes, alert triggers, translation downloads excluded | Phone allowlist filter and shared dispatcher; API cannot widen owner grants |
| Live calls | Five-second live admission; authenticated creation time in sealed plaintext; v2 execution start/runtime deadlines; session request approval deadlines clipped; unknown outcomes never replayed | Source and unit checks; Pixel media/control behavior remains acceptance work |
| Lost device | Owner Access-protected server disable closes socket, erases jobs, refuses reconnect and new jobs | Worker test; production owner page absent until approved deployment |
| Limits/backpressure | 2MiB bodies, 8MiB result, 512KiB maximum frame data with one ACK in flight, 2 phone executors, 8 device/4 agent jobs, 200/agent/UTCday, 16MiB retained-result budget | Real request-size and large paged ciphertext/frame tests; load/battery measurement not claimed |

The original Grok review's Supabase mailbox concerns are superseded by this
Worker/DO implementation. The relay sees connector plaintext and can originate
calls within the owner's narrow allowlist, which is stated explicitly. Sealed
mode hides content, not routing metadata, identity/public keys, size or timing.

A focused read-only source review identified shared-socket failure propagation,
challenge disruption/capacity exhaustion, postponed cleanup, global session-cap
interference, result-memory teardown and MCP retry cancellation gaps. Fixes
isolate malformed/oversized jobs, require signed challenge issuance, preserve the
earliest alarm, limit only new sessions per agent, retain bounded results, and
map both fresh and idempotently returned operations to cancellation. This is not
a claim of a comprehensive independent audit.

## Automated validation

Both private and limited Android variants pass **192 unit tests each**, including
six relay tests, and both lint tasks pass without a baseline or new suppression.
The Worker suite uses real SQLite/WebSocket flows; its two grouped tests cover
HPKE/authenticated envelope behavior and the integration assertions described
above, including result-budget isolation. Allocator tests pass (9). The runtime
Node dependency audit reports no known advisories. Wrangler dry-run succeeds.
The default production validator refuses the intentionally unset origin/Access
values and hostname route; that is the expected safe result.

HPKE interoperability is exercised in both directions: JS creates the committed
synthetic fixture opened by the JVM test, and Node opens the JVM's generated
response with the pinned phone key. This is real library interoperability, not a
mock cipher.

## Release blockers and limits

- USB `57091FDCQ0081F` is not connected. Read-only network ADB verifies the main
  package remains 1.6.40/code57. No uninstall, clear, reset, pair removal or APK
  installation was performed. A network-only ADB route cannot safely support
  the specified Tailscale-disabled test. The user was asked to attach USB while
  independent validation continued.
- Actual-phone enrolment, relay requests with Tailscale off, Doze with and
  without battery exemption, process restart/reboot behavior, battery use and
  owner approval/cancellation remain unverified. A foreground socket is not a
  delivery guarantee. FCM wake-ups are implemented (31 Oct 2026) but need a
  Firebase project, the app config and the Worker secret before they can be tested.
- The relay targets the Bridgit Cloudflare account (`3d2c7c0c…`), which is
  empty as of 31 October 2026. The `bridgit.cc` zone is still in the original
  account (`9cd3a280…`), so `relay.bridgit.cc` needs the zone moved first, or the
  relay deployed where the zone is. The deploy bundle validates in dry-run, but the checked-in default has no
  public route and deliberately fails production configuration validation.
  Owner-only Access team/audience, final hostname and exact hosted client
  registrations need a concrete reviewed production configuration and owner
  publication authorization. No Worker, namespace, DNS, Access policy, APK,
  release or website was published. No paid plan was enabled.
- Wake words, recognizer/model/license selection, audio/video streaming and
  full-screen media UI are separate capability gates. Wake words are never
  consent. Relay adds no speech/transcript collection or cloud speech fallback.
- Website copy remains **Exploring** for public relay. That matches delivered
  public availability. Existing direct `/v1/guide` remains a direct API guide;
  the new relay setup supplies its distinct URL/credential/mode and MCP
  contract. No parallel website work was overwritten.

The signed private artifact is **1.6.59 / code 76**, package `net.finleg.bridget`,
SHA-256 `8ed31fd40fc57e639f63e9f994acc9d61ca6692b27332af6df8ab78c3294ac63`.
Its original certificate, manifest, clean source provenance and 16KiB native
alignment were verified; debug fixture components are absent. Source commit:
`d3d42120a40917879db501f9ebfb927125dfc454`. The allocator reserved code76 at
build time, carrying forward the shared Git-common-dir high-water; gaps were
not reused. `relay-release.json` records the artifact path and full evidence.
Public availability and installation remain false.

## Primary references checked

- [Cloudflare WebSockets](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
- [Cloudflare limits and SQLite row sizes](https://developers.cloudflare.com/durable-objects/platform/limits/)
- [MCP 2025-11-25 authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- [Android Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Bouncy Castle HPKE API](https://downloads.bouncycastle.org/java/docs/bcprov-jdk18on-javadoc/org/bouncycastle/crypto/hpke/HPKE.html)
- [hpke-js API](https://jsr.io/@hpke/core/doc/~/CipherSuite)
