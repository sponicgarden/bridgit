# Spec: flashlight (Bridget 1.3)

> **Superseded by [bridget-spec-v2.md](bridget-spec-v2.md)** (27 Oct 2026). Kept for history; v2 is the source of truth.

Status: proposed, 26 Oct 2026. Not built. Feature idea #7, scoped to the
flashlight only.

```
POST /v1/flashlight
{ "on": true, "auto_off_seconds": 60 }      // auto_off 1-600, default 60; "on": false turns it off
-> 200 { "on": true, "auto_off_at": "2026-10-26T23:59:00+01:00" }

GET /v1/flashlight -> { "on": false }
```

- Uses Android's torch control (`CameraManager.setTorchMode`), which works
  from the background and doesn't open the camera or show the green
  indicator. The notification says "Grok turned on the flashlight" while it's
  lit, with a **Turn off** button.
- It always turns itself off after `auto_off_seconds`, so an agent can't
  leave it burning. It also turns off if the camera is needed for a snapshot.
- If another app is using the camera, the call returns `sensor_busy`.
- Its own switch (master and per agent: **Flashlight**), off by default.
  Audit-logged like every request.

Acceptance: Grok turns it on, it is visibly lit, it goes off by itself after
the timeout, and the Turn off button in the notification works.
