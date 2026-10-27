# Bridget — UI design spec

Version 0.1 · 27 Oct 2026 · Status: **draft for design review**

- **Mockups (Design canvas, private):** https://claude.ai/artifact/8jrHeNcjXf1c3XmkEN84FA
  Home, Add agent → setup prompt, Activity (with the entry sheet open), Inbox,
  Access, Report a bug, each in light and dark (12 frames, 390 × 844 dp). The
  link opens only for its owner until it is shared from the canvas's Share menu.
- **Source of truth:** `docs/bridget-spec-v2.md` (2.1). This document expands
  section 12 into screen-by-screen design detail; it does not change any
  requirement. Where it proposes something section 12 doesn't say, it's
  marked **Proposal** and repeated in the open questions (section 9).
- **Platform:** native Kotlin Views, Material Components for Android
  (Material 3), single Activity, one Fragment per destination (UI-01, UI-07).
  No Compose; no WebView except HTML interaction cards (CAP-34).

Contents: 1 Principles · 2 Tokens · 3 Shared components · 4 Navigation ·
5 Screens · 6 System surfaces · 7 Accessibility checklist · 8 Traceability ·
9 Open design questions

---

## 1. Principles

"Techie but friendly" (12.1):

1. **Friendly layer first.** Every row leads with a plain sentence in the
   second person ("Grok took a screenshot"). Technical values sit under it in
   mono chips, and the full detail is one tap away (UI-06).
2. **Mono means machine.** JetBrains Mono is used only for keys, IPs,
   hostnames, endpoints, HTTP codes, request IDs, JSON, the setup prompt and
   log lines (UI-03). Never for headings, labels or buttons.
3. **Gold means live.** The beacon gold (`secondary`) is reserved for
   things happening right now: capturing, waiting for a first call, an active
   grant, Live log tailing, "Waiting for you". Teal (`primary`) is for
   actions and "on". This mirrors the icon: teal bridge, gold beacon.
4. **Banners only when action is needed** (UI-10). A calm Home has no banner.
5. **Errors say what to do**, never blame (UI-06). No emoji anywhere.

## 2. Tokens

Implemented as a `Theme.Bridget` (parent `Theme.Material3.DayNight.NoActionBar`)
with `values/` and `values-night/` colour resources. **Dynamic colour off**
(UI-02).

### 2.1 Colour

Rows marked **UI-02** are copied unchanged from the spec. Rows marked
**derived** fill the Material 3 roles UI-02 doesn't list (needed by
`MaterialSwitch`, chips, error banners, etc.); they're tonal steps of the same
hues and are proposals (Q-D1).

| Role (M3 attr) | Light | Dark | Source | Used for |
|---|---|---|---|---|
| `colorPrimary` | `#0F6E64` | `#5FD4C4` | UI-02 | Filled buttons, switch track on, links, active tab text |
| `colorOnPrimary` | `#FFFFFF` | `#00372F` | UI-02 | Text/icons on primary |
| `colorPrimaryContainer` | `#A6F2E6` | `#005047` | UI-02 | Hero card, selected chips, nav indicator pill, tonal buttons |
| `colorOnPrimaryContainer` | `#00201C` | `#A6F2E6` | derived | Text on primary container |
| `colorSecondary` (beacon gold) | `#7A5900` | `#FFC15E` | UI-02 | Live dot, "Waiting for you" heading, Live chip dot |
| `colorSecondaryContainer` | `#FFDEA6` | `#5C4200` | UI-02 | Live banners, key highlight in the setup prompt, "Partly" chip |
| `colorOnSecondaryContainer` | `#261900` | `#FFDEA6` | derived | Text on secondary container |
| `colorSurface` / background | `#F6FAF8` | `#0E1513` | UI-02 | Screen background |
| `colorSurfaceContainer` | `#EAF0EE` | `#1A2120` | UI-02 | Cards, nav bar, bottom sheets, search field |
| `colorSurfaceContainerHigh` | `#E1E8E5` | `#252B2A` | derived | Mono chips, "Off" chip, switch track off, avatar for Bridget's own entries |
| `colorOnSurface` | `#171D1B` | `#DDE4E1` | UI-02 | Body text |
| `colorOnSurfaceVariant` | `#3F4947` | `#BEC9C6` | derived | Secondary text, inactive nav icons, timestamps |
| `colorOutline` | `#6F7977` | `#899391` | UI-02 | Outlined buttons, chip borders, switch outline off |
| `colorOutlineVariant` | `#BEC9C6` | `#3F4947` | derived | Hairlines, card borders on the prompt card |
| `colorError` | `#BA1A1A` | `#FFB4AB` | UI-02 | Error stripe, Remove, Deny, nav badge |
| `colorErrorContainer` | `#FFDAD6` | `#93000A` | derived | Error result chips, "Blocked" chip, error banner |
| `colorOnErrorContainer` | `#410002` | `#FFDAD6` | derived | Text on error container |
| `success` (custom attr) | `#1E7A3C` | `#7FD99A` | UI-02 | Active-agent dot, "Connected" |
| `successContainer` (custom) | `#C3EFCD` | `#005226` | derived | "On" chip, 2xx result chip, Connected card |
| `onSuccessContainer` (custom) | `#00210B` | `#9CF6B4` | derived | Text on success container |

**Beacon dot states** (UI-09): teal `primary` = listening; gold `#FFC15E`
pulsing = capturing now / waiting; grey `outline` = paused; `error` = Tailscale
down. Always paired with a text label ("Listening", "Paused", …).

**Agent avatar tints** (UI-12): first letter on a tinted circle, colour picked
by hashing the agent name into 8 fixed pairs (container / on-container). The
mockups use the light pairs in both themes; **Proposal**: dark theme uses the
same hues at tone 30 container / tone 90 text (Q-D2).

| # | Light bg / fg | Dark bg / fg |
|---|---|---|
| 1 violet | `#E9D5FF` / `#3B0764` | `#4C1D95` / `#EDE9FE` |
| 2 orange | `#FED7AA` / `#431407` | `#7C2D12` / `#FFEDD5` |
| 3 blue | `#BFDBFE` / `#172554` | `#1E3A8A` / `#DBEAFE` |
| 4 pink | `#FBCFE8` / `#500724` | `#831843` / `#FCE7F3` |
| 5 lime | `#D9F99D` / `#1A2E05` | `#365314` / `#ECFCCB` |
| 6 cyan | `#A5F3FC` / `#083344` | `#164E63` / `#CFFAFE` |
| 7 amber | `#FDE68A` / `#451A03` | `#78350F` / `#FEF3C7` |
| 8 slate | `#E2E8F0` / `#0F172A` | `#334155` / `#F1F5F9` |

