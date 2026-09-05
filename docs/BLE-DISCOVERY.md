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

## What is not implemented yet

- **Responding to a beacon.** Tarish scans and logs sightings; it does not yet use one
  to wake anything, because `tarishd` already holds AWDL continuously. That is more
  power than Apple spends, and a later revision should let the beacon drive it.
- **Contacts-only mode.** Requires real identifiers to hash, which requires the UI and
  a decision about where they are stored.
- **The 31-byte budget.** Apple's manufacturer data is 20 of the 31 available bytes.
  Nothing else may be added to this advertisement casually — no device name, no
  service UUID. The display name travels in the AirDrop protocol, not here.
