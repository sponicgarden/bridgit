# Reviewer access and demonstration

Do not paste real agent keys, the owner's tailnet identity or private phone hostname into Play Console or the website. Provision an isolated review phone and network, a dedicated agent fixture with revocable grants and nonpersonal media. Before submission reproduce the entire flow on a clean device without developer-only hooks. The reviewer must be able to test real functionality; a local demo alone is insufficient. If private-network access cannot be made practical within review rules, resolve it before submission rather than supply unusable instructions.

## App access text to finalize

"BRIDGIT controls the reviewer's own Android phone through an explicitly paired API client. Install Tailscale and sign in to the isolated review environment using the access instructions supplied securely in Console. Open BRIDGIT, review disclosure, grant only the capabilities you will test, and pair the dedicated review client. The supplied test client uses the displayed pairing code to receive a short-lived test setup credential. Use the review client controls for Health, Camera snapshot, Audio clip, Selected photo and Stop. You can repeat pairing without contacting the developer. No production device or personal data is needed."

This text is a template. The isolated network/client, secure credentials, account availability, code behavior and exact button labels must be created/tested before it is submitted. Prefer supplying a documented local review client and owner-enrolled isolated environment rather than adding a security-bypass mode to release.

## 90-second recording script

0–15s: launch actual release build; display app version; show disclosure, owner grant and denied-by-default toggles. 15–30s: pair a dedicated named agent, hide key values in the recording, demonstrate health and capability readiness. 30–45s: owner enables camera; remote request captures nonpersonal fixture; show persistent status and Android indicator. 45–60s: short audio recording and selected fixture photo; show permission-specific behavior. 60–75s: Stop/cancel, revoke grant; remote request now fails. 75–90s: notification denied/channel blocked case, clear failure explanation and privacy link. Do not edit away error states that matter to review.

For future runtime add: agent proposes → phone permission diff → owner activates → agent disconnects → local button/timer executes → stop persists. Streaming requires an additional actual viewer-session video; help mode requires delivery versus human acknowledgment and failure demonstration.
