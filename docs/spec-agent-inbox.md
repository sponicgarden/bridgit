# Spec: Ask Rahul, send to phone, and the Inbox screen (Bridget 1.4)

Status: proposed, 26 Oct 2026. Not built. Covers feature ideas #2 (agents ask
Rahul a question), #3 (Rahul shares things to an agent) and #4 (agents send
files to the phone), all of which appear in one new **Inbox** screen in the
app.

## 1. How agents get answers without a webhook

Many agents can't receive incoming calls. Grok has a webhook, and some Claude
setups may, but Codex and Claude Code sessions usually don't. So every
"something for the agent" item can be fetched **two ways**:

- **Wait-and-ask (works for every agent):** the agent sends a request such as
  `GET /v1/prompts/{id}?wait=55`. Bridget holds the request open until there
  is an answer or 55 seconds pass, then the agent asks again. This is called
  *long polling*. The answer arrives within a moment of Rahul tapping, with no
  webhook, no open port and no server on the agent's side. The agent only
  needs something running that keeps asking: a loop script, a cron job, or a
  Claude Code background monitor.
- **Callback (optional, for agents with a webhook):** if the agent registered
  a callback URL, Bridget also POSTs the result there right away. Rahul
  approves each agent's callback URL on the phone. By default, only addresses
  on the tailnet (`100.64.0.0/10` or `*.ts.net`) can be approved, so Bridget
  never talks to the public internet unless Rahul adds a public URL himself.
  Every callback is signed (`X-Bridget-Signature`: HMAC-SHA256 of the body
  with a per-agent callback secret shown once at registration), so the
  receiver can check it came from Bridget.

This changes spec v1.0 section 5.6 ("no network calls other than answering"):
outbound calls happen only to callback URLs Rahul approved on the phone.

## 2. Ask Rahul (prompts)

An agent sends a small card to the phone. Rahul answers with a tap.

### Fast template (build first)

```
POST /v1/prompts
{
  "title": "Pay this invoice?",              // required, max 80 chars
  "text": "Hostinger, $11.99, due Oct 30.",   // max 2,000 chars, plain text
  "image": { "gallery_id": "87567" } | { "data_base64": "...", "mime": "image/jpeg" },  // optional, max 2 MB
  "controls": [                               // 1-6 controls
    { "type": "button", "id": "yes", "label": "Pay", "style": "primary" },
    { "type": "button", "id": "no",  "label": "Not now" },
    { "type": "text",   "id": "note", "label": "Reply", "placeholder": "Anything to add?" },
    { "type": "choice", "id": "when", "label": "When", "options": ["Today", "This week"] }
  ],
  "priority": "normal" | "urgent",            // urgent = heads-up notification with sound
  "expires_in_seconds": 3600,                 // 60 s to 7 days, default 1 hour
  "callback": true                            // also POST the answer to this agent's approved callback URL
}
-> 201 { "prompt_id": "p_8f2c", "status": "pending", "expires_at": "..." }
```

- **On the phone:** a notification "**Grok asks:** Pay this invoice?". The
  first three buttons appear right in the notification (Android shows at most
  three). A text control adds an inline reply field. Tapping the notification
  opens the full card in the Inbox with the image and every control.
- **Lock screen:** shows only "Grok has a question" until the phone is
  unlocked (setting: *Show question text on lock screen*, default off).
- **Answer:**

```
GET /v1/prompts/p_8f2c?wait=55
-> { "status": "answered" | "pending" | "expired" | "dismissed" | "cancelled",
     "answer": { "button": "yes", "values": { "note": "use the Amex", "when": "Today" } },
     "answered_at": "..." }
```

- The agent can cancel a question that's no longer needed:
  `DELETE /v1/prompts/{id}`. The notification disappears.
- Rahul can **Dismiss** without answering (status `dismissed`).
- Limits: 5 open prompts per agent. Further ones get `too_many_open` (429).

### HTML mode (phase B)

For agents that want a custom layout, `"html": "<...>"` replaces
`text`/`controls`. It renders in a locked-down web view:

- No network at all. Only the HTML itself and inline `data:` images; 256 KB cap.
- JavaScript runs only to call `Bridget.respond({...})`, which submits the
  answer. Any JSON is allowed, up to 16 KB, and it comes back as `answer.data`.
