# Spec: the Access screen — one tap to each Android setting (Bridget 1.3)

Status: proposed, 26 Oct 2026. Not built. Deep links checked on Rahul's Pixel
10 Pro XL (Android 17) with `cmd package resolve-activity`.

## Why

Some features need a switch that only Rahul can flip in Android Settings,
often three or four screens deep. This screen lists every such switch,
shows whether it's on, and opens the exact page with one tap. Bridget then
detects the change by itself when Rahul comes back.

## The screen

A new **Access** tab: **Inbox | Access | Settings**.

```
Access                                        5 of 9 on
--------------------------------------------------------------
Camera                         On
Microphone                     On
Photos                         Full access
Notifications                  On
--------------------------------------------------------------
Location                       Off          [Allow]
  Where the phone is, when an agent asks. Never tracked.
App usage                      Off          [Turn on]
  Screen time per app, and which app is open now.
Do Not Disturb control         Off          [Turn on]
  Lets agents check and switch Do Not Disturb.
Modify system settings         Off          [Turn on]
  Lets agents change screen brightness.
Run in background              Off          [Allow]
  Stops Android from pausing the listener.
--------------------------------------------------------------
Not needed: Read all notifications (the Muse app already does this)  [Show]
```

- Each row has a plain-language line saying what it unlocks, and a status
  chip: **On**, **Off**, **Partly** (for example approximate location only),
  or **Blocked** (Android's "restricted settings", see section 4).
- **One button per row** opens the right place (section 2). When Rahul comes
  back, the row updates on its own; Bridget re-checks every time the screen
  is shown.
- **Show me how** under each button opens numbered steps with Pixel
  screenshots, for when a link lands somewhere unexpected (section 3).
- Rows for features that aren't built yet are hidden. Each one appears when
  its feature ships.
- Turning a setting **off** from here is the same link: Android's page has the
  switch both ways.

## 2. What each button does (verified links)

"Direct" means it opens Bridget's own switch, so one more tap finishes it.

| Row | Button opens | Taps for Rahul | Link (intent) |
|---|---|---|---|
| Camera, Microphone, Photos, Notifications | Android's permission pop-up | 1 | runtime permission request |
| Location: precise, while in use | Android's pop-up: choose **Precise**, **While using the app** | 2 | `ACCESS_FINE_LOCATION` request |
| Location: all the time (optional) | Bridget's Location permission page; choose **Allow all the time** | 1 | `ACCESS_BACKGROUND_LOCATION` request (Android sends it to Settings) |
| App usage | **Direct** to Bridget's "Permit usage access" switch | 1 | `android.settings.USAGE_ACCESS_SETTINGS` + `package:net.finleg.bridget` → `AppUsageAccessSettingsActivity` |
| Do Not Disturb control | **Direct** to Bridget's "Allow Do Not Disturb" switch, then **Allow** | 2 | `android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS` + package → `ZenAccessDetailSettingsActivity` (falls back to the list page `NOTIFICATION_POLICY_ACCESS_SETTINGS`) |
| Modify system settings | **Direct** to Bridget's "Allow modifying system settings" switch | 1 | `android.settings.action.MANAGE_WRITE_SETTINGS` + package → `AppWriteSettingsActivity` |
| Run in background | Pop-up "Let app always run in background?", **Allow** | 1 | `android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + package |
| Read all notifications (hidden by default) | **Direct** to Bridget's notification-access switch, then **Allow** | 2 | `android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS` + component extra → `NotificationAccessDetailsActivity` |
| Anything blocked | Bridget's App info page | see section 4 | `android.settings.APPLICATION_DETAILS_SETTINGS` + package |

How Bridget detects each one: `checkSelfPermission` (runtime permissions and
location), `AppOpsManager` `GET_USAGE_STATS` (app usage),
`NotificationManager.isNotificationPolicyAccessGranted` (Do Not Disturb),
`Settings.System.canWrite` (system settings),
`PowerManager.isIgnoringBatteryOptimizations` (background), and the enabled
listener list (notifications).

The manifest must declare `PACKAGE_USAGE_STATS`,
`ACCESS_NOTIFICATION_POLICY` and `WRITE_SETTINGS`, or Bridget won't appear
on those pages at all.

## 3. "Show me how" steps (fallback)

Short numbered steps with the exact words on Rahul's Pixel, each with a
screenshot. Screenshots are taken on the Pixel while this feature is built
(the phone was locked when this spec was written) and stored in
`android/docs/img/access-*.png` plus inside the app. Wording to confirm then:

**App usage**
1. Settings → **Apps** → **Special app access** → **Usage access**
2. Tap **Bridget**
3. Turn on **Permit usage access**

**Do Not Disturb control**
1. Settings → **Apps** → **Special app access** → **Do Not Disturb access**
2. Tap **Bridget**, turn on **Allow Do Not Disturb**, tap **Allow**

**Modify system settings**
1. Settings → **Apps** → **Special app access** → **Modify system settings**
2. Tap **Bridget**, turn on **Allow modifying system settings**

**Location all the time**
1. Settings → **Apps** → **Bridget** → **Permissions** → **Location**
2. Choose **Allow all the time**, and keep **Use precise location** on

**Run in background**
1. Settings → **Apps** → **Bridget** → **App battery usage**
2. Choose **Unrestricted**

## 4. If a switch is greyed out ("Restricted setting")

Since Android 13, apps installed from a downloaded APK can't be given
notification access (and some other special access) until Rahul allows it.
Bridget installed over ADB isn't affected (its install source is "shell").
A copy installed from the GitHub download may be.

When Bridget sees a row blocked, the row shows **Blocked** and the button
opens App info with these steps:
1. Tap the toggle once so Android shows "Restricted setting", then tap **OK**
2. On Bridget's **App info**, tap **⋮** (top right) → **Allow restricted settings**
3. Confirm with your fingerprint or PIN, then come back and tap **Turn on** again

## 5. One tap from an agent's request

When an agent's call fails because a setting is off (`permission_denied`,
or a new `access_needed` for special access), Bridget can also send Rahul a
**notice** (see `spec-agent-inbox.md`): "**Grok wanted your app usage. Turn on
App usage?**" [Turn on] [Not now]. **Turn on** goes straight to the page in
the table above. The agent's error includes `"setting": "usage_access"` so it
can tell Rahul exactly what's missing, and it can retry once the Access
screen shows the setting on. At most one such notice per setting per day.

## 6. Acceptance tests

1. Every **Turn on** button lands on the page in the table (Bridget's own
   switch where marked Direct) on the Pixel.
2. After flipping each switch and pressing Back, the row shows **On** within
   a second with no app restart.
3. Turning a switch off in Settings shows **Off** the next time Access opens.
4. A blocked (restricted) row shows **Blocked** and the steps in section 4.
5. An agent call that needs App usage while it's off returns `access_needed`
   with `setting: usage_access`, and the notice's button opens the right page.
6. Screenshots exist for each "Show me how" and match the Pixel's wording.

## Open questions

1. Is "Read all notifications" worth offering at all, given the Muse app
   already reads them? (Proposed: hidden, behind **Show**.)
2. Location: is "while in use" (with the always-on listener) enough, or do
   you want "all the time" so location survives restarts without opening the
   app?
