# Reverse-engineering AirDrop properly

> **Scope correction.** This rig is for building our **own AWDL implementation** --
> a universal driver that does not depend on `libmosey`. It is **not** a debugging
> tool for the current stack.
>
> Once AWDL is established, everything above it is BLE plus ordinary TCP/IP on a
> working interface, and both endpoints are ours to inspect. Reaching for a
> monitor-mode capture to debug an HTTPS exchange is the wrong instrument: slower,
> harder, and it answers a question that `logcat` and `openssl` already answer.
> Use it when the question is genuinely about the air, not about our own sockets.

## Why a dedicated rig

Everything established so far came from one vantage point: an Android phone running our
own code, watching `mosey0`. That is enough to verify what *we* transmit, and it has
been wrong-footed twice by the fact that multicast loops back — our own packets arrive
looking exactly like a peer's.

What it cannot do:

- **See what it does not receive.** AWDL is time-sliced; a station only hears the
  channel during its availability windows. Frames exchanged outside our window, or on
  the other social channel, are invisible.
- **See action frames at all.** The AWDL synchronisation and service-response TLVs ride
  in 802.11 action frames. `libmosey` consumes them and never surfaces them, so
  `Synthetic SR TLVs` is a line in a state dump rather than something we can read.
- **Watch two Apple devices talk.** The most valuable capture is an iPhone AirDropping
  to a Mac with us not participating at all. That is ground truth for the whole
  sequence, and it is the one thing a participant can never record.

A monitor-mode capture answers all three, and it is the only way to get from "our
records look like Mosey's" to "we know what the protocol requires".

## What the rig needs

**Wi-Fi.** Monitor mode *and* frame injection, on **5 GHz**. AWDL's social channels are
6, 44 and 149; a 2.4 GHz-only adapter sees the least interesting one. `ath9k` is the
chipset family OWL is built and tested against, and it is the safe choice — its monitor
and injection support is mature and does not need patched firmware. Verify a specific
adapter before buying: many cards advertise "monitor mode" and cannot inject, and
several popular ones are 2.4 GHz only despite the packaging.

The alternative is a Broadcom card with **nexmon** patches. We established earlier that
nexmon is *not needed* to run AWDL on the Pixel, because `wonder.ko` already exposes
what we need — but that is about driving our own radio, not about observing someone
else's. For pure observation `ath9k` is less work.

**Bluetooth.** Any adapter that can do passive LE scanning with a raw HCI socket.
Unlike the Wi-Fi side this is undemanding: `btmon` against the kernel's HCI monitor
gives full advertising PDUs, and that is all we need. The phone can already do this —
Tarish's own scanner sees Apple beacons — but a Linux host gives the raw bytes rather
than an Android `ScanRecord`.

**OWL** ([owlink.org](https://owlink.org)) is the reference AWDL implementation for
exactly this hardware. Its value here is twofold: it is a second, independent
implementation to compare our behaviour against, and it can act as a *peer* under our
control, which the Mac never will be.

## What to capture, in order

These are all questions about **the radio and the AWDL layer itself**, which is what
the rig is for. Anything above the interface -- mDNS, TLS, HTTP, the transfer -- is
debugged at the endpoints instead.

1. **iPhone → Mac AirDrop, us absent.** The whole sequence with no interference from
   us. This is the reference recording and everything else is compared against it.
2. **The same transfer, BLE and Wi-Fi on one timeline.** The open question is what
   makes a peer start browsing `_airdrop._tcp`. We now know it is not our mDNS records,
   which are byte-identical to Mosey's. Correlating the BLE beacon against the first
   AWDL frame and the first mDNS query should show the trigger directly.
3. **AWDL action frames during discovery.** Specifically whether the service name
   appears in a service-response TLV, which is what `Synthetic SR TLVs` implies and
   what `mosey_update` is suspected of populating.
4. **The HTTPS exchange.** Ports come from SRV. Endpoints are `POST /Discover`,
   `/Ask`, `/Upload`; bodies are Apple binary plists; the file payload is a **cpio**
   archive. TLS is the obstacle — the certificate story for "everyone" mode versus
   contacts-only is not established and is the next real unknown.

## What we already know, so it is not re-derived

| Layer | State | Where |
|---|---|---|
| AWDL bring-up | working from our own process, no Google packages | [MOSEY-FFI.md](MOSEY-FFI.md) |
| `libmosey` FFI | 5 symbols, `mosey_start_5` signature confirmed by calling it | [MOSEY-FFI.md](MOSEY-FFI.md) |
| mDNS records | verified byte-for-byte against Mosey | [AIRDROP-DISCOVERY.md](AIRDROP-DISCOVERY.md) |
| BLE beacon layout | Apple mfg data `0x004C`, type `0x05`, 4x 2-byte SHA-256 slots | tarish-app `docs/BLE-DISCOVERY.md` |
| BLE advertise + scan | working; two Apple devices observed beaconing | tarish-app |
| What triggers a peer to browse | **unknown** — the current blocker | — |
| AirDrop HTTPS + TLS | **not started** | — |
| cpio | **not started** | — |

## The goal

Hardware independence. Tarish now ships **`tlink`, its own open AWDL implementation**, in
place of `libmosey` in production (this rig is what built it); Google's `libmosey` remains
only as the ABI reference/fallback, pinned in `vendor/mosey/` because a vendor bump could
change that ABI under us with no warning. `tlink` still rides on Google's `wonder.ko` kernel
MAC module, which is the remaining hardware dependency this work aims to remove. A protocol we actually
understand can be implemented against OWL, against a monitor-mode adapter, or against
whatever comes next, on hardware Google has no say in.

That is the difference between AirDrop working on this phone and AirDrop working.
