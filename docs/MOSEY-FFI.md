# The AWDL ABI that `tarishd` calls — and its two implementations

`tarishd` does not implement AWDL. It `dlopen`s a library by the soname
`libmosey_daemon_ffi.so` and calls five functions. **Two different libraries provide that
ABI, and which one is on the device is an integrator's choice, not the daemon's.**

| | what it is | where |
|---|---|---|
| **`tlink`** | **our own open implementation — what ships in production** | [tarish-link](https://github.com/tarish-dev/tarish-link), crate `tlink-shim`, built as `mosey_daemon_ffi` |
| `libmosey` | Google's closed blob from the Pixel vendor image | `/system_ext/lib64/libmosey_daemon_ffi.so` |

> **This file used to be titled "the vendor AWDL ABI" and opened by stating that
> `libmosey_daemon_ffi.so` ships in the Pixel vendor image.** Read on its own it says Tarish
> depends on a closed Google component for AWDL, which is the project's central claim
> inverted — and it is the claim external reviewers check first. AWDL in production is ours.
>
> Everything below still describes `libmosey`, and deliberately so: it is the reference
> implementation, it is the fallback, and it is the thing the ABI was recovered *from*. tlink
> is compatible with it because this document is what tlink was written against. Read it as
> "here is the contract, and here is the implementation it was reverse-engineered from" — not
> as "here is what your phone is running".

`libmosey_daemon_ffi.so` ships in the Pixel vendor image and contains the AWDL
protocol: master election, availability-window synchronisation, peer tables and
action frames.

This file is the reference a maintainer needs. It is **not** a header shipped by
the vendor — every line below was recovered by observation, and the vendor is
free to change it. That is precisely why there is a second implementation.

## The library

```
/system_ext/lib64/libmosey_daemon_ffi.so
```

Links only `libc`, `libdl`, `liblog`, `libm` — no binder, no framework. It speaks
nl80211 directly to `wonder.ko`. Exports exactly five functions behind a version
script (`@@VERS_1.0`), which is a deliberate integration surface rather than
leaked internals:

| Symbol | Args | Notes |
|---|---|---|
| `mosey_start_5` | 7 | returns an opaque session handle, `NULL` on failure |
| `mosey_update` | 4 | first arg is the handle |
| `mosey_stop` | ~1 | takes the handle |
| `mosey_reset` | 1 | byte-sized enum, values 1 or 2 |
| `mosey_dump` | 0 | writes state to the log |

Google's own `mosey_server` resolves four of the five by `dlsym` — it does not
link them either — which is good evidence this is a supported way in.

## `mosey_start_5`

```c
void *mosey_start_5(
    const uint8_t *channels,     // e.g. {149}
    uint64_t       n_channels,
    uint32_t       max_mdns,     // observed 0x7fffffff
    const char    *country,      // exactly two letters, e.g. "QA"
    uint32_t       op_mode,      // see below
    const uint8_t *config,       // serialised StartMoseyConfig protobuf
    uint64_t       config_len
);
```

`op_mode` selects the entire radio backend:

| value | backend |
|---|---|
| 1 | `Preexisting { iface_name: "radiotap0" }`, `ArtIoctl` |
| **2** | `AsNeeded { iface_name: "wonder0", wiphy_name: "wonder" }`, `Netlink` — the real one |

`StartMoseyConfig` is a protobuf. Four bytes are enough and are what Google's own
call passes:

```
08 01   field 1 = 1   is_dbs_supported
30 01   field 6 = 1   rate_adaptation
```

The library names the rest of the message in its log: `sta_channel_freq`,
`daemon_amsdu`, `driver_ampdu`, `channel_hopping`, `maxAmsduSizeMode`.

## The library is its own oracle

`libmosey` links `liblog` and reports how it parsed every argument. **Read logcat
for `mosey_daemon` before touching any code** — each wrong argument names its own
mistake:

```
n_channels too large  ->  "channels=[8, 1, 48, 1]"    your proto bytes read as channels
op_mode = 4           ->  "Invalid op mode: 4"
proto in the wrong arg->  "Couldn't deserialize given bytes into a proto"
country wrong length  ->  "Provided country code is not a valid 2-letter country code"
```

A successful start looks like:

```
Creating mosey daemon with channels=[149], max mdns=2147483647,
    cc=Some(CountryCode("QA")), radio=AsNeeded { iface_name: "wonder0", ... }, config=Netlink
Decoded StartMoseyConfig: is_dbs_supported=true, ... rate_adaptation=true
protocol::peer: New peer at ... discovered
protocol::sync: New master has been elected: ...
```

## Stability

The `_5` in `mosey_start_5` is a **version marker, not an arity** — the function
takes seven arguments. That implies at least five prior revisions, so treat the
ABI as versioned and unstable across vendor images: `dlsym` the exact name and
fail loudly rather than degrade.

`tarishd` isolates every call to this library in `src/mosey.rs` for that reason — one
module, so that swapping the implementation underneath it is a build-time choice and
touches nothing else. That is what makes shipping tlink instead of `libmosey` a
one-file change.

## Provenance

Recovered by breakpointing Google's `mosey_server` during real AirDrop transfers
and reading the argument registers, then confirmed by calling the library
directly. The full investigation — including the traps and several wrong turns —
is kept with the OS integration, alongside the raw captures in `awdl/captures/` — that
material is in the private integration repo, not here.
