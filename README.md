# Tarish

**File sharing for Android that works with AirDrop and Quick Share, with no Google Play
Services — sandboxed or otherwise — and no Google account.**

Send a file to a MacBook from a phone that has never spoken to Google. The Mac shows a real
device name and a normal AirDrop prompt; the phone shows a normal share sheet. Send to a
Windows laptop or another Android phone and Quick Share does the same. Nothing signs in,
nothing checks in, and no Google application is installed.

**This repository is the home of the project and of all its documentation.** The transports
live in a second repository because they are a different kind of software — a native daemon
with its own SELinux domain — but everything needed to understand, build, integrate or adopt
Tarish is here.

| repository | contains |
|---|---|
| **tarish-app** (this one) | the app, and **all documentation** for the whole project |
| [**tarish-daemon**](https://github.com/tarish-dev/tarish-daemon) | `tarishd` and `tarishsharingd`: the transports, the protocol library, the SELinux policy |
| [**tarish-libawdl**](https://github.com/tarish-dev/tarish-libawdl) | an open AWDL implementation — the replacement for Google's closed `libmosey` |

---

## Status

Verified on hardware, under SELinux **enforcing**, on a build with **zero Google
applications installed**.

| | AirDrop (Apple) | Quick Share (Android / Windows) |
|---|---|---|
| discovery | ✅ AWDL + mDNS | ✅ mDNS on Wi-Fi, BLE off-network |
| send | ✅ | ✅ |
| receive | ✅ | ✅ |
| shared network | ✅ | ✅ 22 MB/s measured |
| off-network | ✅ AWDL is its own link | ✅ Wi-Fi Direct 10.5 MB/s; Bluetooth ~150 KB/s bootstrap |
| both protocols at once | ✅ on BCM4390, concurrently in opposite directions, no degradation. ⚠️ **not possible on BCM4383** — see [Devices](#devices) |

Interoperability is tested against **real peers, not only against ourselves**: macOS and iOS
for AirDrop, Windows 11 and stock Android for Quick Share. That distinction has caught bugs
no amount of self-testing would — see [Testing](#testing).

Open issues are in **[docs/TODO.md](docs/TODO.md)**, which leads with what is still broken
rather than with what has been finished.

---

## Why, when sandboxed Play Services exists

Sandboxed Play Services on GrapheneOS does not cover this, for two reasons.

**It cannot do AirDrop at all.** AirDrop needs Google's `mosey` stack running with platform
privileges. Getting it working that way — the route this project took first — required
*privileged* Play Services, not the sandboxed kind, plus a successful check-in to Google's
servers to receive a feature flag. That is a long way from "install an app".

**And for many the objection is not the sandbox but the code.** Sandboxed Play Services is
still Google's code on the device, and a de-Googled OS is often chosen to avoid exactly that,
at any privilege level. Tarish needs none of it.

---

## How it is put together

Two processes, and the split is not arbitrary.

**The daemon holds the link and speaks the protocols.** A native daemon has no
foreground-service requirement, so there is no permanent notification, and doze and
app-standby do not apply to it. It keeps AWDL up, answers mDNS, terminates TLS, and runs
both protocol state machines.

**The app does what only the framework can do, and owns consent.** Bluetooth LE, Wi-Fi
Direct groups and the share sheet are framework API a native daemon cannot reach; the app
creates those and hands the daemon a file descriptor. It is also where a person says yes or
no, and where managed policy is read.

Every line in the app is there because the framework will not let a native service do it:

| | where | why |
|---|---|---|
| BLE advertising and scanning | **app** | framework Bluetooth is unreachable from a native daemon |
| Bluetooth RFCOMM / L2CAP socket | **app** | opened here, handed over as an fd |
| Wi-Fi Direct group | **app** | `WifiP2pManager` is framework API |
| Wi-Fi association frequency | **app** | not exposed to a native process |
| moving received files to `Downloads/Tarish` | **app** | the daemon is unprivileged and cannot reach shared storage |
| share sheet, prompts, inbox, settings | **app** | it is a UI |
| holding the AWDL link | daemon | needs `CAP_NET_ADMIN`, and must outlive the UI |
| mDNS, TLS, AirDrop protocol, cpio | daemon | parses input from strangers, so it holds nothing else |
| UKEY2, secure channel, sharing state machines | daemon | Rust, and testable off-device |

The daemon is the source of truth for everything except the UI. The app holds no protocol
state.

### The layers, per protocol

Being precise about which layers are open source and which are the vendor's matters more than
the line count.

#### AirDrop

| layer | source | notes |
|---|---|---|
| radio and MAC — `wonder.ko` | **vendor** | Google/Broadcom kernel module, already in the stock image. **Zero AWDL protocol strings in it** |
| AWDL protocol — `libmosey_daemon_ffi.so` | **vendor** | election, sync, peer discovery. 51 protocol strings. This is what [`tarish-libawdl`](https://github.com/tarish-dev/tarish-libawdl) replaces |
| IP on `mosey0` | open source | including the routing Android's fwmark model requires |
| mDNS, TLS, HTTP, Apple's plist dialect, cpio | **open source** | `tarishsharingd` |
| share sheet, prompts, consent | **open source** | the app |

Both vendor blobs **already ship in the Pixel vendor image** and run on a build with no
Google packages, so using them adds nothing to the device that was not already there.
Replacing the AWDL blob is a separate track, and it is underway:
**[tarish-libawdl](https://github.com/tarish-dev/tarish-libawdl)** is an open AWDL
implementation that already brings the radio up, wins Apple's master election and synchronises
to Apple devices on a Pixel with no `libmosey` in the path. The reverse-engineering record is
[docs/REVERSE-ENGINEERING.md](docs/REVERSE-ENGINEERING.md).

#### Quick Share

Entirely open source, top to bottom — there is no vendor component.

| layer | where |
|---|---|
| BLE advertise and scan | **app** |
| mDNS discovery on `wlan0` | daemon |
| Bluetooth RFCOMM / L2CAP socket | **app**, handed to the daemon as an fd |
| Wi-Fi Direct group | **app**, socket handed over |
| UKEY2 handshake, D2D keys, SecureMessage | daemon — `libtarish_protocol` |
| secure channel, sequence numbers, replay refusal | daemon |
| offline frames, payload reassembly, sharing FSM | daemon |
| bandwidth upgrade negotiation | daemon decides, app provides the radio |

`libtarish_protocol` is deliberately **Android-free** so it is testable on a build host. 202
tests, including one that runs a whole share between two peers in-process: handshake, key
derivation, encrypted channel, introduction, acceptance, a file in chunks, reassembled and
compared byte for byte.

---

## Devices

Tarish needs `wonder.ko` **bound to the Wi-Fi driver**, and that depends on the Wi-Fi chip,
not the model or the SoC. Every Pixel 10 image ships both `bcmdhd4383.ko` and
`bcmdhd4390.ko` and loads whichever matches the silicon, so the model name tells you
nothing. Check the device:

```bash
adb shell 'lsmod | grep bcmdhd'          # 4390 = good, 4383 = no wondertap
adb shell 'ls /sys/class/ieee80211/'     # a `wonder` wiphy is the real test
```

| device | model | chip | AirDrop | Quick Share |
|---|---|---|---|---|
| `blazer` | Pixel 10 Pro | BCM4390 | ✅ AWDL and Wi-Fi coexist | ✅ |
| `mustang` | Pixel 10 Pro XL | BCM4390 | ✅ AWDL and Wi-Fi coexist | ✅ |
| `frankel` | Pixel 10 | BCM4383 | ⚠️ works, but takes the radio — see below | ✅ |
| `rango` | Pixel 10 Pro Fold | unverified | unverified | expected to work |
| `stallion` | Pixel 10a | no `wonder.ko` at all | ❌ impossible | ✅ |

**On BCM4383 (Pixel 10) the radio is exclusive.** There is no `wondertap`, so AWDL falls
back to a radiotap path that takes the physical radio: Wi-Fi drops within about ten seconds
of AirDrop becoming active and comes back on its own **10-24 seconds after AirDrop is
switched off**. Measured over repeated cycles with the association on 5520 MHz and the band
correctly reported to the daemon — so this is the silicon, not a band-selection mistake, and
no software change reaches it.

That is a tradeoff rather than a trap: nothing needs a reboot and nothing stays broken. But
the consequence is the practical answer for this device: **on BCM4383, AirDrop and Quick
Share over Wi-Fi are mutually exclusive.** Quick Share needs no AWDL, but it does need
Wi-Fi, and AWDL has taken it.

AirDrop is also an order of magnitude slower there — roughly **0.8 MB/s** against
**7.7-9.1 MB/s** on a BCM4390 device. **For a daily driver, prefer a 4390 device.**

**The Pixel 10a can never do AirDrop.** Its image ships no `wonder.ko`. It still ships
`mosey_server` and the Mosey app, so finding those proves nothing. Quick Share works.

### Transfers to an Apple device are slower, and that is the protocol

An iPhone keeps a sparse AWDL presence to save power. Measured on hardware: our devices are
available in **16 of 16** availability windows on one 5 GHz channel; an iPhone is available
in **4 of 16**, split between 5 GHz and 2.4 GHz — so useful overlap is around 19%. Device to
device runs 7.7-9.1 MB/s; iPhone to device runs 2.6-4.7 MB/s. Both ends staying awake is why
the first number is higher, and there is nothing to fix in it.

---

## Integrating this into an OS

This is built to be adopted, and the guide is written for someone who has never seen the
project:

### ➜ **[docs/GRAPHENEOS.md](docs/GRAPHENEOS.md)** — complete GrapheneOS integration

Every step: what to copy, the makefile entries, the AID, the SELinux policy and its four
context files, the framework patch that is not optional, the scripts that automate it, and
how to verify each stage separately so a failure tells you where it is.

Platform-independent requirements — what the OS must provide, the routing trap, the
regulatory-country requirement — are in **[docs/INTEGRATING.md](docs/INTEGRATING.md)**.

**LineageOS is the next target.** Nothing here is GrapheneOS-specific by design: the daemon
asks the platform for an AWDL library and a kernel module and does not care where they came
from, which is exactly why the vendor pin lives with the integrator rather than in either
repository.

> **If you maintain GrapheneOS or LineageOS and want this in the image, please open an
> issue.** No Google dependency, no network callbacks, an unprivileged parser process,
> policy an administrator can pin, and a licence that imposes nothing.

---

## Documentation

| document | what it covers |
|---|---|
| [GRAPHENEOS.md](docs/GRAPHENEOS.md) | **integrating into GrapheneOS**, end to end |
| [INTEGRATING.md](docs/INTEGRATING.md) | what any platform must provide, and the traps |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | how the processes divide the work today |
| [ARCHITECTURE-TARGET.md](docs/ARCHITECTURE-TARGET.md) | where the split is heading |
| [POLICY.md](docs/POLICY.md) | managed configuration, and the SELinux domains |
| [AIRDROP-DISCOVERY.md](docs/AIRDROP-DISCOVERY.md) | AWDL, mDNS, and how a peer is found |
| [AIRDROP-PROTOCOL.md](docs/AIRDROP-PROTOCOL.md) | `/Discover`, `/Ask`, `/Upload` on the wire |
| [BLE-DISCOVERY.md](docs/BLE-DISCOVERY.md) | Quick Share advertising and scanning |
| [QUICKSHARE-VECTORS.md](docs/QUICKSHARE-VECTORS.md) | captured vectors from real peers |
| [MOSEY-FFI.md](docs/MOSEY-FFI.md) | the five vendor functions and their ABI |
| [REVERSE-ENGINEERING.md](docs/REVERSE-ENGINEERING.md) | replacing the vendor AWDL blob |
| [TODO.md](docs/TODO.md) | **what is still open** |
| [CREDITS.md](docs/CREDITS.md) | prior work this benefited from |

---

## Design positions

These look odd until you know why.

**Advertising runs only while you are on the Receive screen**, and the screen is held awake
while it does. Anything else means a phone quietly discoverable in a bag. The screen-awake
part is not decoration: without it the screen times out, the activity pauses, visibility
drops, and the device goes off the air while still reporting itself as available.

**The daemon decides; the app asks.** Consent is enforced where the bytes move, not in the
UI, so an app that hides its send button cannot be bypassed by calling the interface
directly.

**Managed policy is honoured, not merely displayed.** A pinned control is shown disabled and
labelled rather than hidden — a control that silently refuses to move reads as a broken app
rather than as an enforced policy. See [POLICY.md](docs/POLICY.md).

**Transfers survive backgrounding** through a foreground service typed `connectedDevice`,
which is the honest type: the work being protected is a link to another device.

**`tarishsharingd` runs as its own uid**, which costs a one-line framework patch. Since
Android B only uid 0 and uid 1000 may touch the local network, and every other uid needs a
bit in a BPF map derived from *packages* — so a native daemon can never earn it and mDNS
`sendto()` fails with `EPERM`. [GRAPHENEOS.md](docs/GRAPHENEOS.md) carries the patch.

---

## Building

The app is a plain AOSP app — no Gradle, no Material Components, views built in code — so it
builds inside a platform tree and nowhere else:

```bash
m TarishApp
```

The daemon builds the same way; the [integration guide](docs/GRAPHENEOS.md) covers wiring
both into a device.

---

## Testing

Two devices, driven from a script, no screen taps:

```bash
tarishctl peers                     # what this device can see
tarishctl send <peer-id> <file>     # start a transfer over AirDrop
tarishctl send-lan <peer-id> <file> # ...or over Quick Share on the LAN
tarishctl accept <transfer-id>      # answer an offer
tarishctl policy                    # read the daemon's live policy
tarishctl policy pin off            # for transport tests
tarishctl policy airdrop 0          # one protocol only
```

**Read the policy back rather than assuming it.** `tarishctl policy` asks the daemon; the
radio cannot answer the question, because the AWDL session outlives visibility by about half
a minute, so `mosey0` being up is equally consistent with AirDrop on and with AirDrop having
just been switched off.

`tarishctl` is **userdebug and eng only**, enforced in the makefile and again in SELinux
policy — it can start a transfer and accept an incoming one, which is not something a shell
on a production device should be able to do.

**Test against both vendors, always.** Passing against one proves very little:

- Windows sends no confirming `Response` and cannot host Wi-Fi Direct at all
- macOS accepts a gzip container that Apple itself never sends
- a stock Pixel does both, and is stricter about frame shapes

And test **two Tarish devices against each other**, which is different again: a third-party
peer runs its own half of the protocol, so it papers over any place where our two halves
disagree. Several real bugs were only ever visible device-to-device — a container mismatch
where our sender gzipped and our receiver did not decompress, and an id mismatch that made
accepting an incoming transfer impossible.

**And beware what your harness makes impossible.** The end-to-end script forces the screen on
so long runs behave consistently, and that bought immunity to the single most likely real
failure — a phone left waiting with its screen timing out. Sixteen passing rows across two
devices could not see it. Ask what your fixtures rule out, then test that.

---

## Acknowledgement

Thanks to **[Bada](https://github.com/kyujin-cho/Bada)**, an open Quick Share implementation
for Android. **No code from it is used here** — this is a fresh implementation in a different
language and process model — but it worked out several protocol details independently and
documented why they matter, and that saved real time. Where a constant exists because Bada
found it first, the comment beside it says so.

Also to **OWL** and **OpenDrop** from seemoo-lab (TU Darmstadt), for establishing what AWDL and
AirDrop look like on the wire. Full credits: [docs/CREDITS.md](docs/CREDITS.md).

## Licence

Apache 2.0. See [LICENSE](LICENSE).

AirDrop is a trademark of Apple Inc., registered in the U.S. and other countries and regions.
Quick Share, Android and Pixel are trademarks of their respective owners. Tarish is an
independent project and is not affiliated with or endorsed by Apple, Google or Samsung.
