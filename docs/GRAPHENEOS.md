# Integrating Tarish into GrapheneOS

Written for GrapheneOS because that is where Tarish was developed, but nothing here is
GrapheneOS-specific — the same steps apply to AOSP or any build you control.

Tarish is **not** an app you can sideload. It is two `init` services with their own SELinux
domains, a dedicated Android ID, and one framework patch. None of that can be granted by
an APK, which is the whole reason the daemon exists: it holds the transport so the UI does
not have to be a permanently-running foreground service.

## What you are adding

| | |
|---|---|
| `tarishd` | holds the AWDL link. `CAP_NET_ADMIN`, `CAP_NET_RAW`, uid `system` |
| `tarishsharingd` | parses everything a stranger sends. **No capabilities**, uid 7500 |
| `TarishApp` | the share sheet, consent, and the framework radio work. Privileged, in `system_ext/priv-app` |
| SELinux policy | **three** domains — `tarishd`, `tarishsharingd`, `tarish_app` — plus file/service/property contexts |
| AID 7500 | `system_ext_tarish`, so the daemon owns its own files |
| one framework patch | `packages/modules/Connectivity` — see step 7 |

The split is the security design: the process holding `CAP_NET_ADMIN` never parses remote
input, and the process parsing remote input holds nothing. See
[ARCHITECTURE.md](ARCHITECTURE.md).

The app has its own domain rather than sharing `platform_app` with every platform-signed app
on the device, and both daemons' rules were derived from `auditallow` measurement of what they
actually exercise rather than from the `net_domain()` macro. `tarishsharingd` consequently has
no `rawip_socket`, no `icmp_socket` and no `netlink_route_socket` at all.

## Before you start

- A build tree you can modify and rebuild.
- A device you can flash. **A `user` build will not work for development** — you cannot
  read the daemon's logs or push a rebuilt binary. Use `userdebug`.