All text/background pairs above meet WCAG AA (4.5:1) for 14 sp text; verify
with the build's contrast test (UI-29).

### 2.2 Type

Roboto (system) with the M3 type scale; JetBrains Mono bundled (Q7) as
`@font/jetbrains_mono` (Regular 400, Medium 500).

| Style (M3) | Size / line | Weight | Used for |
|---|---|---|---|
| `textAppearanceHeadlineSmall` | 24 / 32 | 400 | First-run page titles |
| `textAppearanceTitleLarge` | 22 / 28 | 400 | Top app bar titles, hero state ("Listening", 500) |
| `textAppearanceTitleMedium` | 16 / 24 | 500 | Section headings, sheet titles |
| `textAppearanceBodyLarge` | 16 / 24 | 400 | Intro paragraphs, form fields |
| `textAppearanceBodyMedium` | 14 / 20 | 400 | List rows, secondary lines |
| `textAppearanceLabelLarge` | 14 / 20 | 500 | Buttons, chips |
| `textAppearanceLabelMedium` | 12 / 16 | 500 | Nav labels, day headers, status chips |
| `textAppearanceBodySmall` | 12 / 16 | 400 | Captions, timestamps |
| `TextAppearance.Bridget.Mono` | 12 / 16 | 400 | Mono chips, address, IDs |
| `TextAppearance.Bridget.Mono.Prompt` | 12 / 18 | 400 | Setup prompt card, entry sheet JSON |

All sizes in `sp`, so they follow the system font scale.

### 2.3 Spacing and size

4 dp grid. Screen side gutter **16 dp**. Gap between sections **12 dp**
(10 on Home to fit the first screen), inside cards **8 dp**. Card padding
**16 dp** (14 on dense cards). List rows: one-line **48 dp**, two-line
**56–64 dp**, activity rows **≥ 64 dp**. Touch targets **≥ 48 dp** (icon
buttons 48 × 48; 40 dp-high buttons get 4 dp inset padding to reach 48).
Top app bar 64 dp; navigation bar 80 dp.

### 2.4 Shape

| Element | Corner | M3 shape |
|---|---|---|
| Cards, banners, prompt card | 12 dp | `shapeAppearanceCornerMedium` |
| Bottom sheets, full-screen dialog top | 28 dp (top only) | `shapeAppearanceCornerExtraLarge` |
| Chips, mono chips | 8 dp / 4 dp | `CornerSmall` / `CornerExtraSmall` |
| Buttons, search bar, nav indicator | full (pill) | `CornerFull` |
| Text fields | 4 dp (outlined) | `CornerExtraSmall` |
| Avatars, dots | circle | — |

### 2.5 Motion

| Motion | Spec |
|---|---|
| Screen transitions | M3 shared axis X for forward/back inside a tab; fade-through between tabs |
| Bottom sheet, dialogs | M3 defaults |
| Beacon pulse | Gold dot, ring grows 0 → 7 dp and fades, 1.6 s ease-in-out, infinite while live (UI-04) |
| State change (Waiting → Connected) | 200 ms cross-fade + check-mark draw 300 ms |
| List insertions (Live log) | 150 ms fade + slide 8 dp |
| **Remove animations** on (`ANIMATOR_DURATION_SCALE = 0`) | Pulse becomes a static gold dot; all transitions instant |

## 3. Shared components

| Component | Material class | Notes |
|---|---|---|
| Top app bar | `MaterialToolbar` (small) | Title left; ⋮ overflow on every main screen holds **Report a bug** and **Request a feature** (FBK-01) plus screen items |
| Navigation bar | `BottomNavigationView` (M3) | 5 items, active pill in `primaryContainer`; Inbox `BadgeDrawable` |
| Card | `MaterialCardView` (filled, `surfaceContainer`, no stroke) | Elevation 0 |
| Hero card | `MaterialCardView` in `primaryContainer` | Home only |
| Banner | `MaterialCardView` in `secondaryContainer` (warning) or `errorContainer` (error), leading icon, one text button | Not the M3 banner component (none in MDC-Android) |
| Switch | `MaterialSwitch` | Label is the row text; whole row toggles |
| Filter chips | `Chip` style `Widget.Material3.Chip.Filter` in a `ChipGroup` | Single-select (Inbox) or multi (Activity) |
| Dropdown chip | `Chip` with trailing ▾ icon, opens a `ListPopupWindow` | Agent ▾, Type ▾ |
| Mono chip | `TextView` with `TextAppearance.Bridget.Mono`, 4 dp corners, `surfaceContainerHigh` | Not clickable; endpoint, code, size, duration |
| Status chip | `Chip` (assist, non-clickable) with leading check for **On** | On / Off / Partly / Blocked always as text |
| Buttons | `MaterialButton` filled / tonal / outlined / text | One filled button per view max |
| Text field | `TextInputLayout` outlined | Error text below the field |
| Segmented control | `MaterialButtonToggleGroup` (single selection, required) | Bug / Feature |
| Lists | `RecyclerView` + `ListAdapter`; sticky day headers via `ItemDecoration` | |
| Sheets | `BottomSheetDialogFragment` | Drag handle shown |
| Dialogs | `MaterialAlertDialogBuilder` | Max 3 buttons |
| Feedback | `Snackbar` | Above the nav bar |
| Progress | `LinearProgressIndicator` (determinate segments for Add agent), `CircularProgressIndicator` (small, in buttons) | |
| Avatar | Custom `AgentAvatarView` (circle, first letter) | Content description = agent name |

**Empty state pattern:** centred 64 dp outlined icon in `onSurfaceVariant`,
one `TitleMedium` line, one `BodyMedium` line, optional tonal button.
**Loading pattern:** for lists, 3 skeleton rows (`surfaceContainerHigh`
blocks, no shimmer when Remove animations is on); never a full-screen
spinner. **Error pattern:** inline banner at the top of the screen with a
retry or fix button.

