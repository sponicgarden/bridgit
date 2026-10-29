# BRIDGIT Device Manager for Android

Copyright 2026 Sponic Garden, Inc. Licensed under the [Apache License 2.0](LICENSE).

A small sideloaded app for Rahul's Pixel 10 Pro XL. It lets any AI agent you
pair with it (Muse, Grok, or others) take a photo, record a short audio clip,
or fetch a gallery photo. It answers only over Tailscale, each agent has its
own key, and every request is logged on the phone with the agent's name.

Package `net.finleg.bridget`. Built from `docs/spec-v1.0.pdf` (26 Oct 2026,
written as "Almusca Device Bridge"), with these changes:

| Topic | Decision |
|---|---|
| Name | **Bridgit** (26 Oct 2026) |
| Agents | **Any number of named agents** (max 8), each with its own key, instead of one agent |
| Listener availability | **Always on**: one foreground service with a permanent notification |
| Gallery access | **Full** photo access (selected-photos mode still works and is reported) |
| Spec questions 3-7 | Spec defaults: snapshots not saved to the gallery, 120 s mic cap, rear camera at 2048 px, 1,000-entry log kept on the phone |

**For agents:** [`docs/scenarios.md`](docs/scenarios.md) lists everything you can do with Bridgit, with exact calls and what works today vs. what's planned.

## Marketing website

The BRIDGIT marketing website lives in [`website/`](website/README.md). It includes the landing page, appliance concepts, developer information, support and policy drafts. Run `npm --prefix website run dev` for a local preview. Domain connection and deployment are pending.

## Install

1. Download the APK from the private GitHub release (`sponicgarden/bridgit`,
   tag `bridget-v1.2.0`), or build it (below).
2. Open it on the Pixel and allow installs from that source. Let Play Protect scan.
3. Open **Bridgit**. The listener and all three capabilities start **off**.

Updates install over the top as long as they are signed with the same key.

## Adding an agent (phone unlocked, in hand)

1. Tailscale on, open Bridgit, tap **Grant permissions** (notifications,
   camera, microphone, photos).
2. Tap **Add agent**, type its name (for example `Muse`). The app shows a
   6-digit code, the key and a QR code for 5 minutes, and the listener turns on.
3. The agent fetches its key with the code. This only works during that
   window, and 5 wrong codes close it:
   `curl -X POST http://pixel-10-pro-xl.tail9c9221.ts.net:8734/v1/pair -d '{"code":"123456"}'`
   returns `{"token","agent","base_url"}`. Store the key in Bitwarden, never in chat.
4. Switch on the capabilities you want, and optionally tap **Allow listener to
   run in background**.

Each agent row has **Rotate key** (the old key stops working at once and a new
pairing window opens) and **Remove** (deletes that agent's key; removing the
last agent stops the listener). The toggles apply to all agents.

Paired agents (26 Oct 2026): **Muse, Grok, Claude, Codex**. Keys in Bitwarden:
**"Bridget — Muse agent key"**, **"Bridget — Grok agent key"**, **"Bridget —
Claude agent key"**, **"Bridget — Codex agent key"** (login username = agent
name, password = key, URL = base URL).

**After a reboot, or if Android stops the app, open it once.** Android only
lets a foreground service use the camera and microphone if it was started
while the app was on screen. If the listener restarts itself in the
background, gallery and health keep working, and camera/mic return
`service_start_blocked` until the app is opened.

## API (for agent builders)

Base URL `http://pixel-10-pro-xl.tail9c9221.ts.net:8734/v1` (the phone's
Tailscale name; the IP is 100.94.27.79). Every call except `/pair` needs
`Authorization: Bearer <key>`. Every response has a `request_id` that matches
an audit entry. Client timeout: 30 s (mic: duration + 30 s). Nothing in the
API is tied to a particular agent; any HTTP client works.

| Endpoint | Body / query | Returns |
|---|---|---|
| `POST /pair` | `{"code":"123456"}` | `{"token","agent","base_url"}`, once per pairing window |
| `GET /agent/profile` | | calling agent’s id, name, icon and wordmark (no credentials) |
| `POST /agent/profile` | optional `name`, `icon_base64`, `wordmark_base64` | updated calling agent profile |
| `GET /health` | | app, calling agent, version, uptime, battery, charging, bind address |
| `GET /capabilities` | | per capability: toggle, permission, `service_ready`; gallery `access_mode` |
| `POST /camera/snapshot` | `{"camera":"rear"\|"front","flash":false,"max_width_px":2048}` (`null` = full size) | JPEG as `data_base64` + size, dimensions |
| `POST /mic/record` | `{"duration_seconds":1-120}` (whole numbers only) | M4A (AAC, 44.1 kHz mono) as `data_base64` |
| `GET /gallery/recent?limit=20` | limit 1-100 | photo metadata, newest first, plus `access_mode` |
| `GET /gallery/photo/{id}?max_width_px=1600` | optional downscale | same envelope as snapshot, `source: "gallery"` |
| `GET /image/last?type=any\|screenshot\|photo` | optional `max_edge_px` (default 1568), `quality` (default 85), `raw=1` | the single newest image of that type, resized for an agent to read |
| `GET /audit?limit=50` | limit 1-1000 | newest audit entries (all agents) |

