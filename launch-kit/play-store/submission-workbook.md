# Google Play submission workbook

Status: prepared draft, not submitted. Match every answer to the final merged manifest, release candidate, SDK inventory and measured network behavior. Current and future listing text are separate to prevent roadmap claims reaching the store.

## Listing fields and assets

| Field | Prepared value | Release action |
|---|---|---|
| App name | BRIDGIT Device Manager (21 characters) | Confirm brand; maximum 30 characters |
| Short/full description | listing-current.json; future copy gated | Maximum 80 / 4,000 characters; validation script checks |
| Category | Tools, proposed | Confirm in Console |
| Developer | Sponic Garden, Inc. appears in repo copyright | Confirm publishing legal entity and verified account |
| Support email / website | Pending domain and verified mailbox | Required real reachable contact; do not use an invented address |
| Privacy policy | /privacy/ draft | Public HTTPS, no sign-in/geoblock, app/developer identified, actual retention, real contact |
| Icon | brand/play-icon-512.png | 512×512 RGBA PNG, ≤1 MiB; inspect before upload |
| Feature graphic | brand/play-feature-1024x500.png | 1024×500 RGB PNG; no fake download badges or endorsements |
| Phone screenshots | Storyboard supplied; captures pending | At least 2 real release screenshots; JPEG or 24-bit PNG, 320–3840 px; longest ≤2× shortest |
| Promotional video | reviewer-demo.md supplies script | Record actual build; no claim a storyboard is a demonstration |

## App content worksheet

Ads: proposed No, if the submitted app and SDKs still contain none. App access: restricted by private networking and pairing; provide complete own-device test instructions and isolated fixtures, not owner's credentials. Target audience: proposed adults 18+ configuring their own devices. Do not equate a baby-related use case with an app designed for children; assess actual content and marketing. Content rating: complete IARC questionnaire accurately in Console; no rating predetermined here. News, financial, government and health declarations: answer from actual functionality and marketing. A help station is not automatically exempt from health-policy assessment merely because copy says non-medical.

Data safety: use the per-data matrix. No analytics does not automatically mean no collection. Off-device transfer, end-to-end encryption exclusions and user-initiated sharing exceptions require actual evidence. Do not mark every camera operation shared or every encrypted transfer exempt by default. Keep final questionnaire answers and rationale alongside tested release revision.

Account deletion: current app has local agent records, no BRIDGIT online account creation. Local/offline records do not automatically trigger Play's online account deletion requirement. Provide clear local deletion controls regardless. If a future relay/viewer service creates app accounts, implement in-app and external deletion request paths and associated data removal before shipping it. The website delete-data page currently explains local cleanup; it is not a working hosted-account deletion endpoint.

## Foreground-service declaration drafts

Camera: "The owner starts an explicitly labeled device session while BRIDGIT is visible. Approved callers can request camera captures within the enabled grant. The phone shows ongoing status and Android's camera indicator. The owner can stop the session from BRIDGIT or its notification." Evidence: setup → capture → indicator → stop → revoked call fails. Amend for actual streaming implementation only.

Microphone: "The owner enables audio access for approved requests. Each short recording is bounded to the requested duration and visible on the device. The owner can stop access." Do not claim immediate recording cancellation until implemented. Evidence includes interruption, microphone privacy switch, notification denial and response cleanup.

Special use: "BRIDGIT maintains a private-network command listener during a session the owner deliberately enables, so the owner's remote agent can reach the phone without the phone's screen remaining open. Deferring the listener would prevent the expected remote command from reaching the device. The owner can stop it at any time." This is a proposed justification, not approved eligibility. Measure whether narrower sanctioned APIs meet the actual use. An idle indefinitely running listener can be questioned by review.

Battery exemption: justify only when core functionality is adversely affected and the requested path is eligible; do not bundle it into generic setup. Broad photo permission: either remove it from Play manifest or submit the real core-use justification; selected mode support alone does not approve READ_MEDIA_IMAGES.

## Release gates

- [ ] Owner accepts v3 scope and reconciles v2 decisions.
- [ ] Target API 36 or current submission requirement; minimum OS claims match build.
- [ ] Privacy/disclosure/consent and scoped grants implemented.
- [ ] Permission and notification loss stops active capture; Stop/revoke tests pass.
- [ ] CameraX native libraries checked for ELF/zip 16 KiB compatibility using final AAB.
- [ ] Signed release AAB, versionCode, signing certificate and Play delivery validated.
- [ ] Existing signing key continuity selected before open/production rollout if desired.
- [ ] Real screenshots and reviewer demo match the submitted build.
- [ ] Public policy/support pages reachable without Sites private sign-in.
- [ ] App access instructions successfully repeated on a clean isolated review device/network.
- [ ] Data safety answers reconcile all optional modules and SDKs.
- [ ] Feature-use tallies (app `core/Usage.kt`, website `usage.mjs`, on by default with a Settings switch): declare App activity › App interactions as collected, not shared, for analytics; check whether Play treats an opt-out switch as "optional".
- [ ] Required testing and developer identity/account checks complete.
- [ ] Applicable personal accounts: 12 testers continuously opted in for 14 days, then production-access application.
- [ ] Review Console policy status and unresolved declarations; no bypasses.

Sources checked 28 October 2026: [listing](https://support.google.com/googleplay/android-developer/answer/9859152?hl=en), [assets](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en), [review](https://support.google.com/googleplay/android-developer/answer/9859455?hl=en), [FGS](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en), [privacy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en), [data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en), [signing](https://support.google.com/googleplay/android-developer/answer/9842756), [testing](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).

Approved public name: BRIDGIT Device Manager for Android. Play title: BRIDGIT Device Manager (21 characters), within the 30-character limit. Domain: https://bridgit.cc. The tagline remains “Turn any Android device into an agentic appliance.” Existing Android package and API identifiers stay compatible.