## 4. Navigation

```
[ Home ] [ Inbox ] [ Agents ] [ Activity ] [ More ]
```

- Bottom navigation, five destinations, each with its own back stack (UI-07).
- **More** is a list screen: Features, Access, Settings, Report a bug,
  Request a feature, My reports, About (UI-07).
- Inbox badge = unanswered questions + unread items; hidden at 0; "99+" cap.
- At font scale ≥ 150 % the bar becomes icons only
  (`labelVisibilityMode = unlabeled`), labels kept as content descriptions
  and long-press tooltips (UI-07, UI-29).
- Full-screen flows without the nav bar: First run, Add agent, Report form,
  Interaction detail, file viewer.
- Icons (Material Symbols Outlined, filled when active): `home`, `inbox`,
  `group`, `monitoring` (activity), `menu`.

## 5. Screens

Each screen lists: purpose · layout · components · copy · states ·
accessibility. Copy in **bold quotes** is final text from the spec; plain
quotes are proposals.

### 5.1 First run (UI-08)

**Purpose:** explain Bridget in three pages, get the notifications permission,
confirm Tailscale, then hand off to Add agent.

**Layout:** full screen, no toolbar. `ViewPager2` with a page indicator
(three dots, `TabLayout` dot style) near the bottom; app icon (96 dp)
centred in the top third; headline + body; buttons pinned to the bottom.
Bottom row: **Skip** (text, left) on pages 1–2; **Next** (filled, right).

| Page | Headline | Body | Extra |
|---|---|---|---|
| 1 | **"Meet Bridget."** | **"Let your AI agents use this phone's camera, mic, photos and more, only when they ask, always visibly."** | — |
| 2 | **"Private by design."** | **"Bridget only answers over your Tailscale network. Each agent gets its own key. Everything is logged here."** | Tailscale check row; notifications explainer + system permission request |
| 3 | **"You're in control."** | **"Every feature starts off. Turn on what you want, and switch anything off in one tap."** | **Add your first agent** (filled), **Later** (text) |

Page 2 detail:
- Tailscale row (card): checking state "Checking Tailscale…" with small
  circular progress; success **"Tailscale: connected (100.94.27.79)"** with
  success check, IP in mono; failure "Tailscale isn't connected" with
  **Open Tailscale** (tonal; opens the Tailscale app, or its Play page if
  missing).
- Notifications line (above the button): **"Bridget uses notifications to
  show you every time an agent uses your phone. Without them, camera, mic and
  screenshots stay off."** Button "Allow notifications" triggers the
  `POST_NOTIFICATIONS` request. If denied: the line turns into a warning
  banner with "Open settings" (deep link `APP_NOTIFICATION_SETTINGS`); Next
  stays enabled.

**States:** Tailscale checking / connected / not connected; permission not
asked / granted / denied. **Back** on page 1 exits the app.
**A11y:** each page is announced as "Page 2 of 3"; the indicator dots are
not focusable; the Tailscale row is a live region.

### 5.2 Home (UI-09 … UI-11) — mockup frame "Home"

**Purpose:** at a glance: is Bridget listening, does anything need me, who
used the phone, what's on.

**Layout** (scrolls; `NestedScrollView` with vertical `LinearLayout`):
1. Toolbar: app icon (28 dp) + **Bridget**; ⋮ (Report a bug, Request a
   feature, Settings).
2. **Hero card** (`primaryContainer`): beacon dot + state ("Listening" /
   "Paused" / "Capturing" / "Tailscale is down"), **Pause** outlined button
   (becomes filled **Resume** when paused); address in mono
   `pixel-10-pro-xl.tail9c9221.ts.net:8734` (tap copies, Snackbar "Address
   copied"); chips row: "Up 3 h 12 m", "✓ Tailscale", "Battery 64%".
3. **Banners** (0–n, most urgent first), each one line + one text button:

| Trigger | Text | Button |
|---|---|---|
| ARC-03 camera/mic not active | "Camera and mic need the app opened once" | **Fix now** → **Enable camera and mic** action |
| Notifications off (PRD-05) | "Notifications are off. Camera, mic and screenshots stay off until they're on." | "Turn on" |
| Tailscale down | "Tailscale isn't connected. Agents can't reach Bridget." (error style) | "Open Tailscale" |
| Battery optimised | "Android may stop Bridget when the screen is off." | "Fix" |
| Permission revoked | "Camera permission was removed." | "Grant" |
| Feedback status changed | "Report #0007 is marked fixed." | "Check it" |

4. **Agents** heading + "See all" link; up to 3 rows (avatar 32 dp, name,
   "· 2 min ago", last endpoint in mono, status dot: success = call in the
   last 10 min, outline ring = idle). Tap → Agent detail.
5. **Today** card (one line, tap → Activity filtered to today): "Today · 42
   requests · 3 photos · 1 clip · 0 errors". Errors > 0 shows the count in
   `error`.
6. **Quick switches**: 2 × 2 grid of small cards, each label +
   `MaterialSwitch` (Camera, Microphone, Gallery, Location by default — the
   four most-used master switches). **Proposal:** grid of switch cards
   rather than chips (Q-D4).
7. **Waiting for you (n)**: the newest open question as a card: "**Grok
   asks:** Pay this invoice?" with up to two answer buttons (outlined
   secondary, filled primary). Hidden in P1 and when nothing is open.
   Tapping the card opens Interaction detail.

**States:**
- *No agents yet:* Agents section replaced by an empty card: "No agents
  yet. Add one to let it use this phone." + **Add agent** (tonal).
- *Paused:* grey dot, "Paused", address greyed, Today still shown.
- *Capturing:* gold pulsing dot, "Grok is taking a photo" for the duration.
- *Listener error:* hero card switches to `errorContainer`: "Bridget can't
  listen on port 8734" + "Details" (opens the diagnostic log entry).
- *Loading:* hero card shows "Starting…" with the dot grey; never blocks.