- The AWDL half additionally needs a Pixel with Google's `wonder.ko` — the kernel MAC
  module, which is silicon-tied and is not reimplemented — plus **some** library providing
  the `libmosey_daemon_ffi.so` ABI. That is either
  [tlink](https://github.com/tarish-dev/tarish-link), which is ours and open and is what
  production ships, or Google's `libmosey` from the vendor image, which is the reference and
  fallback. **Google's library is not a requirement**; this line used to say it was. See
  [MOSEY-FFI.md](MOSEY-FFI.md).
- Quick Share needs neither — it is plain Wi-Fi and works on any device.

---

## 1. Copy the source in

```bash
cp -r tarish-daemon /path/to/aosp/vendor/tarish
```

Anywhere works; `vendor/tarish` is used throughout this document and in the SELinux
policy's own comments.

## 2. Build the packages

Create `vendor/tarish/tarish.mk`:

```make
PRODUCT_PACKAGES += \
    tarishd \
    tarishsharingd
```

and inherit it from your device makefile:

```make
$(call inherit-product, vendor/tarish/tarish.mk)
```

On a Pixel with adevtool, the device makefile is
`vendor/google_devices/<device>/<device>.mk` — **which is generated**. Anything you add
there is destroyed by the next `adevtool generate-all`, so re-apply after every vendor
extraction. This is the most common way an integration silently reverts.

## 3. Build the app

The daemon moves the bytes; the app is the share sheet, the consent prompt, and the radio
work only the framework can do. Neither half is useful alone.

```make
PRODUCT_PACKAGES += TarishApp
```

It installs as a **privileged** app in `system_ext/priv-app/`, and needs two XML files
alongside it:

| file | goes to | why |
|---|---|---|
| `privapp-permissions-dev.tarish.app.xml` | `system_ext/etc/permissions/` | allow-lists the privileged permissions. **Without it the build fails**, by design — a privileged app whose permissions are not declared will not boot the device |
| `default-permissions-dev.tarish.app.xml` | `system_ext/etc/default-permissions/` | pre-grants the runtime permissions. A system sharing component that stops to ask for Nearby Devices on first use is not a sharing component |

Both ship in the app repository under `etc/`.

> **Two traps that make a correct build look broken.**
>
> **Default permissions are granted only when Android decides the image is an upgrade**, and
> it decides that by comparing the build fingerprint. Flash an image whose fingerprint the
> device already has and `DefaultPermissionGrantPolicy` never runs, so every newly added
> system package arrives with *nothing* granted — which presents as the app crash-looping
> for reasons that have nothing to do with the app. Always build with a fresh build number.
>
> **`BLUETOOTH_ADVERTISE`, `_SCAN` and `_CONNECT` are granted at first install only.**
> Flashing over existing userdata keeps the old grant state. Check with
> `adb shell dumpsys package dev.tarish.app | grep -A5 'runtime permissions'` — a real
> pre-grant shows `GRANTED_BY_DEFAULT`, which is the only thing distinguishing it from
> someone having run `pm grant` by hand.

## 4. Ship the AWDL library — and pin it

**Quick Share needs nothing here. Skip this step if AirDrop is not wanted.**

`tarishd` needs a library providing the `libmosey_daemon_ffi.so` ABI (the soname + five FFI
symbols). **In production that library is our own `tlink` shim, not Google's `libmosey`** —
the integrator pins tlink over the libmosey path (`gos-tlink.sh`); Google's `libmosey` also
satisfies the ABI and is the fallback/reference. It does not care where the file comes from: it
tries the soname, then the usual paths, then `TARISH_MOSEY_LIB`. On a Pixel `libmosey` and
`wonder.ko` are already in the stock vendor image, so a build with **zero Google packages**
still has both — using them adds nothing to the device that was not already there.

**Pin the library rather than depending on whatever the image currently has.** A vendor bump
can change the ABI underneath you with no signal, and the daemon depends on five exported
symbols and their signatures. Copy it into your own vendor directory, install it from there,
and compare hashes after every vendor extraction.

**Do not pin `wonder.ko`.** It is a kernel module carrying a vermagic and a RANDSTRUCT seed,
so it loads only into the exact kernel it was built against. An archived copy cannot be
carried forward and pinning one would give false confidence. If the vendor drops it, the
answer is a new MAC layer, not an old `.ko` — see [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md).

## 5. Declare the AID

`tarishsharingd` runs as uid 7500. Without this it runs as `nobody`, which is **shared** —
so `/data/misc/tarish` becomes readable by anything else running as `nobody`, defeating the
point of isolating it.

Add to your `BoardConfig.mk` or a device `.mk`:

```make
TARGET_FS_CONFIG_GEN += vendor/tarish/config/tarish_aid.txt
```

7500 sits inside `AID_SYSTEM_EXT_RESERVED` (7500–7999) and both daemons ship in
`system_ext`, so this is the range the platform reserved for exactly this.

## 6. Install the SELinux policy

Copy `vendor/tarish/sepolicy/` into your build's private policy directory and reference it:

```make
PRODUCT_PRIVATE_SEPOLICY_DIRS += vendor/tarish/sepolicy
```

**Copy all of it, not just the `.te` files.** Four context files come with the policy and
each fails differently and silently:

| file | without it |
|---|---|
| `file_contexts` | the domain exists but nothing ever runs in it |
| `service_contexts` | the daemon cannot publish its binder service |
| `property_contexts` | its properties get the default label and `set_prop` on its own type is still denied |
| `seapp_contexts` | the app side cannot reach it |

Do **not** put the policy in `vendor/google_devices/<device>/sepolicy/` on a Pixel. That
directory is adevtool output and is regenerated; the policy will vanish.

## 7. Apply the framework patch — this one is not optional

```bash
cd packages/modules/Connectivity
git apply /path/to/vendor/tarish/patches/packages_modules_Connectivity/*.patch
```

> **That glob applies TWO patches, and only the first one does anything.** Worth knowing
> before you read them, because the second is named
> `0002-exempt-tarish-daemon-local-traffic-from-vpn-lockdown.patch` and anyone reviewing this
> tree will stop on it — a patch that exempts a daemon from a VPN kill-switch is exactly what
> a review is looking for.
>
> **It is a no-op.** It keys on the BPF bit `LOCKDOWN_VPN_MATCH`, which `BpfNetMaps` derives
> from `intersectUids(vpnRanges, mAllApps)` — a set built out of *packages*. uid 7500 is a
> native AID with no package, for the same reason the first patch is needed at all, so it
> never carries the bit and the code path never fires. It is retained pending a decision, not
> because it works.
>
> Sharing under a kill-switch is done by **policy routing** instead, in the daemon rather than
> in the platform: an `ip rule` for the AWDL interface, scoped to uid 7500, against a table
> holding one link-local route and no IPv4. It cannot reach the internet, the LAN or the VPN's
> own subnet.

**Why it is required.** Since Android B, local network access is gated by a BPF map.
`is_local_network_access_blocked()` exempts only uid 0 and uid 1000; every other uid needs
`PERMISSION_BIT_ACCESS_LOCAL_NETWORK` in `sUidPermissionChunkMap`, which
`PermissionMonitor` derives from **installed packages**. A native daemon has no package,
so it can never earn the bit. Every mDNS `sendto()` then fails with `EPERM` — the daemon
starts, advertises, looks entirely healthy, and never sends a packet.

**`repo sync` silently discards this patch.** Re-apply after every sync and verify with
`git apply --check` rather than assuming.

The patch hardcodes 7500 and so does `config/tarish_aid.txt`. Change one and you must change
the other; they cannot disagree quietly.

See [the daemon repo's `patches/README.md`](https://github.com/tarish-dev/tarish-daemon/blob/main/patches/README.md) for the full reasoning.

## 8. Build and flash

```bash
source build/envsetup.sh
lunch <device>-cur-userdebug
m
```

---

## Verifying it, step by step

Do these in order. Each one fails in a way that looks like the next one's problem.

**The services started, in the right domains and as the right users:**

```
$ adb shell 'getprop init.svc.tarishd; getprop init.svc.tarishsharingd'
running
running

$ adb shell 'ps -A -o USER,NAME | grep tarish'
system            tarishd
system_ext_tarish   tarishsharingd
```

If `tarishsharingd` runs as `nobody`, step 3 did not take. If a service is absent, check
`logcat` for an SELinux denial on `execute_no_trans` — that is step 4.

**The binder service is published:**

```
$ adb shell service list | grep tarish
dev.tarish.ITarishService/default: [dev.tarish.ITarishService]
```

Absent means `service_contexts` is missing.

**The framework patch is live:**

```
$ adb shell dumpsys connectivity | grep 7500
7500  PERMISSION_ACCESS_LOCAL_NETWORK  PERMISSION_INTERNET
```

Absent means step 5 did not apply, or `repo sync` removed it. The daemon will run
perfectly and never send an mDNS packet.

**No denials:**

```
$ adb logcat -b all -d | grep -i "avc:.*denied" | grep tarish
```

Should be empty.

---

## Things that look like failure and are not

- **`tarishd` idles with no AWDL session.** Correct. The radio is only held while a client
  is on screen or a transfer is running — otherwise it costs battery for nothing.
- **`mosey0` does not exist yet.** It appears when the session starts, and gets a **new
  interface index every time**. Anything caching that index will break.
- **The peer list is empty on a network with client isolation.** Most hotel and guest
  Wi-Fi blocks multicast, so mDNS discovery cannot work. This is the network, not the
  build — BLE discovery is unaffected.

## Automating it, and keeping it applied

Several of these steps land in **generated** territory. On a Pixel build, `adevtool` rewrites
the device makefile and the RRO overlays, so anything written there is destroyed at the next
vendor extraction. Treat every step as something to re-run, not something to do once.

Worth scripting, and each one idempotent:

| script does | why it must be repeatable |
|---|---|
| copy the daemon into `vendor/tarish`, write `tarish.mk`, wire the `inherit-product` line | the device makefile is regenerated |
| copy the app, install both permission XMLs | same |
| install the SELinux policy and **all four** context files | see step 6 — each one fails differently and none loudly |
| check the AID in `tarish_aid.txt` against the number hardcoded in the framework patch | if they disagree the daemon starts, advertises, and silently never sends |
| install the pinned AWDL library and compare its hash with the image's | a vendor bump changes it with no signal |
| apply the framework patches | `repo sync` resets every project to its manifest revision and discards them |

That last one deserves emphasis: **patches must be applied by a script, never by hand.** A
sync quietly reverts them, and the resulting failure — an unprivileged daemon that cannot
reach the local network — looks nothing like a missing patch.

A useful property to build in: have each script offer a `--check` mode that reports state
without changing anything, so a build can be verified before it is trusted. The most valuable
thing such a check can report is **drift** — the tree built from a different commit than the
checkout now has, or a pinned library that no longer matches.

## LineageOS, and other platforms

Nothing above is GrapheneOS-specific by design, and the next target is LineageOS.

The daemon asks the platform for an AWDL library and a kernel module and does not care where
they came from. That is exactly why the vendor pin lives with the integrator (step 4) rather
than in either repository — an integrator's vendor decisions are not the daemon's business.

What changes between platforms:

- **The framework patch** (step 7) is against `packages/modules/Connectivity`, which is
  common to AOSP. The patch should apply as-is; the AID it hardcodes must match yours.
- **`system_ext` placement** assumes a partition layout that most builds share. Where it
  does not, `product` works equally well — nothing depends on which of the two it is beyond
  the paths in the context files.
- **Quick Share works anywhere.** It needs no AWDL, no vendor blob and no kernel module —
  only Wi-Fi, Bluetooth and the policy. A platform with no `wonder.ko` at all still gets a
  working Quick Share implementation, which is most of the value for most devices.

**If you maintain a platform and want this in the image, please open an issue.** It is built
to be adopted: no Google dependency, no network callbacks, an unprivileged parser process,
policy an administrator can pin, and a licence that imposes nothing.

## What this does not give you

The daemon is the transport and the protocol. **It has no user interface.** Discovering a
peer, showing a transfer prompt, and BLE advertising all need an app, because a native
daemon cannot reach framework Bluetooth or draw anything. See
[tarish-app](https://github.com/tarish-dev/tarish-app).

For AirDrop, Tarish **replaces Google's `libmosey` with its own open AWDL stack, `tlink`**
(see [tarish-link](https://github.com/tarish-dev/tarish-link)), which provides the same
`libmosey_daemon_ffi.so` ABI — so in production the AWDL userspace is ours, not Google's. The
one layer Tarish does **not** reimplement is `wonder.ko`, the silicon-tied kernel MAC module
(Google's, in the Pixel vendor image); Tarish drives it. Quick Share has no AWDL dependency.
Authoritative overview:
[CURRENT-ARCHITECTURE](https://github.com/tarish-dev/tarish-daemon/blob/main/docs/CURRENT-ARCHITECTURE.md).
