# Quick Share — captured vectors from a real peer

Captured from **Windows Quick Share** (Google's Quick Share app for Windows) advertising on
the LAN, 2026-08-28. These are real bytes off a real implementation, not synthesised from
a reading of the protocol, which is what makes them worth keeping: a test that passes
against our own encoder proves only that we are self-consistent.

Captured with nothing more than the host's own resolver, no code of ours involved:

```
dns-sd -B _FC9F5ED42C8A._tcp .
dns-sd -L I0dTWlL8n14AAA _FC9F5ED42C8A._tcp local
```

## The advertisement

```
service   _FC9F5ED42C8A._tcp.local
instance  I0dTWlL8n14AAA
host      K-Desktop5090.local:54703
TXT       n=BgGJXkvVBh_HZPOTA1LoUvoNSy1EZXNrdG9wNTA5MA
```

The service type confirms `sha256("NearbySharing")[0..6]` = `FC9F5ED42C8A`, which we derive
in `quickshare::SERVICE_ID_HASH_PREFIX`.

## Instance name

base64url, 10 bytes, and structurally identical to ours:

```
windows  I0dTWlL8n14AAA  ->  23 | 47 53 5A 52 | FC 9F 5E | 00 00     endpoint_id "GSZR"
ours     I0VyTHf8n14AAA  ->  23 | 45 72 4C 77 | FC 9F 5E | 00 00     endpoint_id "ErLw"
                             ^^   ^^^^^^^^^^^   ^^^^^^^^   ^^^^^
                             PCP  endpoint id   svc hash   reserved
```

Only the random endpoint id differs, which is what should differ. PCP is `0x23` on both.

## EndpointInfo (the `n=` TXT value)

31 bytes:

```
06 01 89 5E 4B D5 06 1F C7 64 F3 93 03 52 E8 52 FA 0D 4B 2D 44 65 73 6B 74 6F 70 35 30 39 30
^^ |------------------ 16 byte metadata -----------------| ^^ |------- "K-Desktop5090" -------|
header                                                     len
```

Header byte `0x06` = `0000 0110`:

| field | bits | value |
|---|---|---|
| version | 7..5 | 0 |
| hidden | 4 | 0 |
| device_type | 3..1 | **3** (laptop) |
| reserved | 0 | 0 |

`device_type=3` for a Windows desktop is a useful data point: the field describes a form
factor, and Windows reports laptop rather than a desktop-specific value.

The name is length-prefixed UTF-8 with no terminator and no trailing bytes, which matches
`quickshare::endpoint`.

## What this already settles

- the service type, the instance encoding and the EndpointInfo layout are right
- **LAN discovery is real.** Quick Share advertises over mDNS on the local network, so it
  is not a BLE-only discovery protocol with mDNS as a mere connection medium. That was an
  open question and it is now answered by observation.
- a peer is reachable at a plain TCP port (`54703` here) taken from the SRV record, which
  is where the Nearby Connections framing begins

## What it does NOT settle

- whether a peer will *accept* a connection that arrives without any prior BLE contact.
  Discovery working over mDNS does not by itself prove the connection path does.
- anything above the transport: UKEY2, the connection frames and the sharing FSM are all
  still unverified against a real implementation.
- the 16 metadata bytes are opaque here. They are not decoded, and nothing in our code
  depends on their content yet.

## Re-capturing

The instance name and port change per session, so these exact values are a snapshot. The
structure is what is being asserted, not the bytes. Re-run the two `dns-sd` commands above
against any Windows or Android peer with Quick Share visible to everyone.

## BLE endpoint advertisements, captured 2026-09-03

Taken on mustang with an unfiltered BLE scan (`persist.tarish.ble_debug 1`), in a room with
a Windows machine running Quick Share and an Android device actively sharing.

**The headline result is a negative one.** Over several minutes there were **zero**
`0xFE2C` FastInitiation pulses and hundreds of `0xFEF3` advertisements. Discovery happens
on `0xFEF3`; the `0xFE2C` pulse is a sender waking an idle receiver, not the mechanism by
which peers are found. `protocol/src/ble.rs` said otherwise until this capture.

Three advertisements, from two devices with randomised addresses:

```
4a17233932584d1132b3480eb85b8b562fcefb2077e73494e5f264    endpoint "92XM"
4a172357444b4811320f75aa376a6c91683c1583cf24accd3c2536    endpoint "WDKH"
4a172342524a361132e88e4a78a8c8399b84fcc5a2404ee85f2d94    endpoint "BRJ6"
```

Structure, agreed by all three:

| offset | bytes | meaning |
|---|---|---|
| 0 | `4a` | version and flags; bit 1 set marks a fast advertisement |
| 1–2 | `17 23` | constant in every sample; **meaning not established**. `0x17` = 23 = length − 4 |
| 3–6 | varies | endpoint id, four printable ASCII characters |
| 7 | `11` | endpoint-info length, 17 |
| 8–24 | varies | endpoint info: flags byte, 2-byte salt, 14-byte encrypted metadata key |
| 25–26 | varies | two trailing bytes, **purpose unknown** |

The endpoint-info length being exactly 17 across three samples from two devices is what
makes the reading credible: 1 + 2 + 14 is Quick Share's own endpoint-info shape.

Decrypting the device name from the metadata key needs a contact certificate rooted in a
Google account. Tarish has none and wants none, so the field is carried and not
interpreted — the name arrives later over the connection, as it does for AirDrop.

For contrast, the idle background advertisements on the same service are 17 bytes and a
different shape, and must not be read as peers:

```
5120001111020000232000557834460000
512210001002040803200082db0adb0000
5128000010024020231000158513940000    (K-PROART, the Windows machine, while idle)
```

### The one that only appeared with extended advertising

The capture above missed the Windows machine entirely. The cause was not the filter and
not the peer: **Android's BLE scanner reports only legacy advertisements unless
`setLegacy(false)` is set**, and a device using BLE 5 extended advertising is then
completely invisible — no error, no callback, nothing to notice — while being plainly
discoverable to any other scanner. A stock Android phone could see the machine the whole
time.

With extended advertising enabled, 614 sightings in 30 seconds:

```
48fc9f5e0000002b23fc9f5e425146551a06233429a1567e52933345fc86671defa6084b2d50726f4172749cc7d3e40de9000056ce
```

| offset | bytes | meaning |
|---|---|---|
| 0–6 | `48 fc9f5e 000000` | outer frame header |
| 7 | `2b` | **body length, 43** |
| 8 | `23` | `versPCP` — version 1, PCP_HIGH. Constant on stock peers |
| 9–11 | `fc 9f 5e` | `sha256("NearbySharing")[..3]` |
| 12–15 | `42 51 46 55` | endpoint id, `"BQFU"` |
| 16 | `1a` | endpoint-info length, 26 |
| 17 | `06` | flags |
| 18–19 | `23 34` | salt |
| 20–33 | `29 a1 … a6` | encrypted metadata key, 14 bytes |
| 34 | `08` | name length |
| 35–42 | `4b 2d 50 …` | **`"K-ProArt"`** |
| 43–48 | `9c c7 d3 e4 0d e9` | **Bluetooth MAC `9C:C7:D3:E4:0D:E9`** |
| 49–52 | `00 00 56 ce` | device token and padding |

`8 + 43 + 2 = 53` exactly.

**The Bluetooth MAC is the point.** It is how a peer is reached when there is no network:
BLE finds the device and hands over an address to open a Bluetooth connection to. The
fast form has no MAC, which is why the same peer can be discoverable and unreachable.

**Field names and meanings are Bada's**, from `BleServiceData.kt`. This table originally
recorded offsets 1–2 and 4–8 as "constant across samples, meaning not established" and
the trailing bytes as "purpose unknown". They are a body length, a version/PCP byte, and
a device token. The structure had been reconstructed correctly from captures; the labels
were guesses, and reading Bada replaced them with facts.

1 + 2 + 14 + 1 + 8 = 26 exactly, which is what makes the reading solid rather than
plausible.

**A peer visible to everyone publishes its name in the clear.** The contacts-only form
does not — the name is inside the encrypted metadata key, which needs a certificate
rooted in a Google account to read. Tarish has none and wants none, so a contacts-only peer
is reported without a name rather than guessed at. Everyone-mode is the case Tarish can
use, and it is also the case a person chooses deliberately.

The hash appears twice because the outer frame repeats it. The parser scans for a hash
that is preceded by a version byte and followed by an ASCII endpoint id and a length that
fits, rather than trusting a fixed offset — the frame header differs between captures
(two bytes on the Android peers, eight on this one) and validating the body is more
robust than characterising every wrapper.
