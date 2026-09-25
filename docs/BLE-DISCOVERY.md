# BLE — the trigger that makes AirDrop discovery start

## Why this exists

Tarish had a correct mDNS responder for `_airdrop._tcp.local` — the right records, the
right TXT, NSEC, reverse PTR, verified byte-for-byte against what Google's Mosey puts
on the wire — and no Apple device ever listed it.

The capture that explained it, taken on `mosey0` with a Mac's AirDrop window open:

```
4 Q  PTR  _airdrop._tcp.local     <- all four from OUR OWN address
3 Q  PTR  _spotify-connect._tcp.local
2 Q  PTR  _applicationServicePairing._tcp.local
2 Q  PTR  _appSvcPrePair._tcp.local
```

**Nobody was asking.** The only `_airdrop._tcp` queries on the link were Tarish's own
browse looping back to itself. A perfect answer to a question no peer asks is
invisible.

BLE is what makes them ask. An AirDrop sender broadcasts a BLE beacon; receivers that
see it bring up AWDL and begin the mDNS exchange. Without the beacon the Wi-Fi side
never starts, no matter how correct it is.

## Where BLE lives, and why not in the daemon

Not in `libmosey`. That was checked rather than assumed: the library links exactly
four things —

```
libdl.so   liblog.so   libc.so   libm.so
```

— no Bluetooth library, no binder. It has 19 strings matching `bluetooth`, and every
one of them is a libpcap link-layer type name (`BLUETOOTH_HCI_H4_WITH_PHDR`,
`Nordic Semiconductor Bluetooth LE sniffer frames`) from an embedded capture-file
writer. Against 358 AWDL and Wi-Fi strings. It is a Wi-Fi component.

Google draws the line in the same place: their app carries `BLUETOOTH_PRIVILEGED` in
its privileged-permissions file, and the native daemon does not. So BLE belongs in the
app, and `tarishd` keeps `NET_ADMIN`/`NET_RAW` for the Wi-Fi side and no Bluetooth
access at all.

## The advertisement

Manufacturer-specific AD structure, Apple's company ID `0x004C`:

```
05                  AirDrop message type
12                  length of the remainder (18)
00 00 00 00 00 00 00 00
01                  version
aa aa               SHA-256(Apple ID)[0..2]
pp pp               SHA-256(phone)[0..2]
ee ee               SHA-256(email)[0..2]
ee ee               SHA-256(email2)[0..2]
00                  trailer
```

The identifier fields are **two-byte truncated SHA-256 hashes**. A receiver hashes its
own contact identifiers and compares, which is how contacts-only mode works. Two bytes
collide constantly by design — this narrows the field, it does not prove identity, and
the real check happens later over TLS.

Tarish advertises all four slots as zero. That is an honest "visible to everyone": we
claim no identity rather than guessing at one, and a receiver in contacts-only mode
correctly ignores us.

### Provenance

Apple publishes no specification for this. The layout was reverse-engineered against
real Apple devices in **GoOpenDrop**, by the same author, and is reimplemented here from
that description rather than copied — see [CREDITS.md](CREDITS.md).

It is worth saying why that mattered. The previous attempt at the mDNS TXT record was
*guessed* from a nearby service's fields and was wrong twice, costing several
build-and-flash cycles. This layout is measured, so it is being treated as the
authority.

## The other direction: reading an iPhone's state off the air

The beacon above is what we *send*. Since 2026-09-25 the same scan also *reads* one Apple
message, and it is the reason a peer that locked or left now disappears from the send list in
seconds instead of the 75 minutes its mDNS TTL would keep it.

Every iPhone in range advertises **Nearby Info**, type `0x10`, continuously:

```
10 05 <FLAGS> <ACTION> <auth tag ×3>
```

**Bit `0x40` of FLAGS is set exactly while the device will take an AirDrop** — AirDrop on
*and* unlocked. Locking and switching Receiving Off both clear it, which is why stock labels
both states "screen off". Measured 2026-09-25 on two iPhones with different action bytes,
one action at a time; the table and provenance are in the daemon's
`protocol/src/apple.rs`, which is also where the bytes are decoded. Apple publishes none of
this.

What the app does, and only this:

- `TarishBleService` adds a scan filter for Apple manufacturer data whose first message type
  is `0x10`, beside the existing `0x05` filter.
- Each sighting is forwarded to `ITarishService.reportAppleAdvertisement` — at once when the
  bytes for that address change (that *is* the event), otherwise every 800 ms as a keep-alive.
  The daemon treats an address it has not heard for two seconds as gone.
- The tile reflects `TarishPeer.state`: `STATE_SCREEN_OFF` dims it, adds a red "screen off"
  line, and a tap explains instead of failing. The daemon takes the peer out of the list a few
  seconds later if it stays that way, and it is back the moment the device is receptive again.

Two things the address cannot do, so the app does not try: it is random and **rotates on every
state change** (both phones rotated at lock and at Everyone-on), and the auth tag is
resolvable only by devices holding the owner's iCloud keys. So nothing here maps a BLE address
to an mDNS peer. The daemon counts devices *present* and *receptive*, probes every AirDrop peer
when either count drops, and labels peers only when the count covers all of them and none is
receptive — exact with one iPhone in range, withheld when it would be a guess.

Not measured yet: a Mac's flags, and whether a locked iPhone keeps answering mDNS probes
(which is what decides the two-iPhone case).

## What is not implemented yet

- **Responding to a beacon.** Tarish scans and logs sightings; it does not yet use one
  to wake anything, because `tarishd` already holds AWDL continuously. That is more
  power than Apple spends, and a later revision should let the beacon drive it.
- **Contacts-only mode.** Requires real identifiers to hash, which requires the UI and
  a decision about where they are stored.
- **The 31-byte budget.** Apple's manufacturer data is 20 of the 31 available bytes.
  Nothing else may be added to this advertisement casually — no device name, no
  service UUID. The display name travels in the AirDrop protocol, not here.
