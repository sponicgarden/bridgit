# Bridgit relay — local implementation, public acceptance pending

This is an outbound HTTPS/WebSocket Worker and one SQLite Durable Object per
phone. It is independent of the direct Tailscale listener. No public service is
claimed by this checkout. `wrangler.jsonc` intentionally uses an invalid public
origin and has no route. The example production hostname is a proposal, not a
live address. No paid plan, DNS record, Access application or production Worker
has been created by this change.

## Authority and enrolment

The account is `394b5de665bbfdea54cdd57be9094762`, the existing Sponic update-bucket
account. This does not authorize changes to the Sponic product.

The owner opens **More → Relay** on the phone. **Show phone enrolment keys**
exports only its stable device ID, Keystore P-256 signing public key and HPKE
public key. At `/d/{device}/admin/`, the owner signs in through Cloudflare Access
and pastes that JSON. The Worker validates Access's signed JWT against the
configured team issuer and application audience; a forwarded email header is
never authority. Registering produces a five-minute one-time token, redeemed
only after **Confirm this phone and enable relay**. Key substitution and token
reuse fail. A lost-device **Disable device** prevents new jobs and reconnects;
only the authenticated owner can enable that same identity again. The owner
must then confirm locally. Identity replacement is deliberately not exposed.

Challenge issuance itself requires a fresh signed phone proof. The socket
handshake signs a separate one-use server challenge, bound to the device ID.
The identity private key is non-exportable Android Keystore P-256. HPKE P-256
private material is encrypted with a separate non-exportable Keystore AES-GCM
key. Android backup is disabled. New installations must be enrolled anew.

**Add relay agent** creates a normal production agent with explicit v1
capability grants and a separate relay allowlist. Its unexposed direct key is
not usable on direct transports; relay agents are refused there. The displayed
`rly_` token is shown once and only its SHA-256 digest is stored. Neither that
token nor an OAuth token authenticates a phone. Tokens bind to device, agent,
mode and the existing agent-key generation; rotation or removal invalidates
them. Existing direct pairings are not migrated or replaced.

Connector mode explicitly trusts the relay to originate requests within that
agent's phone-approved allowlist. The owner sees this disclosure at creation,
in the agent row, and on the setup prompt. Every v2 call uses the existing
scope/approval/idempotency dispatcher. Every v1 call uses the real registered
handler's admission, grant, switch, rate-limit, revocation and audit pipeline,
with `via=relay:connector` or `relay:sealed`. There is no loopback HTTP proxy.

## Wire contracts

All paths below follow the canonical `https://HOST/d/DEVICE` base. JSON objects
are UTF-8. No redirect is followed by the phone or helper. Agent credentials go
only in `Authorization: Bearer TOKEN`.

- `POST /jobs?wait=0..55`: connector body
  `{deadline: epoch_ms, request: {method, path, query?: object, body?: object,
  idempotency_key?: string}}`; sealed body `{deadline, envelope}`. The absolute
  deadline must be in `(now, now + 30 minutes]`. The body is at most 2 MiB,
  including ciphertext encoding. A connected request may wait up to 55 s.
- `202 {job_id, status, poll_url, expires_at}` means accepted, not executed.
  `GET /jobs/ID` is agent/generation-bound. Pending returns 202. A connector
  completion is `{job_id,response:{status,content_type?,body?,body_base64?,
  headers?}}`; a sealed completion is `{job_id,envelope}`. Inspect the phone's
  status and operation outcome; dispatch is not proof of an external action.
- `DELETE /jobs/ID` cancels only that agent's job, removes its retained result,
  and signals the phone. v2 operations carry the relay job association; MCP
  retries also map the returned idempotent operation to the new job. Unknown
  outcomes are never automatically retried.
- Errors are `{error: code, job_id?: ID}`. Admission uses 400 for invalid JSON,
  deadline, mode or protocol; 401 for wrong/revoked credentials; 403 for device,
  audience or Origin refusal; 404 for an inaccessible job/session; 413 for body
  limits; 429 for quotas/backpressure. Offline connector submission returns
  503 `phone_unreachable`. Expired visible jobs return 504
  `phone_unreachable`; after cleanup they return 404. Lost connector content
  returns 410 `content_lost`. A failed job returns 409, including `invalid_job`,
  `agent_revoked`, `result_too_large`, `result_backpressure` or `cancelled`.
