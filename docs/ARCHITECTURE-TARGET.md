# The shape this should be

Not a plan of work. A decision record: where the privilege boundary belongs, why the
current one is in the wrong place, and the two questions still open.

Nothing here is built. `docs/ARCHITECTURE.md` describes what exists today.

## Where we are

| | privilege | does | parses hostile input |
|---|---|---|---|
| `tarishd` | `system`, `CAP_NET_ADMIN`, `CAP_NET_RAW` | libmosey FFI, nl80211, brings up `mosey0`, routes and fib rules | no, by design |
| `tarishsharingd` | uid 7500, no capabilities | AirDrop mDNS/TLS/HTTP/plist/cpio; Quick Share protocol; writes received files | everything |
| `TarishApp` | **platform certificate, privileged** | BLE, Bluetooth sockets, UI, prompts | some |

The first two are a deliberate split and a good one: capabilities in the process that
parses nothing, parsing in the process that holds nothing.

**The app being privileged was never decided.** It holds a platform certificate for one
reason: `android.os.ServiceManager.getService()` is the only way an APK can reach a native
binder service, it is a hidden API, and hidden-API access comes with the platform
signature. Once signed that way every other privilege is free, so `BLUETOOTH_PRIVILEGED`
and `NETWORK_SETTINGS` were added without anything pushing back. `BLUETOOTH_PRIVILEGED` has
no call site at all.

That is the failure this document exists to correct: not a wrong decision, an absent one.

## Where it belongs

**The boundary is the radio, not the protocol.** Only AWDL needs a capability. Everything
above it is ordinary networking and framework API.

    tarishd            privileged, in the OS image, three jobs and no more
    Tarish (APK)       ordinary unprivileged app; Rust core over JNI

`tarishd` reduces to:

1. start and stop the AWDL session — libmosey FFI, nl80211 vendor commands
2. bring up `mosey0`, install `fe80::/64` in its table and the **uid-scoped fib rule**,
   for the uid it reads from `SO_PEERCRED` on the control socket -- nothing hardcoded
3. report the interface index and link-local address, because the index changes every
   session and anything caching it breaks

It parses nothing, writes nothing, and knows no protocol.

Everything else is the app: mDNS, TLS, HTTP, plists, cpio, received files, BLE, Bluetooth
sockets, both protocol stacks, the UI.

**Quick Share then needs no OS support whatsoever.** No daemon, no image, no privilege, no
signature — a plain APK on any Android. Only AirDrop needs `tarishd`, because only AWDL needs
`CAP_NET_ADMIN`. That is a far better story than "requires a custom ROM", and it is what
Bada already demonstrates: an ordinary app doing this protocol with no platform help.

## What disappears

| gone | why it ever existed |
|---|---|
| the app's platform certificate and privapp entry | one hidden-API lookup |
| `BLUETOOTH_PRIVILEGED` | nothing. No call site |
| `NETWORK_SETTINGS` | turning Wi-Fi on as a convenience |
| AID 7500, `tarish_aid.txt`, `TARGET_FS_CONFIG_GEN` | a daemon must run as SOME uid and `nobody` is shared. Not a property we wanted |
| `/data/misc/tarish/inbox`, its init mkdir and chmod | somewhere 0700 to stage files |
| the binder hop that copies files out, and most of `FileCollector` | crossing from uid 7500 to the app |
| `patches/packages_modules_Connectivity/*` | **a native daemon can never earn local-network access** -- the BPF bit is derived from installed packages. An app has one |
| the AID/patch consistency check in `gos-tarish.sh` | keeping 7500 agreeing in two places |

The received-file path today is `daemon → /data/misc/tarish/inbox (0700) → binder → app →
Downloads/Tarish → MediaStore`. In the target it is: the app writes where it received. Two
hops and a private directory removed, and it fixes something already wrong -- a received
file currently sits somewhere no file manager can open until the app is foregrounded.

## The core stays Rust

`libtarish_protocol` is 193 tests and deliberately Android-free, which is why it is testable
on the build host with no device. **That is the asset**, and a rewrite in Kotlin would
reproduce the code and discard the evidence: the PIN derivation pinned against foreign
vectors, per-packet versus cumulative acknowledgements, the `0xFF -> "0001"` sign-extension
guard, LEGACY-mode framing, two real BLE captures. Every one of those tests exists because
it was got wrong first.

So: JNI, not a rewrite. `rust_ffi_shared` in-tree, or `cargo-ndk` with prebuilt `.so` per
ABI when the APK has to build outside the platform tree -- which it must, if MDM is to
install it. Four hazards, all known: `catch_unwind` at every entry point so no panic
crosses into the JVM; `AttachCurrentThread` for callbacks from Rust threads; a `GlobalRef`
for the callback object; `JNIEnv` is per-thread and never shared.

