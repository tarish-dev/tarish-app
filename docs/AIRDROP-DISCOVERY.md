# How macOS actually discovers AirDrop peers

Captured off the wire between a MacBook Pro and this phone over AWDL, 2026-08-23.
Raw capture: [apple-airdrop-discovery-capture.txt](apple-airdrop-discovery-capture.txt).

> **CORRECTION, same day.** The conclusion below — that `_airdrop._tcp.local` is
> not the discovery service — is **wrong**, and was drawn from watching only a
> Mac. Capturing Google's own Mosey on the same link settled it: Mosey
> advertises `_airdrop._tcp.local`, and it interoperates. See
> [What a working Android peer advertises](#what-a-working-android-peer-advertises)
> below, which is the section to build from. What remains true is that the Mac
> also runs two *pairing* services, and that Tarish's TXT was wrong — just not for
> the reason first concluded.

## The finding

**`_airdrop._tcp.local` is not how modern macOS finds peers.** Advertising it and
answering correctly is not enough, and that is exactly what Tarish did for several
build cycles while a Mac sat three feet away and never listed it.

Attributing every packet in a 260-packet capture to its sender:

```
19  phone   Q  _airdrop._tcp.local                        <- us, browsing
 1  Mac     Q  _airdrop._tcp.local                        <- asked ONCE
 3  Mac     Q  _applicationServicePairing._tcp.local
 3  Mac     Q  _appSvcPrePair._tcp.local
185 Mac     A  k-mbprom5._appsvcprepair._tcp.local
184 Mac     A  k-mbprom5._applicationservicepairing._tcp.local
```

The Mac asked about `_airdrop._tcp` **once** in fifteen minutes. Everything else
it did — hundreds of records — was two other services.

## What the Mac advertises

```
_services._dns-sd._udp.local  PTR -> _applicationservicepairing._tcp.local
_services._dns-sd._udp.local  PTR -> _appsvcprepair._tcp.local

k-mbprom5._appsvcprepair._tcp.local
    SRV -> 84717908-3422-48b5-b2be-53f0c066c0df.local:55573
    TXT    sn=com.apple.sharingd.AirDrop
           at=c35650444485
           sid=D593F104-38F3-4121-A0B4-4F166E5DC402
           dnm=K-MBProM5
           _dc=1

k-mbprom5._applicationservicepairing._tcp.local
    SRV -> 84717908-3422-48b5-b2be-53f0c066c0df.local:55574
    TXT    (same, WITHOUT dnm)
```

**AirDrop is named inside the TXT, not by the service type.**
`sn=com.apple.sharingd.AirDrop` is what marks these records as AirDrop; the
service itself is a generic Apple "application service pairing" mechanism that
presumably carries other services too.

## Field notes

| Field | Observed | Notes |
|---|---|---|
| `sn` | `com.apple.sharingd.AirDrop` | the service identity — this is the AirDrop marker |
| `at` | `c35650444485`, earlier `004eb9b20bd5` | 12 hex, **changes between sessions** |
| `sid` | a UUID, different each session | session identifier |
| `dnm` | `K-MBProM5` | the displayed device name. Present on `_appsvcprepair` only |
| `_dc` | `1` | unknown |

Instance name is the device name lowercased (`K-MBProM5` -> `k-mbprom5`), not an
opaque id. The **host** is a UUID, and it also changes per session.

Two ports, adjacent and ephemeral-looking: `55573` for pre-pair, `55574` for
pairing. They differ between captures, so they are assigned, not fixed.

## What this means for Tarish

Advertising `_airdrop._tcp.local` alone cannot work. To be listed we must
advertise both pairing services with `sn=com.apple.sharingd.AirDrop`, plus the
`_services._dns-sd._udp.local` pointers, and answer for a UUID-shaped host.

Still unknown, and not answered by this capture:

- what `at` and `_dc` mean, and whether a peer validates them
- what protocol runs on the pre-pair and pairing ports
- whether BLE is required before any of this is honoured. The Mac transmits
  nothing on AWDL when idle, so something wakes it, and BLE is the documented
  trigger

## How this was captured

macOS **gates AWDL multicast reception** — a plain socket joined to `ff02::fb`
on `awdl0` receives nothing, while the same code on `en0` works. Only
mDNSResponder with the P2P flag sees AWDL traffic. Sending is not gated.

So the phone is the only usable vantage point: `tools/mdnsdump.c` runs there and
sees everything. Two earlier conclusions drawn from a Mac-side listener were
wrong because of this, and the listener had not been self-tested.


## What a working Android peer advertises

Captured from Google's Mosey on this phone, with a Mac as peer, 2026-08-23.
Raw: [mosey-airdrop-advertisement.txt](mosey-airdrop-advertisement.txt).

**This is the template to build against**, because Mosey is an Android
implementation on the same radio and interface as Tarish, and it demonstrably
interoperates with Apple devices.

```
PTR   _services._dns-sd._udp.local -> _airdrop._tcp.local
PTR   _airdrop._tcp.local          -> 8ed4b330f476._airdrop._tcp.local
SRV   8ed4b330f476._airdrop._tcp.local -> Android_MHMQSTGX.local:40985
TXT   8ed4b330f476._airdrop._tcp.local -> flags=489
AAAA  Android_MHMQSTGX.local -> fe80::d88a:15ff:fec8:f2a0
NSEC  8ed4b330f476._airdrop._tcp.local
NSEC  Android_MHMQSTGX.local
PTR   0.A.2.F...ip6.arpa -> Android_MHMQSTGX.local     (reverse)
```

### The TXT is `flags=489`

One key, one value. **Not** the `sn`/`at`/`sid`/`dnm` set — those belong to the
Mac's `_appsvcprepair` and `_applicationservicepairing` services, and Tarish
briefly copied them onto `_airdrop._tcp`, which is a service they never appear
on. 489 = `0x1E9`; the bit meanings are not yet known.

### What Tarish was missing

| | Mosey | Tarish (before) |
|---|---|---|
| `_services._dns-sd._udp` PTR | yes | **no** |
| TXT | `flags=489` | `dnm=`/`sid=`/`_dc=` — wrong service's fields |
| NSEC for instance and host | yes | **no** |
| reverse `ip6.arpa` PTR | yes | **no** |
| host name | `Android_XXXXXXXX.local` | `<12hex>.local` |
| instance | `<12hex>._airdrop._tcp.local` | same — this part was right |

NSEC matters more than it looks. It is how a responder says "this name exists
and has *these* record types and no others". When the Mac asked our host for an
**A** record and we had only AAAA, the correct answer was an NSEC asserting that
— silence reads as "no such host".

Mosey also runs three instances at once, each with its own hostname and port,
and ports are high and arbitrary (40985, 38005, 39763) rather than fixed.

### One more thing Mosey does

Its own state dump shows:

```
Synthetic SR TLVs:  [_airdrop._tcp.local]
```

It injects the service name into AWDL's own synchronisation TLVs, so the service
is announced at the AWDL layer as well as over mDNS. Whether an Apple peer
*requires* that, or merely benefits from it, is not known.

**Hypothesis, untested: this is what `mosey_update` is for.** Tarish calls only
`mosey_start_5` and `mosey_stop`. Google's daemon also calls `mosey_update`,
which a trace of Google's daemon shows taking four arguments — `x0` the session handle, `x1`
a pointer, `x2`=1, `x3`=0 — with the pointer's contents never identified. A
service-registration call would fit: it is per-session, it takes a buffer, and
something has to carry `_airdrop._tcp.local` from userspace into the SR TLVs.
`mosey_start_5` also takes a `max_mdns` argument, so the library is mDNS-aware
rather than purely a link layer.

That is a guess from argument shapes, not evidence. The cheap test is the mDNS
work first: if a peer lists us with the corrected records, the TLVs were not
required and this stays a curiosity. If it does not, `mosey_update` is the next
thing to trace — its real arguments can be read by hooking the call in
Google's daemon.
