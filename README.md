# Tarish (app)

The Android half of **[Tarish](https://github.com/tarish-dev/tarish-daemon)** — AirDrop and
Quick Share with no Google Play Services and no Google account.

This repo is the part you can see: the share sheet, the transfer prompt, the inbox,
settings, and the radio work that only the framework can do. The protocols and the
transports live in the daemon.

---

## Why the split

Every line in this app is here because the framework will not let a native service do it.
Nothing is here for convenience.

| | where | why |
|---|---|---|
| BLE advertising and scanning | **app** | framework Bluetooth is unreachable from a native daemon |
| Bluetooth RFCOMM / L2CAP socket | **app** | the socket is opened here and handed over as an fd |
| Wi-Fi Direct group | **app** | `WifiP2pManager` is framework API |
| Wi-Fi association frequency | **app** | not exposed as anything a native process can read |
| moving received files to `Downloads/Tarish` | **app** | the daemon is unprivileged and cannot reach shared storage |
| share sheet, prompts, inbox, settings | **app** | it is a UI |
| holding the AWDL link | daemon | needs `CAP_NET_ADMIN`, and must outlive the UI |
| mDNS, TLS, AirDrop protocol, cpio | daemon | parses input from strangers, so it holds nothing else |
| UKEY2, secure channel, sharing state machines | daemon | Rust, and testable off-device |

The daemon is the source of truth for everything except the UI. The app is a client of it
and holds no protocol state.

---

## Design positions

### Advertising only while you are on the Receive screen

Nothing advertises from boot. The BLE beacon starts in `onResume` and stops in `onPause`,
and visibility is off entirely while you are on the Send screen.

That is a privacy position, not a limitation: a device that announces itself to every
AirDrop scanner in range from the moment it powers on is a choice nobody made. The
consequence is worth knowing — **AirDrop stays discoverable with the app closed, because
the daemon owns that; Quick Share does not, because its advertisement lives here.**

### The daemon decides; the app asks

The app never accepts a transfer on its own. Acceptance is a person tapping a prompt, fed
back to the protocol as an event — the state machine cannot reach "transferring" from any
sequence of frames a peer sends. That property is the whole reason the prompt exists.

### Managed policy is honoured, not merely displayed

An administrator can pin AirDrop, Quick Share, the confirmation requirement and the device
name. When a field is pinned:

- the control has **no listener at all** — it does not depress, animate, or respond
- a `managed` label says why
- the stored user preference is discarded when policy is computed, so even a bypassed
  control could not change it

A control that reacts and then refuses is worse than one that plainly cannot be used.

### Transfers survive backgrounding

An in-flight transfer runs in a foreground service with a notification, so leaving the app
does not kill it. Starting a *new* one still needs the app in front.

---

## Permissions, and why each is here

| permission | why |
|---|---|
| `BLUETOOTH_ADVERTISE`, `_SCAN`, `_CONNECT` | the Quick Share endpoint advertisement and the RFCOMM socket |
| `NEARBY_WIFI_DEVICES` | `WifiP2pManager` — without it the Wi-Fi Direct upgrade silently cannot happen |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | reading the association band, and joining a group |
| `LOCAL_MAC_ADDRESS` | Quick Share advertises a real Bluetooth MAC; a redacted one is useless to a peer |
| `POST_NOTIFICATIONS` | a foreground service that cannot post its notification is killed |
| `INTERNET` | loopback and link-local only — there is no server anywhere and nothing is uploaded |

All of these are **pre-granted** by `default-permissions-dev.tarish.app.xml`. Tarish is a
privileged system app and does not prompt.

One trap worth recording: those default grants are applied only when PackageManager decides
the device upgraded, which it judges by comparing the partition fingerprint. An image built
with a stale build number carries the fingerprint already on the phone, is judged "not an
upgrade", and every package it adds arrives with **nothing granted**.

---

## Building

The app is built as part of the platform image, not with Gradle — it is a privileged system
app in `system_ext`, signed with the platform key.

```
PRODUCT_PACKAGES += TarishApp
```

plus `etc/privapp-permissions-dev.tarish.app.xml` and
`etc/default-permissions-dev.tarish.app.xml`. See
[the daemon's integration guide](https://github.com/tarish-dev/tarish-daemon/blob/main/docs/INTEGRATING.md).

---

## Design

Colour, type and iconography follow a brand kit carried in `res/`:

- raw ramps in `values/brand_ramps.xml`, untouched
- semantic tokens in `values/colors.xml` and `values-night/colors.xml`, which decide which
  rung of which ramp plays which role **per theme** — the same rung does not work in both,
  and a mid-grey is low-contrast against light *and* dark
- `tools/contrast.py` measures every pair that meets on screen, in both themes, and is the
  only reason two real contrast defects were found

Themes use plain `Theme.DeviceDefault` parents; the app has no AndroidX and no Material
Components, and its screens are built in code rather than inflated from layouts.

## Licence

Apache 2.0. See [LICENCE](LICENSE).