Keep the surface narrow -- roughly `send(fd, files, callbacks)`,
`serve(fd, callbacks)`, `confirm_pin`, `cancel`. Sockets, discovery and UI are already
Java and stay there.

## Two open questions

**1. Parsing moves into the app's uid.** Today a parser bug gets uid 7500: `inet`, its own
directory, and whatever descriptors were handed in. After the move the same bug is in a
process holding Bluetooth and BLE, the ability to command `tarishd`, and the files it
received.

**Scoped storage bounds this more than a first reading suggests**, and an earlier draft of
this document overstated it as "the user's private files". It is not: the app gets its own
directory, the `Downloads/Tarish` entries it created, and per-URI grants for files the person
picks in the share sheet. Arbitrary user files are not reachable by Android's design.

So the delta is Bluetooth, BLE, radio control and received files. Real, narrower than
claimed, and Rust carries the difference. Getting full isolation back means keeping a
protocol process -- `tarishsharingd` again, app-owned rather than init-owned;
`android:process` gives a separate process but the same uid and so is not a boundary.
**A trade to make deliberately, not by omission.**

**2. Who may command the radio.** `tarishd` exposes a Unix socket, which is public API on the
app side (`LocalSocket`) and needs no privilege to use.

An earlier draft justified restricting this by claiming an open socket would let any app
bypass a VPN. **That was wrong.** Lockdown is enforced in eBPF on the cgroup egress hook
**by uid**, not by routing -- a fib rule gets traffic onto `mosey0` and the gate still drops
it. Routing cannot defeat lockdown, so no bypass exists to prevent.

What remains is smaller and still real: AWDL is a radio, and an arbitrary app being able to
switch it on is a battery and tracking surface. If that is worth gating, SELinux is the
mechanism -- the app's own domain via `seapp_contexts`, keyed on package and signature, with
the certificate in the image's `mac_permissions.xml`. The cost is that MDM can install the
APK but only a build signed with a key the image trusts can drive AWDL. Quick Share, needing
nothing, stays open to any build.

**Gating is a choice here, not a requirement.** It was presented as one and should not have
been.

## What this FIXES, which is the part worth leading with

`docs/POLICY.md` records, from measurement, that uid 7500 carries no `LOCKDOWN_VPN_MATCH`
and is absent from the lockdown map -- as was `nobody` (9999) before the AID existed. So the
exemption is not the AID's doing. **It is created by the untrusted half being a native
daemon rather than an app**, which has been true since the first commit.

Stated as that document states it: a person enables "Block connections without VPN",
believes their device cannot move data off itself outside the tunnel, and Tarish can still
advertise, discover and transfer a file to a device across the room. Local rather than
internet-facing, but data still leaves on a path the user believes is closed.

**An app uid is >= 10000, carries the lockdown bit, and is subject to the gate.** So the
target architecture closes that gap for free, and the session model in `POLICY.md` --
`vpn_lockdown_sessions`, the idle, stall and maximum timers -- becomes unnecessary rather
than merely unfinished. It exists to remediate a hole this shape does not open.

The trade inverts with it: someone who WANTS Tarish working under lockdown then needs a
deliberate exemption (a device-owner allowlist) instead of getting one silently. Against
that document's own stated priority -- "stop Tarish being the thing that undermines lockdown"
-- that is the right way round.

Two cautions. `POLICY.md` says the *mechanism* of the 7500 exemption is unexplained after
three attempts and should be re-measured on an Android bump; do not rely on it either way.
And discovery is the first thing lockdown takes, because multicast is dropped above the
system-uid exemption -- so under lockdown an app-uid Tarish will not see peers at all, which
is a visible behaviour change and should be said in the UI rather than looking broken.

**And one consequence worth stating plainly:** listening while backgrounded needs a
foreground service, so being discoverable costs a visible notification. `tarishd`'s own
header cites avoiding that as a reason for being a daemon. Since discovery and
discoverability are already foreground-only by decision, most of that advantage is already
spent -- and a notification that appears exactly when the device is discoverable is
arguably right for a privacy tool.

## Order, when it happens

1. Drop `BLUETOOTH_PRIVILEGED`. No call site; costs nothing to prove.
2. Move Quick Share into the app over JNI. It needs no privilege and no `tarishd`, so it
   proves the JNI boundary and the build with nothing else at risk.
3. Move AirDrop's protocol layer, and shrink `tarishd` to the three jobs above.
4. Drop the platform certificate, the privapp entry, AID 7500, the inbox and the framework
   patch -- all of which are unreachable until 3 is done.

Step 2 is the one that stands alone and is worth doing on its own merits.
