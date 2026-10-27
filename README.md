# Bridget (Android)

Copyright 2026 Sponic Garden, Inc. Licensed under the [Apache License 2.0](LICENSE).

A small sideloaded app for Rahul's Pixel 10 Pro XL. It lets any AI agent you
pair with it (Muse, Grok, or others) take a photo, record a short audio clip,
or fetch a gallery photo. It answers only over Tailscale, each agent has its
own key, and every request is logged on the phone with the agent's name.

Package `net.finleg.bridget`. Version **1.3.0** (phase P1a of
[`docs/bridget-spec-v2.md`](docs/bridget-spec-v2.md), the source of truth).
1.3.0 is not compatible with 1.2: keys, the log and `/v1/audit` changed, and
nothing is migrated (spec decision D20). Uninstall 1.2.0, install 1.3.0, and
add each agent again.

**For agents:** call `GET /v1/guide` (written for you, generated live) and
`GET /v1/openapi.json`. [`docs/scenarios.md`](docs/scenarios.md) lists what
you can do with Bridget.

## Install

1. Get the APK from the private GitHub release (`sponicgarden/bridget`, tag
   `bridget-v1.3.0`), or build it (below). Install it with
   `adb install -r app-release.apk`, or open it on the Pixel.
2. Open **Bridget**. First run explains the app, asks for notifications and
   checks Tailscale. Every feature starts **off**.

Updates from 1.3.0 on install over the top, as long as they are signed with
the same key.

## Adding an agent (under a minute)