- `POST /challenge`: `{nonce: UUID, at: epoch_ms, signature: base64 P1363}`;
  phone signs UTF-8 `bridgit-relay-challenge-v1\nDEVICE\nNONCE\nAT`. Proofs
  expire after 60 s and are deduplicated for 120 s. Reply `{nonce,until}`.
  `GET /socket` upgrades with `X-Relay-Nonce`, `X-Relay-Signature`, signing
  `bridgit-relay-v1\nDEVICE\nNONCE\nUNTIL`. Server challenges expire in 60 s.
- The phone publishes `{type:"agents",agents:[{id,generation,mode,token_hash}]}`.
  Jobs are `{type:"job",id,seq,agent_id,generation,mode,device_id,deadline,
  created_at,payload}`. Phone result frames are `{type:"result",id,seq,data,
  final}`. `data` contains one part of a single JSON result. Maximum frame
  data is 512 KiB, maximum assembled result 8 MiB. The phone emits small chunks
  and waits for `{type:"ack",id,seq,error?}` before the next. No partial result
  is executed or released. Malformed agent jobs fail individually with
  `{type:"failure",id,error}`; they cannot tear down other agents' socket.
- Heartbeats are `{type:"ping"}` / `{type:"pong",at}` every ~30 s, with a
  75 s stale threshold. Reconnect uses jittered 1–60 s exponential backoff.
  The phone executes at most two jobs concurrently; excess jobs get bounded
  backpressure. Worker admission allows eight active jobs/device, four/agent,
  200 submissions/agent/UTC day, and 16 MiB aggregate retained result data.
  Phone requests additionally pass a 300/minute/agent gate and native limits.

## Sealed mode

`src/sealed.mjs` and Android `Sealed.kt` use RFC 9180 **Auth** HPKE with
DHKEM(P-256, HKDF-SHA256), HKDF-SHA256, AES-128-GCM (suite 0x0010/0x0001/0x0001).
Maintained implementations are `@hpke/core` 1.9.0 and Bouncy Castle 1.86.
Both peers pin each other's raw uncompressed 65-byte P-256 HPKE public keys.
The signing identity and HPKE keys have separate purposes.

An envelope is `{request_id,seq,deadline,enc,ct}` (base64 standard alphabet).
Info is UTF-8 `bridgit-relay-v1`. AAD is UTF-8, no trailing newline:
`bridgit-relay-v1\nDEVICE\nAGENT\nGENERATION\nREQUEST_ID\nDEADLINE\nSEQ\nDIRECTION`,
where direction is `request` or `response`. Request plaintext is
`{created_at,request}`. The relay cannot modify its live timestamp or authority.
Each envelope uses a fresh HPKE encapsulation. Response binding must match the
outstanding request exactly. Stream transport order is separately sequenced;
only the fully authenticated assembled envelope is exposed to the helper.

The phone commits a strictly increasing sequence high-water before execution,
per agent/key generation. Replayed sequences stay rejected across restarts.
Request IDs additionally have 24-hour tombstones (400/day maximum retained per
agent generation); original envelopes remain rejected by sequence afterward.
Use one serialized sender per identity. The helper uses an exclusive config
lock, fsynced sequence reservation and atomic rename; crashes consume a sequence.
Never delete/reset sequence state for a live agent. Lost state requires an
owner key rotation and new enrolment/pins for that agent.

From this directory, `node sealed-client.mjs init /absolute/agent-key.json`
creates mode-0600 key material and prints only its public key. Put that public
key in **Add relay agent**, save the phone's one-time setup as mode-0600 JSON,
and add `key_file` containing the absolute local key-file path. Then run
`node sealed-client.mjs call /absolute/config.json /absolute/request.json`.
The output is decrypted locally. Synthetic fixture keys under Android test
resources are test data and must never be used for enrolment.

## Connector HTTPS and MCP

Bearer HTTPS clients use the job contract above. MCP is Streamable HTTP with
JSON responses at `/mcp`, fixed protocol **2025-11-25**. There is no SSE push
or protocol negotiation fallback. `initialize`, `notifications/initialized`,
`ping`, `tools/list` and `tools/call` are implemented. Sessions are bound to
agent and key generation, expire in one hour, and are capped at eight/agent.
Every non-initialize request requires `Mcp-Session-Id` and
`MCP-Protocol-Version: 2025-11-25`. Discovery and tools execute on the phone,
filtered by the owner's allowlist. Long calls return a tool error containing
the authenticated job poll URL; OAuth job reads/deletes are restricted to MCP
jobs for that same device/agent/generation. A cancelled/expired approval is not
executed later.

