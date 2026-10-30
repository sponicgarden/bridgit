# Spec: screenshot on request, with time-limited approval (Bridgit 1.5)

> **Superseded by [bridgit-spec-v2.md](bridgit-spec-v2.md)** (27 Oct 2026). Kept for history; v2 is the source of truth.

Status: proposed, 26 Oct 2026. Not built. Feature idea #8.

## What the owner gets

When an agent wants to see the screen, the phone asks:

> **Grok wants to see your screen**
> [Allow once] [Allow for 1 hour] [Allow for 16 hours] [Deny]

- **Allow once:** one screenshot, then access ends.
- **Allow for 1 hour / 16 hours:** Grok can take screenshots whenever it asks
  until the time runs out, with no further taps.
- **Deny:** nothing is captured, and Grok is told no.

While access is on, Android's screen-sharing icon stays in the status bar and
Bridgit's notification reads "**Screen visible to Grok until 21:14**" with a
**Stop now** button. Every screenshot is audit-logged.

## Android rules this design follows

- Android won't let an app keep permission to see the screen. Each approval
  goes through Android's own dialog ("Start sharing with Bridgit?"), and the owner
  must pick **Entire screen** there, not a single app.
- "Allow for 1 hour" therefore means: the owner approves once, and Bridgit keeps
  that screen-capture session open for an hour, taking a frame each time an
  agent asks. When the time is up, Bridgit ends the session. Android ends it
  too if the owner taps the status-bar icon, and may end it when the phone locks
  (to be checked on the Pixel). After that, the next request asks again.
- Apps that block screenshots (banking apps, password managers, anything using
  Android's secure flag) come out black. That's expected and is never worked
  around.
- The capture session runs inside Bridgit's foreground service with the
  `mediaProjection` type added. Keeping a session open costs some battery,
  which is why the longest option is 16 hours, not "always".

## Flow

1. Agent: `POST /v1/screen/capture` (optional `max_edge_px`, `quality`,
   `raw=1`, same sizing as `image/last`: 1568 px long edge, JPEG q85).
2. If this agent has an active approval: Bridgit grabs the current frame and
   returns it right away (target under 1 s).
3. If not: Bridgit shows an urgent **question** interaction (see
   `spec-agent-inbox.md`) with the four buttons. The agent's request waits up
   to 55 s:
   - **Allow…** opens Android's screen-share dialog. Once the owner confirms, the
     screenshot is taken and returned to the waiting request.
   - **Deny** returns `screen_denied` (403).
   - No answer within 55 s returns `consent_pending` (202) with a `grant_id`.
     The agent can wait on `GET /v1/screen/grants/{grant_id}?wait=55` and
     then call capture again.
4. When the approval ends, the next capture asks again.

```
POST /v1/screen/capture?raw=1
-> 200 image/jpeg   X-Bridget-Grant: hour   X-Bridget-Grant-Ends: 2026-10-26T21:14:00+01:00

GET /v1/screen/grant
-> { "active": true, "kind": "once" | "1h" | "16h", "agent": "Grok", "ends_at": "..." }
```

## Rules

- **Per agent:** an approval covers only the agent that asked. If Claude asks
  during Grok's hour, the owner is asked about Claude. The prompt offers
  **Allow for all agents** as a checkbox.
- **Rate:** at most 1 screenshot every 5 seconds per agent while an approval
  is active, and 300 per approval. Further calls get `rate_limited`.
- **Phone locked:** no capture while the phone is locked (`device_locked`).
  The approval clock keeps running.
- **Stop now** in the notification, removing the agent, rotating its key, or
  switching off the master **Screenshot** switch ends the approval
  immediately.
- Requires the per-agent **Screenshot** permission (see
  `spec-per-agent-permissions.md`). Off by default.
- Frames are never stored. Each one is encoded, returned and discarded.

## Errors

`consent_pending` 202, `screen_denied` 403, `agent_not_allowed` 403,
`capability_disabled` 403, `device_locked` 409, `rate_limited` 429,
`service_start_blocked` 503 (Android refused the capture session; open the
app once).

## Acceptance tests

1. Grok asks with no approval; the owner taps **Allow once** and confirms
   Android's dialog; Grok's waiting request returns a readable screenshot; a
   second call asks again.
2. **Allow for 1 hour:** ten captures over five minutes need no taps, each
   under 1 s; the status-bar icon and "visible until" notification stay up;
   at the end of the hour the session closes and the icon disappears.
3. **Stop now** ends access mid-hour; the next call asks again.
4. During Grok's hour, Claude's request triggers its own prompt.
5. A banking app on screen comes back black, with no error.
6. Locking the phone: captures return `device_locked`; record whether
   Android also ends the session on this Pixel.

## Open questions

1. Are 1 hour and 16 hours the right lengths, or should 16 hours be "until
   midnight"?
2. Should **Allow for all agents** exist at all, or always be per agent?
