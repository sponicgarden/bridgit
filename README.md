# Almusca Device Bridge (Android)

A small sideloaded app for Rahul's Pixel 10 Pro XL. It lets the agent Almusca
take a photo, record a short audio clip, or fetch a gallery photo, over
Tailscale only, with a token, and with every request logged on the phone.
It implements `android-companion-app-spec.pdf` v1.0 (26 Oct 2026).

Decisions on the spec's open questions (26 Oct 2026):

| Question | Decision |
|---|---|
| 1. Listener availability | **Always on**: one foreground service with a permanent notification |
| 2. Gallery access | **Full** photo access (selected-photos mode still works and is reported) |
| 3-7 | Spec defaults: snapshots not saved to the gallery, 120 s mic cap, rear camera at 2048 px, 1,000-entry log kept on the phone |

## Install

1. Download `almusca-device-bridge-1.0.0.apk` from the private GitHub release
   (`rahuliofam/finleg`, tag `device-bridge-v1.0.0`), or build it (below).
2. Open it on the Pixel and allow installs from that source. Let Play Protect scan.
3. Open **Device Bridge**. The listener and all three capabilities start **off**.

Updates install over the top as long as they are signed with the same key.

## Pairing (once, with the phone in hand)

1. Tailscale on, phone unlocked, open Device Bridge.
2. **Grant permissions**: notifications, camera, microphone, photos.
3. **Pair agent**: the app shows a 6-digit code, the token, and a QR code for
   5 minutes. The listener turns on.
4. The agent fetches its token with the code (only works during that window,
   5 wrong codes close it):
   `curl -X POST http://<phone-tailscale-ip>:8734/v1/pair -d '{"code":"123456"}'`
   and stores the token in its credential store. Don't paste the token into chat.
5. Switch on the capabilities you want. Optionally tap
   **Allow listener to run in background** (battery exemption).
6. Smoke test: one snapshot, one 5-second clip, one gallery fetch. Check the
   three audit entries on the phone.

**After a reboot, or if Android stops the app, open it once.** Android only
lets a foreground service use the camera and microphone if it was started while
the app was on screen, so nothing can start the listener with camera/mic access
in the background. If the listener restarts itself in the background, gallery
and health keep working and camera/mic return `service_start_blocked` until the
app is opened.

## API

Base URL `http://<phone-tailscale-ip>:8734/v1`. Every call except `/pair` needs
`Authorization: Bearer <token>`. Every response has a `request_id` that matches
an audit entry. Client timeout: 30 s (mic: duration + 30 s).

| Endpoint | Body / query | Returns |
|---|---|---|
| `POST /pair` | `{"code":"123456"}` | `{"token"}`, once per pairing window |
| `GET /health` | | version, uptime, battery, charging, bind address |
| `GET /capabilities` | | per capability: toggle, permission, `service_ready`; gallery `access_mode` |
| `POST /camera/snapshot` | `{"camera":"rear"\|"front","flash":false,"max_width_px":2048}` (`null` = full size) | JPEG as `data_base64` + size, dimensions |
| `POST /mic/record` | `{"duration_seconds":1-120}` (whole numbers only) | M4A (AAC, 44.1 kHz mono) as `data_base64` |
| `GET /gallery/recent?limit=20` | limit 1-100 | photo metadata, newest first, plus `access_mode` |
| `GET /gallery/photo/{id}?max_width_px=1600` | optional downscale | same envelope as snapshot, `source: "gallery"` |
| `GET /audit?limit=50` | limit 1-1000 | newest audit entries |

Errors: `{"error","message","request_id"}` with the spec's codes:
`unauthorized` 401, `capability_disabled` / `permission_denied` 403,
`sensor_busy` / `device_locked` 409, `invalid_parameter` 422,
`payload_too_large` 413 (with `photo_id`), `photo_not_found` 404,
`rate_limited` 429, `service_start_blocked` 503, `capture_timeout` 504.
Also `forbidden_source` 403 (caller not in 100.64.0.0/10) and
`pairing_closed` 403.

Limits: 10 capture requests per minute and 5 failed sign-ins per minute per
caller, then 429. Responses over 12 MB of base64 are refused.

## Security model

- Listens only on the phone's Tailscale address (100.64.0.0/10), rebinding
  when it changes, and rejects callers outside that range. If Tailscale is off,
  nothing listens.
- Token: 256-bit random, stored only as a salted SHA-256 hash. **Rotate token**
  invalidates the old one at once and opens a new pairing window. **Unpair**
  deletes it and stops the listener.
- Toggles can only be changed on the phone. All start off.
- Captures run in the foreground service: the notification changes to
  "capturing" and Android's green indicator shows. Temporary files are deleted
  after every request. Agent photos never go into the gallery.
- No analytics, no third-party SDKs other than Ktor (HTTP), CameraX and ZXing (QR).
- App backup is disabled, so the token hash and log never leave the phone.

Permissions beyond the spec's list: `READ_MEDIA_VISUAL_USER_SELECTED` (to see
the "selected photos" grant), `FOREGROUND_SERVICE_SPECIAL_USE` (keeps the
listener up before camera/mic are granted), `ACCESS_NETWORK_STATE`, and
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the optional exemption prompt).

## Build

Needs JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Signing reads `android/keystore.properties` (gitignored):

```
storeFile=/Users/otter/.config/almusca-device-bridge/release.jks
passwordFile=/Users/otter/.config/almusca-device-bridge/keystore.pass
keyAlias=devicebridge
```

Signing certificate SHA-256:
`6bbd482d18efdc58ca28de8358405602ebb0405b479cabd36b37ff8242042f61`.
**Back up `release.jks` and its password** (Bitwarden). Without that key, an
update means uninstalling, which wipes pairing and the audit log.

Debug builds install alongside as `com.almusca.devicebridge.debug` and include
an ADB-only test hook (`DebugSetupReceiver`, protected by the `DUMP`
permission only the ADB shell holds) that sets toggles and opens a pairing
window without tapping the screen. Release builds don't contain it.

## Tested on the Pixel (26 Oct 2026, phone locked)

Passed over Tailscale from the Mac: health, capabilities, 401 for missing and
wrong tokens (logged), one-time pairing, gallery list with full access, photo
fetch with downscale (800 px JPEG) and full size, `photo_not_found`,
`invalid_parameter` (121 s, 2.5 s, unknown camera), `capability_disabled`,
both rate limits, audit read, and connection refused on the Wi-Fi address.

Not yet tested: a real camera snapshot and mic clip. Both need the phone
unlocked and the app opened once (spec milestones M3/M4); that's part of the
pairing session above.