OAuth uses preregistered clients only, owner Access authentication,
authorization code + mandatory S256 PKCE, exact redirect URI/client/resource
matching and one-use 60-second codes. Tokens expire in one hour; no refresh or
dynamic registration is offered. Configure `OAUTH_CLIENTS` as a JSON map
`{"CLIENT_ID":{"agent_id":"PHONE_AGENT_ID","redirect_uris":["https://EXACT/CALLBACK"]}}`.
The owner registration preauthorizes that hosted client for this named agent.
`resource` is exactly `BASE/mcp` on authorization and token exchange. Tokens
cannot be forwarded to direct phone endpoints or arbitrary job submission.
Metadata is linked by the 401 `WWW-Authenticate` header; RFC 8414 well-known
issuer-path discovery is also routed. The token endpoint accepts form encoding
and JSON. Arbitrary JWTs and unauthenticated localhost calls are not accepted.

Origin is rejected unless listed as an exact origin in `ALLOWED_ORIGINS`;
same-origin owner admin forms are allowed. This is a server-to-server hosted
client implementation, not a general browser CORS SDK. Actual ChatGPT,
Claude.ai and Muse account integrations have **not been verified**. Only clients
that support the pinned protocol and these preregistration/OAuth boundaries are
supported by the contract; no vendor-wide compatibility is claimed.

Developer and feedback tools, workflow creation/execution, media stream starts,
peripheral writes, alert triggers and translation downloads are excluded.
The generic HTTP/MCP wrapper cannot bypass the allowlist. Existing v1 capture
and controls are available only if explicitly selected by the owner and their
normal feature switches/grants permit them. Live calls must execute within five
seconds of request creation or fail. v2 sensitive operations retain phone review
and a deadline clipped to the relay job. Transport loss invalidates relay
approvals/control; process restart cancels pending relay approvals. No wake-word,
continuous audio/video, callback adapter or browser-player work is claimed.

## Storage and privacy

Cloudflare SQLite DO storage holds public enrolment keys, hashed credentials,
authority generations, job metadata/deadlines, UTC quota counters, sequence
high-waters, OAuth/session metadata and sealed ciphertext in 32K-character
pages. **Connector requests/results are never written to durable storage.**
They exist only in the active object's memory until the deadline, cancellation,
revocation or eviction. Eviction may lose the connector response and returns an
explicit error; it never silently re-executes work.

Sealed requests are erased when dispatched; sealed results remain pollable only
until the original deadline. Expired records are logically inaccessible at once
and deleted by an alarm scheduled no later than the earliest pending cleanup
(~30-second sweep; Cloudflare scheduling/retries can delay physical deletion).
Abandoned jobs have the same deadline and deletion path. Cancellation and
revocation erase ciphertext pages and memory content. Server disable erases
all job content. Provider backups/deletion mechanics remain Cloudflare's; this
is not a guarantee of immediate physical erasure from their infrastructure.
No request/result console logging, Worker observability or analytics is enabled.
Phone audit records contain transport/agent/outcome metadata, not relay bodies.
No transcript storage is added by relay. Existing phone retention and redaction
still apply to the underlying capability.

## Deployment and acceptance

`npm ci`, `npm test`, `npm run check` validate locally. The tests use a local
Miniflare Worker with real SQLite/WS; the owner-bypass fixture exists only in
`test/fixture.mjs` and is never the production entry point. The production
bundle has no fixture route. `production.example.jsonc` pins the correct
account, disables alternate hostnames and uses a separate namespace. Before a
production publish, choose/verify the hostname, set the existing owner-only
Access team/audience and client registrations, validate with
`node validate-config.mjs production.jsonc`, then run Wrangler's dry-run for
that exact config. The checked-in default intentionally fails production
validation. Production publishing is a separate owner-authorized step after
review/device acceptance, never part of a test run.

Doze must be measured on the actual Pixel with and without battery exemption,
including socket delay, lost pongs/reconnect and job delivery. A foreground
service does not guarantee network in Doze. Force-stop remains unreachable
until reopened. FCM is not implemented. USB installation and Tailscale-disabled
acceptance are blocked while the requested USB serial is absent. The network
ADB route alone is not a controlled substitute for that test.