**A11y:** hero card is one focus stop with a combined description
("Bridget is listening. Address pixel-10-pro-xl…, up 3 hours 12 minutes,
Tailscale connected, battery 64 percent") and an `accessibilityLiveRegion`
of polite so state changes are announced; Pause is a separate stop. Status
dots have content descriptions ("Active", "Idle").

### 5.3 Agents list (UI-12)

**Purpose:** see and manage all agents (up to 16, AGT-02).

**Layout:** toolbar **Agents**; `RecyclerView` of agent cards; **Add agent**
`ExtendedFloatingActionButton` (icon `add`, primary container) bottom-right,
above the nav bar, shrinks to icon-only on scroll.

**Card:** avatar 40 dp · name (`TitleMedium`) · connection chip
(**Tailscale** / **Userspace** / **Relay**) · second line "last used 2 min ago
· 42 today" · status dot (success = call in last 10 min) · chevron. Developer
agents get a small "Developer" label chip (AGT-15).

**States:**
- *Empty:* icon `group`, "No agents yet", "Each AI agent gets its own key.
  Add one and paste the setup prompt into it.", **Add agent** (filled; the
  FAB is hidden while empty).
- *16 agents:* FAB disabled; tapping shows Snackbar "Bridget holds up to 16
  agents. Remove one to add another."
- *Key-rotated within 7 days:* row line 2 reads "Key rotated · waiting for
  new prompt" in `secondary`.

**A11y:** each card is one focus stop: "Grok, Tailscale, last used 2
minutes ago, 42 requests today, active".

### 5.4 Add agent flow (6.2, UI-13, AGT-03 … AGT-08) — mockup frame "Add agent"

Full-screen Fragment (no nav bar). Toolbar: ✕ close, title "Add agent" (step
1) then "Add Muse" once named; overflow ⋮ with **Pair with code** (AGT-13).
Under the toolbar: a **4-segment progress indicator** (four
`LinearProgressIndicator`-style segments, filled up to the current step) with
labels Name · Connection · Check · Setup prompt. Spec steps map like this:

| Spec step (6.2) | Screen | Indicator |
|---|---|---|
| 0 Listener on | Invisible; happens on open. If it fails, Step 3 shows the failure. | — |
| 1 Name | Step 1 | 1/4 |
| 2 How it connects | Step 2 | 2/4 |
| 3 Self-check | Step 3 | 3/4 |
| 4 Setup prompt | Step 4 | 4/4 |
| 5 Waiting for first call | Same screen as step 4, live row below the actions | 4/4 |

Bottom bar on steps 1–3: **Back** (text, left; hidden on step 1) and
**Next** (filled, right).

#### Step 1 — Name
- `TextInputLayout` outlined, label "Name", helper "Letters, numbers,
  spaces, - and _. Up to 24.", counter 24.
- Below: **suggestion chips** (`ChipGroup`, single line wrapping): Muse,
  Grok, Claude, Codex, ChatGPT, Gemini — names already used are hidden.
  Tapping a chip fills the field.
- Errors (inline, on Next): "Use letters, numbers, spaces, - or _" ;
  "You already have an agent called Grok" (case-insensitive).
- Next disabled while empty.

#### Step 2 — Connection
- Three large radio cards (`MaterialCardView` checkable, 1 dp outline,
  selected = 2 dp `primary` outline + `primaryContainer` tint + check icon),
  icon 32 dp + title + one line:
  - **Tailscale (direct)** — **"The agent's computer is on your Tailscale
    network."** (default, preselected)
  - **Tailscale, userspace host** — **"The agent runs on a server where
    Tailscale can't route normally, like Grok's box."**
  - **Relay** — **"The agent isn't on Tailscale."** (only when relay mode is
    set up, ARC/4.4)
- A11y: the three cards form a radio group (`RadioGroup` semantics via
  `AccessibilityDelegate`: "Tailscale direct, 1 of 3, selected").

#### Step 3 — Check
- Checklist (three or four rows), each with a 24 dp leading state icon:
  pending (small circular progress), pass (success check), fail (error
  icon) and the label: "Listener running", "Tailscale address
  (100.94.27.79)", "Reachable on port 8734", "Relay reachable" (relay only).
- Failed row: one-line reason + **Fix** tonal button (opens the right place:
  start listener, Open Tailscale, Access screen).
- Self-connection-only failure: warning row in `secondaryContainer`:
  **"Couldn't confirm from the phone itself; it may still work from the
  agent"** and Next stays enabled ("Continue anyway").
- Next enabled when all pass (or only the self-connection warns). The
  checks re-run on resume.
- A11y: each row is a live region; result announced as it ticks.

#### Step 4 — Setup prompt (AGT-06 … AGT-08)
`FLAG_SECURE` on this window (AGT-08). Layout top to bottom:
1. Intro: "Paste this into Muse. It tells Muse how to reach this phone and
   holds Muse's key."
2. Warning line with lock icon: **"Anyone who gets this text can use your
   phone until you rotate the key."**
3. **Prompt card**: `MaterialCardView` (surfaceContainer, 1 dp
   outlineVariant), `TextAppearance.Bridget.Mono.Prompt`, selectable text,
   scrolls inside the card (max ~45 % of screen height, fade at the bottom
   edge). The key line is highlighted with a `secondaryContainer` span
   (UI-13). Content = Appendix B template.
4. Actions row: **Copy prompt** (filled, full width), **Share** (tonal),
   **QR** (outlined icon button, hidden for userspace prompts, AGT-10);
   ⋮ menu (the toolbar overflow) adds **Copy key only**.
   - Copy → Snackbar **"Copied. Paste it into Muse. Bridget clears it the
     next time you open the app."**
   - QR → bottom sheet with the QR (≥ 240 dp, black on white in both
     themes, quiet zone 4 modules), caption "Scan with the agent's phone
     app. Holds the address and key only."
5. **Waiting row** (step 5): `secondaryContainer` card with the pulsing gold
   dot, **"Waiting for Muse's first call…"**, sub-line "This switches to
   'connected' by itself."
   → on first call becomes a `successContainer` card with a check:
   **"Muse connected"** and mono line `GET /v1/health · 140 ms ·
   100.83.104.121`.
   → after 10 minutes with no call (AGT-04): neutral card **"No call yet.
   Check that the agent's computer is on Tailscale, or show the
   troubleshooting steps."** with **Troubleshooting** (text button → sheet
   with steps for the chosen connection type).
6. Bottom: **Done** (outlined while waiting; becomes filled once connected).