### Agent identity and branding

For agents featured on our home page, we already have optimized logo assets:
Grok, Claude, ChatGPT, Muse, OpenCode, OpenRouter and Gemini. Reuse these assets
for the default identity in interactions and confirmations; custom artwork can
override them. Asset provenance is recorded in `website/brand/agent-sources.json`.

After pairing, an agent can set its own display name and upload its branding:

```http
POST /v1/agent/profile
Authorization: Bearer <agent key>
Content-Type: application/json

{
  "name": "Muse",
  "icon_base64": "<standard base64 PNG bytes>",
  "wordmark_base64": "<standard base64 PNG bytes>"
}
```

The response contains `id`, `name`, `icon_base64`, `wordmark_base64` and
`request_id`. `GET /v1/agent/profile` reads the same profile. The token selects
the agent: these routes cannot edit another agent or change access permissions.
Names follow the existing rules (1–24 ASCII letters, numbers, spaces, hyphens
or underscores; first character alphanumeric; unique ignoring case).

Omitted fields stay unchanged; JSON `null` clears an image. Both images are
optional PNGs, up to 128 KiB decoded each. Icons are at most 512 × 512 pixels;
wordmarks at most 2048 × 512. Send standard base64 without whitespace or a
`data:` prefix. URLs, SVG and unknown fields are rejected. Profile request
bodies are capped at 384 KiB while reading, including chunked requests.
Invalid updates leave the profile unchanged. Branding bytes are stored in
app-private storage and omitted from audit entries. Profiles survive restarts
and key rotation, and are deleted when the agent is removed.

The app shows the identity in the agent list, at the top during captures, and
on key-rotation/removal confirmations. Capture notifications show the icon and
name. The name stays visible alongside the wordmark and provides the fallback
when no images were supplied. Agent artwork is self-supplied, not a verified
identity badge. BRIDGIT attribution and device controls remain visible.
Future interaction and action-approval screens must repeat this identity at
the top and on the confirmation screen; per-request approval screens are not
implemented in the current capture API.

### Fastest image: `GET /v1/image/last`

One call returns the newest image. `type=screenshot` means anything in a
Screenshots folder, `type=photo` means camera photos (`DCIM/Camera`), and
`type=any` (default) means any image, including WhatsApp and downloads.
"Newest" means most recently added to the phone.

It's sized for AI vision by default: long edge 1568 px (what Claude-class
models read at full detail), JPEG quality 85. Small text stays legible; a
screenshot comes to about 100 KB and a photo about 250 KB. Measured on the
Pixel over Tailscale: 0.4-0.8 s per call.

With `raw=1` the body is the JPEG itself (no base64, about 25% smaller) and
the details come in headers: `X-Bridget-Id`, `-Type`, `-Taken-At`,
`-Added-At`, `-Width`, `-Height`, `-Album`. Without it, the JSON envelope
matches the gallery photo response plus `type`, `added_at`,
`original_width_px` and `original_height_px`.

```bash
curl -H "Authorization: Bearer $KEY" -o last.jpg \
  "http://pixel-10-pro-xl.tail9c9221.ts.net:8734/v1/image/last?type=screenshot&raw=1"
```

Errors: `{"error","message","request_id"}` with the spec's codes:
`unauthorized` 401, `capability_disabled` / `permission_denied` 403,
`sensor_busy` / `device_locked` 409, `invalid_parameter` 422,
`payload_too_large` 413 (with `photo_id`), `photo_not_found` 404,
`rate_limited` 429, `service_start_blocked` 503, `capture_timeout` 504.
Also `forbidden_source` 403 (caller not in 100.64.0.0/10) and
`pairing_closed` 403.

Limits: 10 capture requests per minute and 5 failed sign-ins per minute per
calling device, then 429. Responses over 12 MB of base64 are refused.

## Security model

- Listens only on the phone's Tailscale address (100.64.0.0/10), rebinding
  when it changes, and rejects callers outside that range. If Tailscale is off,
  nothing listens.
- Keys: 256-bit random, one per agent, stored only as salted SHA-256 hashes.
- Toggles can only be changed on the phone. All start off.
- Captures run in the foreground service. The notification names the agent
  ("Grok is taking a photo") and Android's green indicator shows. Temporary
  files are deleted after every request. Agent photos never go into the gallery.
- No analytics, no third-party SDKs other than Ktor (HTTP), CameraX and ZXing (QR).
- App backup is disabled, so key hashes and the log never leave the phone.

