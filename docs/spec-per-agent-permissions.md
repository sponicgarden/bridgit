# Spec: per-agent permissions (Bridget 1.3)

> **Superseded by [bridget-spec-v2.md](bridget-spec-v2.md)** (27 Oct 2026). Kept for history; v2 is the source of truth.

Status: proposed, 26 Oct 2026. Not built.

## Why

Bridget now has four agents (Muse, Grok, Claude, Codex) sharing one set of
switches. Turning the camera on for one turns it on for all. Each agent should
get only what it needs. For example, Codex reads screenshots but never uses
the microphone.

## What changes for Rahul

- Tapping an agent in the **Agents** list opens its own screen with one switch
  per capability: **Camera**, **Microphone**, **Gallery**, plus later ones
  (**Notices**, **Questions**, **Speak aloud**, **Send files**, **Shared items**).
- The existing switches stay as **master switches**. A capability works for an
  agent only when the master switch **and** that agent's switch are both on.
  Turning a master switch off stops it for everyone immediately.
- **Gallery scope** per agent: *All images*, *Screenshots only*, or *Camera
  photos only*. This covers the Codex-reads-screenshots case without exposing
  WhatsApp images or photos.
- **Mic limit** per agent: maximum clip length, from 5 to 120 seconds.
- **Capture rate** per agent: 1-10 captures per minute (default 10).
- A new agent starts with **everything off** for itself. Rahul turns on what it
  needs while it is being added.
- The agent screen also shows that agent's recent requests (the audit log
  filtered to it), its last use, and the Rotate key / Remove buttons.

## What changes for agents (API)

- `GET /v1/capabilities` reports what **this** agent may do: each capability
  gets `"allowed"` (this agent) next to `"enabled"` (master), plus
  `gallery_scope` and `mic_max_seconds`.
- New error `agent_not_allowed` (403): the master switch is on, but this agent
  doesn't have that capability. The message names the switch Rahul would turn
  on. `capability_disabled` keeps meaning the master switch is off.
- Gallery calls outside the agent's scope return `photo_not_found`, so the
  agent can't learn that hidden images exist. `image/last?type=any` returns
  the newest image *within scope*.
- Mic requests above the agent's limit return `invalid_parameter` stating the
  limit.

## Storage and audit

- Each agent record gains `caps` (camera, mic, gallery, notices, questions,
  speak, files, shares), `gallery_scope`, `mic_max_seconds` and `captures_per_minute`.
- Every permission change is audit-logged as `app:permissions` with the agent
  and the before/after values.

## Migration

When 1.3 installs over 1.2, the four existing agents keep exactly what they
can do today: every capability whose master switch is on is allowed, scope is
*All images*, the mic limit is 120 s, and the rate is 10 per minute. Rahul then
narrows them down.

## Acceptance tests

1. Camera on for Muse, off for Grok: Muse's snapshot succeeds; Grok's
   returns `agent_not_allowed` and the camera never opens.
2. Codex with *Screenshots only*: `image/last?type=any` returns the newest
   screenshot even when a newer WhatsApp image exists; fetching that WhatsApp
   image by ID returns `photo_not_found`.
3. Master camera off: both Muse and Grok get `capability_disabled`.
4. Changing a permission appears in the audit log with before/after values.
5. Upgrading from 1.2 keeps all four agents working with unchanged behaviour.

## Open questions

1. Should a new agent start with nothing (proposed), or copy another agent?
2. Do any agents need a time window, for example no camera between 23:00
   and 07:00?