**Leaving without copying** (✕, back, Done before any copy) — dialog
(AGT-05): title "You won't see this key again", body **"Copied it?"**,
buttons **Copy and leave** · **Stay** · **Leave**.
**Crash/kill return** (AGT-05a): reopening Bridget within 30 min lands back
on this step with a Snackbar "Picked up where you left off. The key is
still here for 28 more minutes."

#### Pair with code (AGT-13)
Overflow item on Add agent. Full-screen, `FLAG_SECURE`. Large 6-digit code
in mono (`displaySmall`, digits spaced in pairs "482 913"), countdown
`LinearProgressIndicator` + "Expires in 4:32", instruction "Ask the agent to
call `POST /v1/pair` with this code." Expired: "Code expired" + **New code**.
Five wrong tries: "Too many wrong codes. Start again." A11y: the code is read
digit by digit.

### 5.5 Agent detail (UI-14, AGT-12 … AGT-15)

**Layout:** toolbar with back + agent name + ⋮ (Rename, Report a bug).
Scrolling content:
1. Header: avatar 64 dp, name `HeadlineSmall`, connection chip, "Added 26
   Oct · last used 2 min ago from `100.110.68.46`".
2. Stats row: three equal tiles — "42 requests today", "1 error", "18 MB
   sent" — with a thin `LinearProgressIndicator` under data sent against the
   SEC-08 limit ("18 of 500 MB today").
3. **Key**: prefix in mono `brg_Xk3f…`, **Rotate key** (outlined). Rotate
   confirm: "Rotate Grok's key? The old key stops working right away. You'll
   get a new setup prompt to paste into Grok." [Rotate] [Cancel] → opens Step
   4 of the Add agent flow for this agent.
4. **Callback** (P2): URL in mono, **Test**, **Remove**; empty "No callback
   URL. Agents without one use the event stream."
5. **Developer** (Q16): `MaterialSwitch` "Developer — can read bug reports
   and their screenshots" (off by default).
6. **Notes**: `TextInputLayout` multi-line, 200-character counter, saves on
   blur.
7. **Recent activity**: last 50 entries, same row as Activity; "See all" →
   Activity filtered to this agent. Empty: "Grok hasn't made any calls yet."
8. **Permissions**: disabled card: **"Coming later: choose what each agent
   can do. Today all agents share the switches in Features."**
9. **Remove agent** (outlined button in `error` colour, full width). Confirm:
   "Remove Grok? Its key stops working. Grok will be told the agent was
   removed." [Remove] (error text button) [Cancel]. Removing the last agent
   adds "Bridget will stop listening until you add another agent."

### 5.6 Inbox (UI-15, UI-16, P2) — mockup frame "Inbox"

**Purpose:** everything agents sent to Rahul and he sent to agents.

**Layout:** toolbar **Inbox** (⋮: Mark all read, Report a bug);
`ChipGroup` single-select filter chips **All · Questions · Notices · Files
· Shared**; list. Open questions are pinned under a gold "Waiting for you"
heading as cards with their buttons; the rest under "Earlier" as rows.

| Row type | Leading icon (40 dp circle) | Line 1 | Line 2 | Trailing |
|---|---|---|---|---|
| Open question | agent avatar | "**Grok asks:** Pay this invoice?" | text preview, 2 lines max | time; up to 2 buttons on the card |
| Notice | `info` on primaryContainer | "**Claude:** Backup finished, 3 files" | — | time; **Got it** text button while unseen |
| File received | `description` on surfaceContainerHigh | "**Codex sent** a file" | filename + size in mono | time |
| Shared by Rahul | `upload` | "**You shared** a photo with Claude" | filename in mono · "picked up 14:02" / "waiting for pickup" | time |
| Answered | `check` on successContainer | "You answered Muse" | ""Book the 9am?" · Yes" | date |
| Expired / cancelled | `schedule`, row at 75 % opacity | "Expired: Grok "Order more coffee?"" | "No answer within 24 hours" | date |

Unread rows: title weight 500 + 8 dp `primary` dot before the time.

**Empty states** (per chip): All — icon `inbox`, "Nothing here yet",
"Questions, notices and files from your agents show up here." Questions —
"No questions waiting." Files — "No files yet. Agents can send PDFs and
images to your phone." Shared — "Share a photo or text to an agent from any
app's Share menu."

