# Integrating `tarishd`

What a platform integrator has to provide, and the failures worth knowing about
before hitting them. Everything here was found the hard way on GrapheneOS for
Pixel 10; none of it is GrapheneOS-specific.

## What tarishd needs from the platform

**An AWDL library exporting the `libmosey_daemon_ffi.so` soname.** Note the wording:
what tarishd needs is the SONAME and its five FFI symbols (`mosey_start_5`, `stop`,
`update`, `dump`, `reset`), not any particular implementation of them. The name is
Google's, because the ABI was recovered from Google's blob, and it has stayed for
compatibility. **It does not mean you must ship Google's code.**

Two implementations provide it:

| | what it is |
|---|---|
| **tlink** | ours, Apache-2.0, `tarish-link` / crate `tlink-mosey-shim`. No Google userspace |
| **libmosey** | Google's blob out of the Pixel vendor image |

tlink is what Tarish itself ships, and a device running it has **zero Google
components in the AWDL path**. If your reason for using Tarish at all is not
depending on Google's stack, this is the line where that is won or lost — so do not
read the soname as a dependency on the vendor blob.

Either way, tarishd tries the plain soname first (so the dynamic linker's own search
applies), then `/system_ext/lib64`, `/vendor/lib64`, `/system/lib64`, and honours
`TARISH_MOSEY_LIB` as an override. **Shipping and pinning whichever one you choose is
the integrator's job, not the daemon's** — tarishd only requires that one is there,
and deliberately does not care which.

**A kernel driver.** `wonder.ko`, bound to the Wi-Fi driver. On Pixel 10 that is
every model except the 10a, whose `bcmdhd4383` has no `wondertap` support at all.
Note the driver is kernel-version-pinned (vermagic plus a RANDSTRUCT seed), so it
cannot be carried between kernels — if a vendor image drops it, an archived copy
does not help.

**A product package and SELinux policy.**

```make
PRODUCT_PACKAGES += tarishd
```

```
sepolicy/tarishd.te
sepolicy/file_contexts
```

## Install `file_contexts`, not just the `.te`

This is the one that wastes a day.

`sepolicy/tarishd.te` defines the domain; `sepolicy/file_contexts` labels
`/system_ext/bin/tarishd` so `init` can transition into it. Install only the first
and **the build still succeeds**, the domain is present in the compiled policy,
and `tarishd` runs — in `init`'s domain, not its own. Everything looks right except
that the domain is inert.

Many integration paths handle `.te` and `seapp_contexts` but not
`file_contexts`, because apps are labelled through `seapp_contexts` and daemons
are the first thing that needs the other file.

## The SELinux policy is not negotiable, and trimming it costs cycles

`tarishd.te` is derived from the vendor daemon's own policy. Every rule is there
because the same work through the same library needs it. Three rounds of
trimming what "looked unnecessary" produced three failures, each costing a full
build-and-flash:

| Removed | Symptom |
|---|---|
| `map` on `packet_socket` | `PcapError("can't mmap rx ring: Permission denied")` — the library uses libpcap with `PACKET_MMAP` |
| all `allowxperm` rules | `avc: denied { ioctl } ... ioctlcmd=0x54ca` — `TUNSETIFF`, so the interface could not be created **even though plain `ioctl` was allowed** |
| — | see below |

**`allowxperm` is easy to miss entirely.** Granting `ioctl` on a class is not
enough on Android: individual command numbers must also be whitelisted, and the
denial reports the command (`ioctlcmd=0x...`) rather than saying an xperm is
missing.

Only trim a rule with a denial log proving it unused.

## tarishd deliberately cannot execute anything

The link-local route is added with `RTM_NEWROUTE` over netlink rather than by
running `ip`. Exec'ing it needs

```
allow tarishd system_file:file execute_no_trans;
```

which lets the daemon run **any** system binary — far too broad a grant to buy
one route. Sixty lines of netlink needs no permission beyond the
`netlink_route_socket` already held, so the domain grants no exec at all. If you
are tempted to add that rule, you are solving the wrong problem.

## The routing trap

An address on the interface is not enough. Android routes by **fwmark** and gives
a new interface its own routing table — which starts **empty**. `connect()`
returns `ENETUNREACH` despite a valid address and a reachable neighbour in the
table.

tarishd adds `fe80::/64` to the interface's own table at startup. If you see
`ENETUNREACH` on a link that otherwise looks healthy, check the per-interface
table rather than `main`.

## The device needs a regulatory country

tarishd refuses to start without one, on purpose. A device that has never
associated with Wi-Fi reports `00` — the world domain — and channel 149 is not
permitted there. The vendor library **accepts** `00`, logs a bring-up that looks
entirely healthy (`Starting radio`, `Switching channel to Ch149`, `GET_CAP` …)
and then returns NULL. Nothing says "wrong country".

Order: `persist.tarish.country`, then `ro.boot.wificountrycode`, then
`persist.vendor.wifi.country`. To set one explicitly:

```bash
setprop persist.tarish.country QA
```

## Things that look like failure and are not

- **`wonder0` has no address.** The usable interface is **`mosey0`**. `wonder0`
  is the underlying wiphy netdev.
- **The interface disappears.** The AWDL session lives exactly as long as the
  process holding the handle. That is why tarishd does nothing but hold it, and why
  a client app must never be the holder.
- **ICMP gets no reply.** Apple devices generally do not answer pings on AWDL.
  AirDrop is TCP to a port discovered over mDNS, so a silent ping is not a
  broken link.

## Verifying an install

```
init.svc.tarishd = running
context        = u:r:tarishd:s0        user = system
tarishd: AWDL session up, handle=0x..., channel=149
tarishd: route: fe80::/64 dev mosey0 table <ifindex>
mosey0  fe80::.../64
```

with **zero** AVC denials for `tarishd` and **zero** notifications. If the library
refuses to start, read logcat for `mosey_daemon` before touching any code — it
reports how it parsed every argument, and each wrong one names its own mistake.
See [MOSEY-FFI.md](MOSEY-FFI.md).
