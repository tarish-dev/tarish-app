# Barq (app)

The Android client for [`barqd`](../barq-daemon) — share sheet, transfer UI,
settings, Quick Settings tile.

## Why this is thin, and stays thin

Everything expensive lives in the daemon: holding the AWDL link, discovery, mDNS,
and the transfer itself. This app binds `IBarqService` when the user is looking at
it and is expected **not to be running the rest of the time**.

That is the entire point of the split. An Android app that stays resident must
hold a foreground service and therefore a permanent notification; a native daemon
started by `init` has no such obligation. If a feature would require this app to
stay alive, it belongs in the daemon instead — that is the design rule.

## Scope

**In:**
- share-sheet target (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`)
- pick a peer, show transfer progress
- prompt on an incoming offer, accept or reject
- Quick Settings tile for discoverability
- settings: device name, visibility mode and duration

**Out — these belong to the daemon:**
- advertising and discovery
- holding the AWDL session
- mDNS, the AirDrop protocol, the file transfer

**Out — not our problem:**
- self-update. Barq ships in the system image and updates with it.
- permission onboarding. The platform pre-grants what Barq needs through
  `default-permissions`, so there is no permission dance to walk a user through.

## Relationship to Bada

This app started from ideas and structure in
[Bada](https://github.com/kyujin-cho/Bada) by kyujin-cho — an independent
Quick Share implementation for Android. Bada demonstrated the app-side shape
(share-sheet integration, a receiver service, a Quick Settings tile, NFC
tap-to-share) and was what we used while the transport was being built.

Barq is not a fork of it in spirit: the receiver moves out of the app entirely,
and everything Barq talks to is our own daemon. Where code or approach is taken
from Bada it is credited in `docs/CREDITS.md`, and that file is expected to grow
rather than shrink.

## Contract

The IPC contract is owned by the daemon and consumed from there, so the two
cannot drift:

```
../barq-daemon/aidl/dev/barq/IBarqService.aidl
../barq-daemon/aidl/dev/barq/IBarqCallback.aidl
```

`IBarqCallback` is `oneway` throughout — the daemon must never block on a UI
process that may be slow, frozen, or about to be killed.

## Status

**Not started.** This repo currently holds the plan and the contract it will
build against. The daemon is working; the app is not written yet.