#### Interaction detail (UI-16, CAP-31 … CAP-34)
Full-screen. Header drawn by Bridget (can't be covered): avatar + "From
Grok" + time + status chip (Waiting / Seen / Answered / Expired / Cancelled).
Body: title (`TitleLarge`), image (fit width, tap to zoom), text
(`BodyLarge`, links not auto-linked). Controls rendered in order:
- buttons → `MaterialButton` (style `primary` → filled, others outlined),
  stacked full-width when more than two;
- text → `TextInputLayout` with the given label/placeholder;
- choice → `ChipGroup` single-select;
- link → text button with external icon and, beneath it, the host or
  package drawn by Bridget in mono (`hostinger.com`, `com.whatsapp`).
Toolbar actions: **Play aloud** (icon `volume_up`), ⋮ Dismiss.
Answered: controls replaced by "You answered **Pay** · 14:03" and the typed
values. Expired: banner "This question expired. Grok was told."
**HTML card** (P5): WebView inside a `MaterialCardView`; Bridget header
"From Grok · custom card" above and the toolbar with **Dismiss** stay
outside the WebView.

#### File detail
Image → `PhotoView`-style zoomable `ImageView`; PDF → `PdfRenderer` pages in
a `RecyclerView`. Bottom app bar: **Open with**, **Save to Downloads**,
**Delete** (confirm "Delete this file from Bridget?"). Header: "From Codex ·
184 KB · 1 h ago". Error: "Can't show this file. Try Open with."

#### Share detail
Preview (image thumbnail or first 10 lines of text), target agent, status
"Waiting for Claude to pick it up" (gold dot) → "Picked up at 14:02"
(check). Action: **Cancel share** while waiting.

### 5.7 Activity (UI-17, section 10) — mockup frame "Activity"

**Purpose:** the full log, readable by a person.

**Layout:**
- Toolbar **Activity**, ⋮: **Export…**, **Clear log**, **Diagnostics**,
  Report a bug.
- Search field (pill, `surfaceContainer`, leading search icon,
  placeholder "Search activity"). Searches message and endpoint (`q`).
- Chip row (horizontal scroll): **Agent ▾**, **Type ▾**, **Errors only**
  (filter chip), and the **Live** toggle chip at the end (selected =
  `secondaryContainer` with pulsing gold dot).
  - Agent ▾ menu: All agents, each agent, "Bridget (system)".
  - Type ▾ menu (multi-select with checkboxes): Requests, Settings, Agents,
    Grants, Interactions, Diagnostics.
  - Active filters show as selected chips with the chosen value ("Grok ▾").
- List grouped by day with sticky headers ("Today", "Yesterday", "Mon 26
  Oct") in `primary` `LabelMedium`.

**Row:** time (right, `BodySmall`), avatar 28 dp (Bridget's own entries use
a gear icon on `surfaceContainerHigh`), sentence (`BodyMedium`), optional
second line (reason or note text), then mono chips: method + endpoint,
HTTP code (2xx → successContainer, 4xx/5xx → errorContainer with the error
code, e.g. `403 capability_disabled`), size, duration, mode. Error rows get
a **3 dp `error` left stripe** (UI-17).

**Sentence templates** (proposal, extend per capability): "Grok fetched the
last screenshot", "Grok took a screenshot", "Claude took a photo", "Claude
couldn't use the camera" + reason "Camera is switched off in Features.",
"You turned Microphone on", "Muse wrote a note" + the note in quotes,
"Bridget started listening", "Tailscale disconnected".

**Entry sheet** (`BottomSheetDialogFragment`): title = the sentence; every
LOG-01 field as a two-column key/value list in mono (id, ts, channel, level,
kind, agent, source_ip, method, endpoint, params, http, result, bytes,
duration, request_id, message, data); actions **Copy as JSON** (outlined),
**Show this agent's activity** (outlined; label "Claude's activity" when
short on space), **Report a bug about this** (text; opens the report form
with this entry attached).

**Export…** → bottom sheet: format **JSONL** / **CSV**
(`MaterialButtonToggleGroup`), "Uses the current filters (128 entries)",
**Export** → system "Create document" picker.
**Clear log** → dialog "Clear the activity log? This deletes 4,812 entries.
Diagnostics are kept." [Clear] [Cancel] (LOG-05).
**Diagnostics** → toolbar subtitle "Diagnostics", chips swap to level
filter; ⋮ shows "Back to activity".

**States:**
- *Empty:* **"Nothing yet. When an agent uses Bridget, it shows up here."**
- *No results for filters:* "No entries match" + **Clear filters**.
- *Loading more:* small circular progress row at the list end.
- *Live on:* new rows animate in at the top; if scrolled down, a
  "3 new entries" pill appears at the top instead of jumping.

**A11y:** each row is one focus stop with a full sentence: "14:02, Grok
fetched the last screenshot, GET slash v1 slash image slash last, 200, 94
kilobytes, 0.44 seconds"; error rows prefix "Error:" (the stripe is not the
only signal). Live new-entry announcements are throttled to one per 5 s.

### 5.8 Features (UI-18)

**Purpose:** master switches.

**Layout:** toolbar back + **Features**; grouped list (`RecyclerView` with
group headers in `primary` `LabelLarge`). Each row: title, one-line
description, `MaterialSwitch` (whole row toggles). Readiness line under the
description in `secondary` with an inline text button when something is
missing.

| Group | Switch | Description (proposal) |
|---|---|---|
| Capture | Camera | "Photos when an agent asks" |
| | Video | "Short clips, up to 30 s" |
| | Microphone | "Audio clips when an agent asks" |
| | Screenshot | "What's on your screen right now" |
| Photos & files | Gallery | "Find and send photos from your gallery" |
| | Files to phone | "Agents can send you files" |
| | Sharing | "Share things to an agent from any app" |
| Where & what | Location | "Where the phone is" |
| | Phone status | "Battery, network, ringer" |
| | App usage | "Which apps you used today" |
| | Health | "Steps, sleep, heart rate from Health Connect" |
| | Notifications | "Read your notifications" |
| Controls | Flashlight | "Turn the torch on and off" |
| | Phone controls | "Do Not Disturb, sound, brightness, ring" |
| Talk to you | Interactions | "Questions and notices from agents" |
| | Speak aloud | "Read messages out loud" |
| On-device AI | Text recognition | "Read text in images, on the phone" |
| Other | Clipboard | "Copy text to your clipboard" |

Readiness examples: **"Needs camera permission. [Grant]"**, **"Needs the app
opened once"**, "Needs Accessibility. [Turn on]" (links to Access).
Unbuilt capabilities are hidden. Toggling shows no dialog; it's logged.
**A11y:** switch label = title + description; readiness line read after.

### 5.9 Access (UI-25 … UI-28, Appendix E) — mockup frame "Access"

**Purpose:** every Android permission/special access with one tap to the
exact settings page.

**Layout:** toolbar back + **Access**; intro "Android settings Bridget can
use. Each button opens the exact page."; two groups: **Needs you** (Off,
Partly, Blocked, sorted Blocked first) and **All set** (On). Footer link
**Show me how**.

**Row:** title, "what this unlocks" line, status chip (**On** with check /
**Off** / **Partly** / **Blocked**), and one outlined button ("Turn on",
"Change", "Grant") for anything not On. Rows re-check on resume (UI-25).

**Blocked row (UI-27)** expands into a card (the mockup uses Notification
access because UI-27 names it; that feature is still undecided, spec Q2, so
treat it as an example of the pattern, not a promise it ships): explanation "Android blocks
this for apps installed from a file until you allow it:" and numbered
steps:
1. Tap **Turn on**, tap the switch once, close the warning.
2. Open App info, then **⋮ → Allow restricted settings**.
3. Come back here and tap **Turn on** again.
Buttons: **Open App info** (text) · **Turn on** (filled).

**Show me how (UI-26):** full screen pager per access item: Pixel
screenshot (from `docs/img/access-*.png`) with a highlight ring, step text
below, "Step 2 of 3".

**Rows (P-phase-aware, hidden until built):** Notifications, Camera and
microphone, Location (while in use / all the time), Photos and videos,
Screenshots (Accessibility), Notification access, Usage access, Do Not
Disturb access, Modify system settings, Battery: unrestricted, Full-screen
notifications.