- No cookies, no storage, no file or camera access from the page.
- The card header is always drawn by Bridget, not the page ("From Grok -
  custom card"), so a page can't pretend to be another agent or the system.

The fast template covers most cases (yes/no, pick one, short reply) and opens
instantly. HTML mode is for things like a form with many fields or a
side-by-side comparison.

## 3. Send to phone (agent to Rahul)

```
POST /v1/files
{ "name": "Hostinger invoice Oct.pdf", "mime": "application/pdf",
  "data_base64": "...", "note": "For your records", "save_to_downloads": false }
-> 201 { "file_id": "f_31aa" }
```

- Max 25 MB per file. PDF, images, text, CSV, audio, video, ZIP. APKs and
  other executables are refused.
- Files land in the Inbox, kept in Bridget's private storage. Rahul can open,
  share or **Save to Downloads** from there. With `save_to_downloads: true`,
  Bridget saves a copy to `Downloads/Bridget/<agent>/` right away.
- Notification: "**Codex sent** Hostinger invoice Oct.pdf".
- The agent can check whether Rahul opened it: `GET /v1/files/{id}` returns
  `{opened_at, saved_at}`.

## 4. Share to an agent (Rahul to agent)

- Bridget appears in Android's **Share** menu. Android also offers one
  direct-share shortcut per agent ("Grok", "Claude", ...), so sharing a photo,
  PDF, link or text to an agent is two taps. Sharing to plain "Bridget" asks
  which agent.
- Each share becomes an item in that agent's **outbox**, with an optional
  note Rahul types ("what is this rash?").
- The agent collects it the same two ways as prompts:
  `GET /v1/shares?wait=55` (long poll) returns the next items, and
  `GET /v1/shares/{id}` returns the content (images resized like
  `image/last`). Agents with an approved callback also get a POST.
- After an agent collects an item, the Inbox shows it as **Picked up by
  Grok** with the time. Uncollected items expire after 7 days.

## 5. The Inbox screen

A new tab at the top of the app: **Inbox** | Settings. The current screen
becomes Settings.

```
Inbox                       [All] [Questions] [Files] [Shared]
---------------------------------------------------------------
? Grok asks: Pay this invoice?          [Pay] [Not now]   2 min
  Hostinger, $11.99, due Oct 30.
---------------------------------------------------------------
# Codex sent  Hostinger invoice Oct.pdf            open   1 h
---------------------------------------------------------------
^ You shared  IMG_2041.jpg -> Claude     picked up 14:02   3 h
---------------------------------------------------------------
v Answered: Muse "Book the 9am?" -> Yes              yesterday
```

- Newest first, with filters for Questions, Files and Shared (to agents).
- **Open questions are pinned at the top** with their buttons, so Rahul can
  answer straight from the list.
- Tapping a row opens it: the full question card, the file viewer (images
  and PDFs in-app, everything else via "Open with"), or the shared item and
  its pickup status.
- An unread count shows on the Inbox tab and as the app icon badge.
- Items older than 30 days drop off the list (files are deleted unless saved
  to Downloads). The audit log keeps the record.

## 6. Permissions

Each of these is a per-agent switch (see `spec-per-agent-permissions.md`):
**Prompts** (may ask questions), **Send files**, **Shared items** (Rahul can
share to this agent), and **Callback URL** (approved URL or none). All start
off.

## 7. Acceptance tests

1. Codex (no webhook) posts a yes/no prompt, Rahul taps **Yes** in the
   notification, and Codex's open `wait=55` request returns `answered` within
   a second.
2. Grok with an approved tailnet callback receives a correctly signed POST
   for the same answer. A non-approved URL is never called.
3. An HTML card can't load an external image or script (blocked and logged),
   and `Bridget.respond` round-trips JSON.
4. Claude sends a PDF; it appears in the Inbox, opens in-app, and **Save to
   Downloads** puts it in `Downloads/Bridget/Claude/`.
5. Rahul shares a screenshot to Grok from Photos; Grok's long poll returns it
   resized to 1568 px; the Inbox shows "picked up".
6. With the phone locked, the notification text is hidden until unlock.

## Open questions

1. Should urgent prompts be allowed to override Do Not Disturb?
2. Keep answered questions for 30 days, or longer?
3. Should Rahul's "Save to Downloads" be the default for files?
