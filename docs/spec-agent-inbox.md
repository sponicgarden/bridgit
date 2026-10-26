# Spec: Interactions, send to phone, and the Inbox screen (Bridget 1.4)

Status: proposed, 26 Oct 2026. Not built. Covers feature ideas #2 and #6
(agents reach Rahul with an **interaction**: a one-way **notice**, or a
**question** that waits for his answer, either of which can be spoken aloud),
#3 (Rahul shares things to an agent) and #4 (agents send files to the phone),
all in one new **Inbox** screen in the app.

## 1. How agents get answers without a webhook

Many agents can't receive incoming calls. Grok has a webhook, and some Claude
setups may, but Codex and Claude Code sessions usually don't. So every
"something for the agent" item can be fetched **two ways**:

- **Wait-and-ask (works for every agent):** the agent sends a request such as
  `GET /v1/interactions/{id}?wait=55`. Bridget holds the request open until there
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

## 2. Interactions: notices and questions

An agent sends a small card to the phone. There are two kinds:

- **notice**: one-way information ("Backup finished", "Your 3pm moved to
  4pm"). No controls. Rahul can tap **Got it**, and the agent can see whether
  and when he saw it (`status: "seen"`). It never waits for an answer.
- **question**: needs an answer, given with buttons, a choice or a text reply.

Either kind can be **spoken aloud** on the phone (feature #6) with
`"speak": true`. Bridget reads the title and text with Android's built-in
text-to-speech, prefixed with the agent's name ("Grok says: ..."). Speaking
is skipped while the phone is in Do Not Disturb or silent mode, or on a call,
unless `priority` is `urgent` and Rahul allowed urgent speech for that agent.

### Fast template (build first)

```
POST /v1/interactions
{
  "kind": "notice" | "question",             // required
  "title": "Pay this invoice?",              // required, max 80 chars
  "text": "Hostinger, $11.99, due Oct 30.",   // max 2,000 chars, plain text
  "image": { "gallery_id": "87567" } | { "data_base64": "...", "mime": "image/jpeg" },  // optional, max 2 MB
  "controls": [                               // question: 1-6 controls; notice: none
    { "type": "button", "id": "yes", "label": "Pay", "style": "primary" },
    { "type": "button", "id": "no",  "label": "Not now" },
    { "type": "text",   "id": "note", "label": "Reply", "placeholder": "Anything to add?" },
    { "type": "choice", "id": "when", "label": "When", "options": ["Today", "This week"] }
  ],
  "priority": "normal" | "urgent",            // urgent = heads-up notification with sound
  "speak": false,                             // read it aloud on the phone
  "expires_in_seconds": 3600,                 // 60 s to 7 days, default 1 hour
  "callback": true                            // also POST the answer to this agent's approved callback URL
}
-> 201 { "interaction_id": "i_8f2c", "status": "pending", "expires_at": "..." }
```

- **On the phone:** a notification "**Grok asks:** Pay this invoice?" (a
  notice reads "**Grok:** Backup finished"). The
  first three buttons appear right in the notification (Android shows at most
  three). A text control adds an inline reply field. Tapping the notification
  opens the full card in the Inbox with the image and every control.
- **Lock screen:** shows only "Grok has a question" until the phone is
  unlocked (setting: *Show question text on lock screen*, default off).
- **Answer:**

```
GET /v1/interactions/i_8f2c?wait=55
-> { "kind": "question",
     "status": "answered" | "seen" | "pending" | "expired" | "dismissed" | "cancelled",
     "answer": { "button": "yes", "values": { "note": "use the Amex", "when": "Today" } },
     "answered_at": "..." }
```

- `seen` applies to notices (Rahul tapped **Got it** or opened it). Questions
  go from `pending` to `answered`, `dismissed` or `expired`.
- The agent can cancel an interaction that's no longer needed:
  `DELETE /v1/interactions/{id}`. The notification disappears.
- Rahul can **Dismiss** a question without answering (status `dismissed`).
- Limits: 5 open questions per agent (`too_many_open`, 429), and 30 notices
  per agent per hour.

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
Inbox                  [All] [Interactions] [Files] [Shared]
---------------------------------------------------------------
? Grok asks: Pay this invoice?          [Pay] [Not now]   2 min
  Hostinger, $11.99, due Oct 30.
---------------------------------------------------------------
i Claude: Backup finished, 3 files              [Got it] 20 min
---------------------------------------------------------------
# Codex sent  Hostinger invoice Oct.pdf            open   1 h
---------------------------------------------------------------
^ You shared  IMG_2041.jpg -> Claude     picked up 14:02   3 h
---------------------------------------------------------------
v Answered: Muse "Book the 9am?" -> Yes              yesterday
```

- Newest first, with filters for Interactions (notices and questions),
  Files and Shared (to agents).
- **Open questions are pinned at the top** with their buttons, so Rahul can
  answer straight from the list.
- Tapping a row opens it: the full notice or question card (with a
  **Play aloud** button), the file viewer (images
  and PDFs in-app, everything else via "Open with"), or the shared item and
  its pickup status.
- An unread count shows on the Inbox tab and as the app icon badge.
- Items older than 30 days drop off the list (files are deleted unless saved
  to Downloads). The audit log keeps the record.

## 6. Permissions

Each of these is a per-agent switch (see `spec-per-agent-permissions.md`):
**Notices**, **Questions** and **Speak aloud** (may send each kind, and
may have it read out), **Send files**, **Shared items** (Rahul can
share to this agent), and **Callback URL** (approved URL or none). All start
off.

## 7. Acceptance tests

1. Codex (no webhook) posts a yes/no question, Rahul taps **Yes** in the
   notification, and Codex's open `wait=55` request returns `answered` within
   a second.
2. Grok with an approved tailnet callback receives a correctly signed POST
   for the same answer. A non-approved URL is never called.
3. Claude sends a notice with `speak: true`: the phone reads "Claude says:
   ..." aloud, the agent's long poll returns `seen` after **Got it**, and
   nothing is spoken while Do Not Disturb is on.
4. An HTML card can't load an external image or script (blocked and logged),
   and `Bridget.respond` round-trips JSON.
5. Claude sends a PDF; it appears in the Inbox, opens in-app, and **Save to
   Downloads** puts it in `Downloads/Bridget/Claude/`.
6. Rahul shares a screenshot to Grok from Photos; Grok's long poll returns it
   resized to 1568 px; the Inbox shows "picked up".
7. With the phone locked, the notification text is hidden until unlock.

## Open questions

1. Should urgent interactions be allowed to override Do Not Disturb, and to
   speak while it's on?
2. Keep answered questions for 30 days, or longer?
3. Should Rahul's "Save to Downloads" be the default for files?