Permissions beyond the spec's list: `READ_MEDIA_VISUAL_USER_SELECTED` (to see
the "selected photos" grant), `FOREGROUND_SERVICE_SPECIAL_USE` (keeps the
listener up before camera/mic are granted), `ACCESS_NETWORK_STATE`, and
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the optional exemption prompt).

## Build

Needs JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Each debug or release build automatically advances the patch version and Android
`versionCode`, continuing from `1.6.0`. The last allocated version lives
in `apk-version.properties`; keep that file when sharing or committing builds.
`clean` does not reset it. Sync and help do not allocate a version; tasks that
process the app manifest (including some tests) do. Failed builds may leave gaps.
Concurrent builds in this checkout reserve numbers under a file lock. Separate
checkouts must carry forward the latest counter before building updates.

Signing reads `keystore.properties` (gitignored):

```
storeFile=/Users/otter/.config/almusca-device-bridge/release.jks
passwordFile=/Users/otter/.config/almusca-device-bridge/keystore.pass
keyAlias=devicebridge
```

Signing certificate SHA-256:
`6bbd482d18efdc58ca28de8358405602ebb0405b479cabd36b37ff8242042f61`.
Backup: Bitwarden item **"Almusca Device Bridge — Android release keystore"**
(password in the notes, `release.jks` attached; verified 26 Oct 2026). That
key signs Bridgit; the folder and item keep the original name. Without it, an
update means uninstalling, which wipes the agents and the audit log.

Icon: adaptive vector (`res/drawable/ic_launcher_*`), a bridge with a beacon,
plus a monochrome layer for themed icons.

Debug builds install alongside as `net.finleg.bridget.debug` and include an
ADB-only test hook (`DebugSetupReceiver`, protected by the `DUMP` permission
only the ADB shell holds) that sets toggles and adds a test agent without
tapping the screen. Release builds don't contain it.

## Tests on the Pixel

26 Oct 2026, over Tailscale from the Mac. Passed: health, capabilities, 401
for missing and wrong keys (logged), one-time pairing, four agents with
separate keys (each checked via the MagicDNS name) and per-agent audit names,
gallery list with full access, photo fetch with downscale and full size,
`photo_not_found`, `invalid_parameter` (121 s, 2.5 s, unknown camera, bad
type), `capability_disabled`, both rate limits, audit read, connection refused
on the Wi-Fi address, **a real rear-camera snapshot** (1024 px, 2.2 s), **a
real 5-second mic clip** (AAC, 5.9 s), and `image/last` for all three types
(newest item matches MediaStore).

## Next (specs, not built)

**Master spec: [`docs/bridgit-spec-v2.md`](docs/bridgit-spec-v2.md)** (27 Oct 2026) covers everything below and more, with requirement IDs, API shapes, screens, roadmap and open questions. The files below are kept for history.

- `docs/spec-per-agent-permissions.md`: separate switches per agent.
- `docs/spec-flashlight.md`: agents can turn the flashlight on (auto-off).
- `docs/spec-screenshot.md`: screenshot on request, approved on the phone
  once, for 1 hour or for 16 hours.
- `docs/spec-access-screen.md`: an Access tab listing every Android setting
  Bridgit needs, with one-tap links straight to each switch.
- `docs/spec-agent-inbox.md`: interactions (one-way notices and questions,
  optionally spoken aloud), files sent to the phone, and things Rahul shares
  to an agent, all in a new Inbox screen. Works for
  agents without webhooks (long polling).

## License

Copyright 2026 Sponic Garden, Inc.

Licensed under the Apache License, Version 2.0 (the "License"); you may not
use this project except in compliance with the License. You may obtain a copy
of the License in [LICENSE](LICENSE) or at
http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable law
or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the specific language
governing permissions and limitations under the License.

Third-party components keep their own licences: Ktor (Apache 2.0), CameraX
and AndroidX (Apache 2.0), Material Components (Apache 2.0), ZXing (Apache
2.0), Kotlin coroutines (Apache 2.0).

## BRIDGIT naming and compatibility

The repository and public brand are now **Bridgit / BRIDGIT**, at https://github.com/sponicgarden/bridgit and https://bridgit.cc. Android package `net.finleg.bridget`, existing `X-Bridget-*` headers, pairing identifiers, signing-key and agent-key vault item names, configuration under `~/.config/bridget`, and historical release tags remain unchanged so existing installations and agents keep working. Use `tools/bridgit`; `tools/bridget` remains a compatibility entry point.

Cloudflare deployment configuration lives in `website/wrangler.jsonc` and pins the Wingsiebird account. See [website deployment](website/README.md#cloudflare).

New CLI configuration uses `~/.config/bridgit/env` and `BRIDGIT_KEY`, `BRIDGIT_HOST`, `BRIDGIT_PORT` (or `BRIDGIT_ENV` for an explicit config path). The old names remain accepted; new names take precedence.