**A11y:** each row: "Notification access, blocked. Lets agents read your
notifications. Button: Turn on". Steps are a real ordered list.

### 5.10 Settings (UI-19)

Toolbar back + **Settings**. Standard preference-style list built with
Views (no AndroidX Preference styling quirks: use our own row layouts).
Section headers in `primary` `LabelLarge`.

| Section | Rows |
|---|---|
| Listener | Always on (switch) · Port `8734` (mono, opens dialog with number field) · Bind address (mono, read-only) · Allow internet callbacks (switch, off) |
| Screenshots | Mode for agents: **Private copy** / **Android screenshot saved to gallery** (radio dialog) · Accessibility service: status + **Turn on** |
| Privacy | Agents can read the log: **all entries** / **only their own** · Show question text on the lock screen (off) · Log precise locations (off) · Address lookup (off) |
| Log | Keep: 20,000 entries / 30 days (two number fields) · Export · Clear |
| Notifications | One row per channel (Listener, Captures, Questions, Notices, Files, Feedback) → opens the Android channel page |
| Feedback | Also send reports to: Off / finleg intranet tasks / GitHub issue |
| Appearance | System / Light / Dark (radio dialog) |
| About | Version, build, commit (mono) · Signing certificate SHA-256 (mono, tap to copy) · Licences · Spec |

Port change warning dialog: **"Every agent's setup prompt has the old port.
Agents will need new prompts (Rotate key)."** [Change port] [Cancel].

### 5.11 Report form (11.2, UI-20) — mockup frame "Report a bug"

Full-screen dialog Fragment. Toolbar: ✕ **Cancel**, title **"Report a bug"**
/ **"Request a feature"**, **Submit** (filled small button; shows a 16 dp
spinner and "Sending…" while sending; disabled while the description is
empty or images import).

Field order (FBK-04), with one **Proposal**: the Bug | Feature toggle moves
from item 8 to the top, because it changes the title, intro and labels of
everything below (Q-D6). If rejected, it goes back to item 8 unchanged.

1. **Type** — `MaterialButtonToggleGroup` **Bug | Feature** (separate draft
   per type, FBK-05).
2. Error banner (errorContainer; form scrolls to it).
3. Intro (`BodyMedium`, onSurfaceVariant): **"Describe what went wrong. A
   screenshot helps if you have one."** / **"What would make Bridget more
   useful? Sketches and screenshots welcome."**
4. Label **"What happened?"** / **"What would you like to see?"**
5. **Description** — outlined `TextInputLayout`, 5–12 lines, placeholder
   **"Steps to reproduce, what you expected, what you saw…"**; empty-submit
   error **"Please describe the problem before sending."**
6. **Don't auto-fix** checkbox (`MaterialCheckBox`), hint when ticked
   **"Leave this for a person to look at. Nothing gets fixed
   automatically."** Hidden for Feature (always held).
7. **Include last screen** — thumbnail (54 × 96 dp) + label + sub-line
   naming the screen ("Activity, as you left it") + `MaterialSwitch` (on).
   Hidden if capture failed or the screen was `FLAG_SECURE`.
8. **Include recent log** — "50 log entries · Preview" + switch (on).
   Preview opens a sheet with the redacted entries in mono.
9. **Add screenshot** / **Add another** (outlined, `add` icon) → photo
   picker; thumbnails (72 dp, remove ✕ button 48 dp target) in a row;
   caption **"Up to 5 screenshots per report."**
10. **Send updates to** (email field; only when an emailing upload target is
    on).
11. Caption **"App version, device info and the screen you were on are
    included automatically."**

