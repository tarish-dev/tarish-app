# Tarish (app)

The Android half of [Tarish](https://github.com/tarish-dev/tarish-daemon) — **AirDrop and Quick
Share on Android with no Google Play Services and no Google account**.

This repo is the part you can see: the share sheet, the transfer prompt, settings, and the
Bluetooth radio work. The protocol and the transport live in the daemon.

## What the app does, and what the daemon does

The split is not arbitrary and it is worth understanding before reading either repo.

| | where | why |
|---|---|---|
| BLE advertising and scanning | **app** | a native daemon cannot reach framework Bluetooth |
| Bluetooth connection to a peer | **app** | same reason; the socket is handed to the daemon |
| Share sheet, transfer prompt, settings | **app** | it is a UI |
| Holding the AWDL link | daemon | needs `CAP_NET_ADMIN`, and must outlive the UI |
| mDNS, TLS, AirDrop protocol | daemon | parses input from strangers, so it holds nothing else |
| Quick Share protocol — UKEY2 onwards | daemon | tested against captured traffic, and Rust |

Everything in the app is there because the framework will not let a native service do it.
Nothing is there for convenience.

## Why this is thin, and stays thin

Everything expensive lives in the daemon: holding the AWDL link, discovery, mDNS,
and the transfer itself. The **UI** binds `ITarishService` when the user is looking at
it and is expected **not to be running the rest of the time**.

**One part of the app is an exception, and has to be.** BLE advertising is what makes
an Apple device start asking for us at all, and it cannot wait for someone to open
something — so `TarishBleService` starts at boot and stays. It has no UI, no foreground
notification, and does nothing but advertise and scan. That is the smallest thing that
can be resident, and it is resident for the same reason the daemons are: being
discoverable is not an activity the user should have to perform. See
[docs/BLE-DISCOVERY.md](docs/BLE-DISCOVERY.md).

BLE lives here rather than in the daemon because `libmosey` is pure AWDL — it links no
Bluetooth library at all — and because advertising goes through the framework's
`BluetoothLeAdvertiser`. Google splits it the same way.

That is the entire point of the split. An Android app that stays resident must
hold a foreground service and therefore a permanent notification; a native daemon
started by `init` has no such obligation. If a feature would require this app to
stay alive, it belongs in the daemon instead — that is the design rule.

## Scope

**In:**
- share-sheet target (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`), and an in-app picker
- pick a peer, show transfer progress
- prompt on an incoming offer, accept or reject
- an inbox, so a received file does not arrive and vanish
- Quick Settings tile for discoverability *(not written)*
- settings: device name, visibility mode and duration *(not written)*

**Out — these belong to the daemon:**
- advertising and discovery
- holding the AWDL session
- mDNS, the AirDrop protocol, the file transfer

**Out — not our problem:**
- self-update. Tarish ships in the system image and updates with it.
- permission onboarding. The platform pre-grants what Tarish needs through
  `default-permissions`, so there is no permission dance to walk a user through.

## Relationship to Bada

This app started from ideas and structure in
[Bada](https://github.com/kyujin-cho/Bada) by kyujin-cho — an independent
Quick Share implementation for Android. Bada demonstrated the app-side shape
(share-sheet integration, a receiver service, a Quick Settings tile, NFC
tap-to-share) and was what we used while the transport was being built.

Tarish is not a fork of it in spirit: the receiver moves out of the app entirely,
and everything Tarish talks to is our own daemon. Where code or approach is taken
from Bada it is credited in `docs/CREDITS.md`, and that file is expected to grow
rather than shrink.

## Contract

The IPC contract is owned by the daemon and consumed from there, so the two
cannot drift:

```
tarish-daemon/aidl/dev/tarish/ITarishService.aidl
tarish-daemon/aidl/dev/tarish/ITarishCallback.aidl
```

`ITarishCallback` is `oneway` throughout — the daemon must never block on a UI
process that may be slow, frozen, or about to be killed.

## Status

**Working**, on a GrapheneOS build with no Google applications, under SELinux
enforcing: files go both ways with a Mac, peers show real device names, and an
incoming transfer has to be accepted by a person.

| | state |
|---|---|
| share-sheet target (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) | done |
| in-app file picker | done |
| pick a nearby device and send | done |
| accept / decline prompt on an incoming offer | done |
| inbox — received files persist until saved, not dumped and forgotten | done |
| live discovery while the send screen is open | done |
| survives a daemon restart (`linkToDeath` + rebind) | done |
| Quick Settings tile for discoverability | not written |
| settings: device name, visibility mode and duration | not written |

### Two rules the UI encodes

**A device cannot be picked with nothing to send.** Tiles are dimmed and inert
until files are chosen, and tapping one says why. A tile that looks tappable and
silently does nothing reads as a broken app.

**A decline is not a failure.** The daemon reports it as its own status (`-2`,
distinct from `-1`), and the app says "Declined — the other device turned it
down" rather than "could not send", which would invite a retry that gets refused
again.

### Visibility follows the foreground

`onResume` makes the device discoverable and tells the daemon a client is active;
`onPause` withdraws both. The daemon renews visibility on its own timer while the
receive screen is up, so it cannot silently lapse under a UI that still claims to
be visible.

`setActive` is deliberately separate from `setDiscoverable`: it governs the AWDL
radio, and a client that is *sending* is not discoverable while needing the link
more than ever.

## Credits

**[Bada](https://github.com/kyujin-cho/Bada)**, Apache 2.0, is the working Quick Share
implementation this project learned the protocol from. See
[docs/CREDITS.md](docs/CREDITS.md) for what came from where.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