1. **Agents → Add agent** (or **Add your first agent** on first run).
2. Type a name or tap a suggestion (Muse, Grok, Claude, Codex, ...).
3. Pick how it connects: **Tailscale (direct)** or **Tailscale, userspace
   host** (like Grok's box; that prompt includes the `bridget` helper).
4. Bridget checks itself (listener, Tailscale address, port), then shows the
   **setup prompt**. Tap **Copy prompt** and paste it into the agent. The key
   is in the prompt and is shown only once (screenshots of this screen are
   blocked). **Share**, **QR** and **Copy key only** are there too.
5. The screen waits for the agent's first call and then shows "connected".

If the app is killed before you copy, reopening it within 30 minutes returns
to the prompt (the key is kept encrypted with an Android Keystore key until
then). After that, use **Rotate key** on the agent's page.

**Pair with code** (in the Add agent menu) is the older way: the phone shows
a 6-digit code for 5 minutes and the agent calls `POST /v1/pair
{"code":"..."}` once to receive its key.

Agent page: **Rotate key** (old key stops at once and gets
`unauthorized` with `"reason":"key_rotated"` for 7 days), **Remove**
(`"reason":"agent_removed"`), **Developer** switch, notes, recent activity.

Keys are stored in Bitwarden as **"Bridget — <Name> agent key"** (login
username = agent name, password = key, URL = base URL).

**After a reboot**, Bridget starts listening at the first unlock (Android
sends its boot signal only then), with camera and mic off until you open
the app once. Home shows a **Fix now** banner for that.

## API (for agent builders)

Base URL `http://pixel-10-pro-xl.tail9c9221.ts.net:8734/v1` (debug builds:
port **8735**). Every call except `GET /v1/tools/bridget` and `POST /v1/pair`
needs `Authorization: Bearer brg_...`. Every response has a `request_id`
(also `X-Request-Id`) that matches its log entry. Full reference:
`GET /v1/guide`, `GET /v1/openapi.json`, spec section 7 and Appendix A.

| Endpoint | What |
|---|---|
| `GET /health` | Status, calling agent, version, uptime, battery, address |
| `GET /capabilities` | Per capability: `enabled`, `ready`, `blocked_reason`, details |
| `GET /guide`, `GET /openapi.json` | Self-description, generated from the code |
| `GET /tools/bridget` | The command-line helper (no key needed) |
| `POST /camera/snapshot` | `{"camera":"rear"\|"front","flash":false,"max_width_px":2048}`, or `size=agent\|thumb\|large\|original`; `raw=1` for JPEG bytes |
| `POST /mic/record` | `{"duration_seconds":1-120}` |
| `GET /gallery/recent?limit=20` | Newest images, metadata only |
| `GET /gallery/photo/{id}` | One image; `size=`, `raw=1` |
| `GET /image/last?type=any\|screenshot\|photo` | Newest image, sized for AI vision (1568 px, q85); `since=&wait=55` waits for the next one |
| `POST /screen/capture` | What's on screen now, same sizing, nothing saved. Needs More > Access > Screenshots (Android Accessibility, "Bridget screenshots") and the Screenshots switch in Features |
| `GET /log` | The log, with filters; `order=asc&since=<id>&wait=55` follows it live |
| `GET /log/{id}`, `/log/stats`, `/log/export?format=jsonl\|csv` | One entry, stats, export |
| `POST /log` | `{"message":"..."}`: a note in the agent channel |
| `GET /audit` | Deprecated alias of `/log?channel=audit` (new entry shape) |
| `POST /pair` | `{"code":"123456"}` during a pairing window |

Errors are `{"error","message","request_id"}` with the codes in spec
Appendix C. POSTs accept `Idempotency-Key`. Limits: 5 failed sign-ins per
minute per address (valid keys are never blocked), 10 captures per minute
per agent and 30 per address, 4 open `wait` requests per agent.

The log setting **Agents can read: all entries / only their own** (Settings
→ Privacy) applies to every log endpoint. Developer agents always see all.

### Use Bridget from Claude Code over MCP

`POST /v1/mcp` speaks MCP (Streamable HTTP, JSON replies, no SSE, no
session id) with the agent's normal key. Put this in the project's
`.mcp.json` and export the key as `BRIDGET_KEY` (never paste it into the
file):

```json
{
  "mcpServers": {
    "bridget": {
      "type": "http",
      "url": "http://<phone>:8734/v1/mcp",
      "headers": { "Authorization": "Bearer ${BRIDGET_KEY}" }
    }
  }
}
```

`<phone>` is the MagicDNS name or Tailscale IP from Home (debug builds: port
8735). Every endpoint the agent may use is a tool named from its path:
`camera_snapshot`, `image_last`, `gallery_photo` (`id` argument),
`interactions_send`, `interactions_get` (`wait` up to 55), `speaker_say`, …
Left out: `/mcp`, `/pair`, `/tools/bridget`, `/openapi.json`; developer-only
tools appear only for developer agents. Tools whose switch is off are still
listed, with a "switched off" note, and fail with `capability_disabled`.
Images and clips come back as MCP image/audio content; errors as
`isError: true` with the usual error JSON. Side-effecting tools take an
`idempotency_key` argument. `speaker_play` takes a `url` only (send raw audio
bytes to the HTTP endpoint). Each tool call is checked like a direct call
(switch, developer, capture and rate limits) and gets its own log entry for
the real endpoint, marked `via: mcp`. Requests with a browser `Origin`
header are refused (403); batches get 400. The whole JSON-RPC message is
capped at 2 MB, so base64 images sent with `interactions_send` or
`feedback_file` must be smaller over MCP than over HTTP.

### Callbacks

Rahul can give an agent a **Callback URL** in its agent screen (tailnet
addresses only, unless Settings > Allow internet callbacks is on; then a public
`https://` URL works too). Bridget then POSTs every event for that agent as
`{"event": {id, type, at, data}, "agent": "<name>"}`, retrying at 10 s, 60 s and
5 min (`GET /v1/events` always has them). The callback secret (`cbs_…`) is shown
once when the URL is saved. Check the signature like this:

```python
import hmac, hashlib, time
def verify(secret: str, headers, raw_body: bytes) -> bool:
    ts = headers["X-Bridget-Timestamp"]
    want = "sha256=" + hmac.new(secret.encode(), ts.encode() + b"." + raw_body, hashlib.sha256).hexdigest()
    fresh = abs(time.time() - int(ts)) <= 300  # also drop X-Bridget-Event-Id values you've already seen
    return fresh and hmac.compare_digest(want, headers["X-Bridget-Signature"])
```

`POST /v1/callback/test` sends a signed `callback.test` event right away and
returns `{delivered, http_status, host}` (502 `callback_failed` if the
receiver didn't answer 2xx).

## Security model

- Listens only on the phone's Tailscale addresses (IPv4 100.64.0.0/10 and
  IPv6 fd7a:115c:a1e0::/48), rebinding when they change, and rejects callers
  outside those ranges. If Tailscale is off, nothing listens.
- Keys: `brg_` + 43 base64url characters (256 random bits), one per agent,
  stored only as salted SHA-256 hashes. The prefix lets secret scanners
  catch leaked keys.
- Switches can only be changed on the phone. All start off.
- Captures need Bridget's notifications on. Each capture names the agent in
  a notification and Android's indicator shows. Agent photos never go into
  the gallery.
- The log (SQLite on the phone) never stores keys, request headers, media or
  long texts. No analytics, no third-party SDKs other than Ktor (HTTP),
  CameraX, AndroidX/Material and ZXing (QR).
- App backup is disabled. The only outgoing call is the update check (from 1.4.0): HTTPS to
  Sponic's public R2 host, verified before Android asks you to install.

## Crash reports

Crashes and freezes (ANRs) go to Sentry, project `bridget-android` in the
`sponic-gardens-inc` org (decision D32). Sent: the error and stack, app version,
Android version and basic device facts (model, battery, memory). Not sent:
screenshots, photos, keys, request data, install ids or sessions; the project
drops the IP address and the city inferred from it. Turn it off on
the phone under Settings > Privacy > Send crash reports.

## Build

Needs JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

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
key signs Bridget; the folder and item keep the original name. Without it, an
update means uninstalling, which wipes the agents and the audit log.

Icon (1.4.0): a phone outline with a mint "B" ribbon and cursor, as an adaptive icon
(`res/mipmap-*/ic_launcher_foreground.png` on `#022124`, plus a monochrome layer for themed
icons), generated by `tools/make-launcher-icon.py` from the owner's artwork.

Publishing a release (1.4.0 on): `tools/publish-apk.sh --notes "..."` builds, signs, checks the
certificate, uploads the APK and `latest.json` to Sponic's public R2 (`bridget/`), verifies the
download and creates the GitHub release. `--dry-run` checks everything without uploading.

Debug builds install alongside as `net.finleg.bridget.debug`, listen on port
8735, and include an ADB-only test hook (`DebugSetupReceiver`, protected by the `DUMP` permission
only the ADB shell holds) that sets toggles and adds a test agent without
tapping the screen. Release builds don't contain it.

## Tests

- Unit tests (JVM): `./gradlew testDebugUnitTest`.
- Device acceptance (TST-03), against the debug build on the Pixel:
  `tools/acceptance.sh --apk app/build/outputs/apk/debug/app-debug.apk`.
  It writes `docs/acceptance/<date>-<version>-debug.md` with PASS, FAIL or
  UNVERIFIED per check. Camera and mic checks need the phone unlocked.

## Next (specs, not built)

**Master spec: [`docs/bridget-spec-v2.md`](docs/bridget-spec-v2.md)** (27 Oct 2026) covers everything below and more, with requirement IDs, API shapes, screens, roadmap and open questions. The files below are kept for history.

- `docs/spec-per-agent-permissions.md`: separate switches per agent.
- `docs/spec-flashlight.md`: agents can turn the flashlight on (auto-off).
- `docs/spec-screenshot.md`: screenshot on request, approved on the phone
  once, for 1 hour or for 16 hours.
- `docs/spec-access-screen.md`: an Access tab listing every Android setting
  Bridget needs, with one-tap links straight to each switch.
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