**After submit (FBK-08):** dialog title **"Report saved"** (or "Report
sent"), body **"Thanks. It's report #0007. Developer agents can pick it up
from Bridget's API."** [OK] (+ "View report" in debug builds).
**Rate limit:** banner "That's 10 reports this hour. Try again later."
**Cancel with text:** dialog "Keep this draft?" [Keep] [Discard].

### 5.12 My reports (FBK-09, FBK-11, UI-20)

**List:** toolbar back + **My reports**; filter chips All · Bugs · Features
· Open. Row: kind icon (`bug_report` / `lightbulb`), first line of the
description, "#0007 · 27 Oct · updated by Codex", status chip:

| Status | Chip label | Colour |
|---|---|---|
| `new` | New | surfaceContainerHigh |
| `seen` | Seen by agent | primaryContainer |
| `in_progress` | In progress | secondaryContainer |
| `fixed` | Fixed — check it | successContainer |
| `wont_fix` | Won't fix | surfaceContainerHigh |
| `duplicate` | Duplicate | surfaceContainerHigh |
| `confirmed` | Closed | outline only |
| `reopened` | Reopened | errorContainer |

Empty: "No reports yet. Use Report a bug from any screen's ⋮ menu."

**Detail:** text, images (tap to zoom), attached log (collapsed, "50
entries"), "Don't auto-fix" flag, status history as a vertical timeline
(status, who, note in mono if it contains a PR ref, time). When status is
fixed / won't fix / duplicate: bottom bar **Still broken** (outlined) ·
**Confirm** (filled) (FBK-11a).

### 5.13 More (UI-07)

Simple list, 56 dp rows with leading icons: Features (`toggle_on`), Access
(`verified_user`, trailing count badge of rows needing action), Settings
(`settings`), divider, Report a bug (`bug_report`), Request a feature
(`lightbulb`), My reports (`inbox`, badge when a status changed), divider,
About (`info`).

## 6. System surfaces

### 6.1 Notification channels (UI-21)

| Channel id | Name shown in Android | Importance | Example |
|---|---|---|---|
| `listener` | Listener | Low, ongoing | "Listening · Grok · image/last · 2 min ago" |
| `capture` | Captures | Default | "**Grok took a screenshot**" (auto-dismiss 10 s) |
| `questions` | Questions | High (heads-up) | "**Grok asks:** Pay this invoice?" |
| `notices` | Notices | Default | "**Claude:** Backup finished, 3 files" |
| `files` | Files | Default | "Codex sent Hostinger invoice Oct.pdf" |
| `grants` | Screen sharing | Default, ongoing | "Grok can see your screen · 52 min left" [Stop now] |
| `feedback` | Feedback | Low | "Report #0007 is marked fixed" [Confirm] [Still broken] |

All use the monochrome bridge icon `ic_notif` as small icon and the brand
`primary` as accent colour.

### 6.2 Ongoing listener notification (UI-22)
Title "Bridget is listening" (or "Paused"); text "Grok · image/last · 2 min
ago"; when Accessibility is on, second line "Screenshots on" (CAP-25a).
Actions **Pause** / **Resume** and **Open**. Tap opens Home.

### 6.3 Capture notification (CAP-25a, PRD rule)
Shown for every capture naming the agent: "Grok took a photo", "Claude is
recording audio · 0:12" (with a progress bar for clips), "Grok took a
screenshot". Large icon: the agent avatar bitmap.

### 6.4 Question notification (CAP-33)
Title "**Grok asks:** Pay this invoice?", text = question text; up to three
button actions; a text control becomes inline reply. Lock screen public
version: "Grok has a question" unless the privacy setting allows full text.
Notice: "**Claude:** Backup finished".

### 6.5 Access-needed notice (UI-28)
"**Grok wanted your app usage.** Turn on App usage?" [Turn on] [Not now];
at most once per setting per day.

### 6.6 Quick Settings tile (UI-23)
Label **Bridget**; subtitle "Listening" / "Paused" / "No agents"; icon =
monochrome bridge. Active state = listening, inactive = paused, unavailable
= no agents. Tap toggles; long-press opens Home. Content description
"Bridget, listening. Double-tap to pause."

### 6.7 Home-screen widget (UI-24, P3)
2 × 2: beacon dot + state, last three requests ("Grok · image/last · 2 m").
Material 3 widget shape, `surfaceContainer` background. Out of scope for
the mockups.

## 7. Accessibility checklist (UI-29)

- Every icon-only control has a content description; decorative icons are
  `importantForAccessibility="no"`.
- Touch targets ≥ 48 dp (small buttons use inset padding).
- Contrast: all token pairs AA in both themes (section 2.1); check the
  mono chips at 12 sp especially.
- Colour is never the only signal: status chips have text; error rows say
  "Error:" to TalkBack; status dots have descriptions.
- Layouts tested at 200 % font scale: rows wrap, chips wrap to two lines,
  nav goes icon-only from 150 %, the prompt card keeps scrolling.
- Live regions: hero card state, Add agent check rows and waiting row,
  Access status after resume, Activity Live (throttled).
- Focus order follows reading order; bottom sheets trap focus and return it
  to the opening row.
- Respect Remove animations (pulse static).
- `FLAG_SECURE` screens still work with TalkBack (they only block capture).

## 8. Traceability

| Spec ID | Section here |
|---|---|
| UI-01 | 3 |
| UI-02 … UI-06 | 1, 2 |
| UI-07 | 4, 5.13 |
| UI-08 | 5.1 |
| UI-09 … UI-11 | 5.2 |
| UI-12 | 5.3 |
| UI-13, AGT-03 … AGT-08, AGT-13 | 5.4 |
| UI-14, AGT-12, AGT-14, AGT-15 | 5.5 |
| UI-15, UI-16, CAP-31 … CAP-34 | 5.6 |
| UI-17, LOG-05, LOG-06 | 5.7 |
| UI-18 | 5.8 |
| UI-25 … UI-28 | 5.9, 6.5 |
| UI-19 | 5.10 |
| UI-20, FBK-01 … FBK-11a | 5.11, 5.12 |
| UI-21 … UI-24, CAP-25a | 6 |
| UI-29 | 7 |

## 9. Open design questions

| # | Question | Proposed default |
|---|---|---|
| Q-D1 | UI-02 lists 11 colours; Material 3 needs about 20 roles. OK to use the derived values in 2.1 (marked "derived")? | Yes, as listed |
| Q-D2 | Agent avatar colours: 8 fixed tint pairs, with darker pairs in dark theme? | Yes |
| Q-D3 | Inbox is a P2 feature but the nav bar has five slots from P1. Show Inbox in P1 with only the empty state, or ship P1 with four tabs and add Inbox in P2? | Four tabs in P1; Inbox appears in P2 |
| Q-D4 | Home "Quick switches": UI-11 says "MaterialSwitch row / chips". The mockup uses a 2 × 2 grid of switch cards. Accept, and which four? | Grid; Camera, Microphone, Gallery, Location |
| Q-D5 | Add agent: UI-13 says a 4-step indicator; section 6.2 numbers steps 0–5. Mapping in 5.4 (0 invisible, 5 on the same screen as 4). Confirm. | As mapped |
| Q-D6 | Report form: move the Bug/Feature toggle from item 8 (FBK-04) to the top, since it changes the title and labels above it? | Move to top |
| Q-D7 | Agents list: an Extended FAB above the bottom nav takes space on a list that holds at most 16 items. Keep the FAB, or use an "Add agent" button at the end of the list / in the toolbar? | Keep Extended FAB (as UI-12) |
| Q-D8 | Activity error rows use a coloured left stripe (UI-17). Keep, or use a leading error icon instead (clearer for colour-blind users and at a glance)? | Keep stripe **and** add an error icon in the avatar slot |
| Q-D9 | Home overflow ⋮ contents: Report a bug, Request a feature, Settings. Anything else (Pause for 1 hour)? | Those three |
| Q-D10 | Hero card TalkBack: announce every state change (Listening → Capturing → Listening) or only listener on/off and Tailscale changes? Captures already have their own notification. | Only on/off and Tailscale changes |
| Q-D11 | Setup prompt "Done" before the first call: allowed (agent kept, AGT-04) — show it outlined while waiting and filled once connected? | Yes |
| Q-D12 | QR sheet in dark theme: keep black-on-white for scanner reliability? | Yes |
| Q-D13 | Entry sheet action "Show this agent's activity" is long for one row at 390 dp. Shorten to "<Agent>'s activity"? | Yes |
| Q-D14 | Monospace font (spec Q7): the mockups assume bundled JetBrains Mono. If the system monospace is chosen instead, the setup prompt gets about 10 % wider. | Bundle (as Q7 default) |
| Q-D15 | Features descriptions in 5.8 are new copy. Review wording. | As written |

No conflicts with section 12 were found beyond Q-D3 (P1 vs P2 nav), Q-D5
(step count wording) and Q-D6 (field order), which are wording/ordering
choices rather than contradictions.
