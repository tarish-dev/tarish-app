# Policy: MDM control, and behaviour under VPN lockdown

Two related pieces of control, agreed 2026-09-01.

- **What an administrator may turn on and off**, per protocol and per direction.
  **Implemented** — see the status table below.
- **What happens when "Block connections without VPN" is enabled**, which is the harder
  half and the one with a security argument attached. Implemented in part; the full
  reasoning lives in the grapheneos repo, `docs/VPN-LOCKDOWN.md`.

They connect at one point: the administrator decides whether Tarish may operate under
lockdown at all.

## Status — what is actually built

This file said "neither implemented yet" long after the first half shipped, which is worth
recording as its own small lesson: a design document that is not updated when the design
lands becomes an argument against the code.

| | state |
|---|---|
| `res/xml/app_restrictions.xml` with five typed keys | **built** |
| `APP_RESTRICTIONS` metadata in the manifest | **built** |
| `RestrictionsManager.getApplicationRestrictions()` read in `PolicyStore` | **built** |
| managed value PINS, absent key leaves the user's choice | **built** — `containsKey`, not a default comparison |
| unrecognised managed value is read as OFF | **built** — a restriction we cannot honour is read restrictively |
| the UI naming the SOURCE ("set by your organization") | **built** — `*Managed` flags |
| the daemon enforcing the merged policy | **built** |
| **reacting to `ACTION_APPLICATION_RESTRICTIONS_CHANGED` without a restart** | **NOT built** — a policy change applies the next time the app reads it |

No MDM-specific code, and that is the point of the design below: these are AOSP managed
configurations, so Intune, Workspace ONE, SOTI, MobileIron and Jamf all set them through the
same platform API. Nothing here is written against any one vendor, and none of it has been
tested against a real console — the mechanism is standard, the integration is unverified.

---

## 1. MDM control

### Use the platform mechanism, not a vendor one

Android **managed configurations** (app restrictions) are part of AOSP, so every MDM —
Intune, Workspace ONE, SOTI, MobileIron, Jamf — can set them, because they are all
calling the same platform API. One implementation covers all of them.

```
res/xml/app_restrictions.xml          the typed schema
AndroidManifest: <meta-data android:name="android.content.APP_RESTRICTIONS" .../>
RestrictionsManager.getApplicationRestrictions()   read
ACTION_APPLICATION_RESTRICTIONS_CHANGED            react without a restart
```

They are set by a device owner or profile owner. On a device with nothing enrolled
there is no DPC, nothing sets them, and the defaults apply — which is the correct
outcome, not a gap.

### The keys

Per protocol and per direction, expressed as *choice* rather than pairs of booleans:
choice fields render as a single dropdown in an admin console, whereas two booleans
per protocol invite the nonsensical combination and double the surface.

| key | type | default |
|---|---|---|
| `airdrop` | off \| receive_only \| send_only \| both | both |
| `quickshare` | off \| receive_only \| send_only \| both | both |
| `require_confirmation` | bool | **true** |
| `vpn_lockdown_sessions` | bool | **false** |
| `lockdown_idle_seconds` | int | 300 |
| `lockdown_stall_seconds` | int | 30 |
| `lockdown_max_seconds` | int | 1800 |

`require_confirmation` defaults true and an administrator turning it *off* is turning
off the per-transfer accept prompt. That is theirs to decide, but the default is the
safe one.

### Enforcement belongs in the daemon, not the UI

A hidden button is not a policy control. `tarishsharingd` is what advertises, browses,
accepts connections and writes files, so policy has to reach it and it has to be the
thing that refuses. An app that merely hides the affordance is bypassed by killing the
app or connecting to the daemon directly.

The daemon cannot read managed configuration itself — it is native, uid 7500, with no
framework access, for the same reason it cannot do BLE. So:

```
MDM -> DPC -> app (RestrictionsManager) -> AIDL -> tarishsharingd enforces
```

and the daemon **defaults to denied**, opening only on being told. A daemon that has
never heard from the app shares nothing.

Note this is the opposite of the radio gate, where an unset `tarish.awdl.wanted` means
radio-ON. That default is right there — a missing property should not silently disable
sharing — and wrong here, where a missing policy must not silently permit it. Do not
copy the pattern across.

---

## 2. VPN lockdown

### What lockdown actually does

Not destination-based. It is a per-uid rule enforced in eBPF on the cgroup egress hook
(`packages/modules/Connectivity/bpf/progs/netd.c`), and on ingress the rule is
literally "drop unless it arrived on `lo`". Older releases did the same with iptables
owner-uid matches. Traffic either left through the tunnel or it did not.

Two details from reading that program matter more than the general shape:

**Multicast is dropped before the system-uid escape.**

```c
if ((uidRules & LOCKDOWN_VPN_MATCH) && is_multicast(skb, kver)) {
    return DROP;
}

if (is_system_uid(uid)) return PASS;
```

mDNS is multicast, for AirDrop and Quick Share alike. So under lockdown *discovery*
dies first, and independently of everything below.

**The exempt sets of the two gates in this file are different**, which is a trap in
both directions:

| gate | exempt set | uid 7500 |
|---|---|---|
| local network access | `is_system_or_root` — uid 0 and 1000 only | **caught** |
| VPN lockdown, unicast | `is_system_uid` — uid < 10000 | **exempt** |
| VPN lockdown, multicast | none — checked before the exemption | **caught** |

Reading only the first two rows is how this document came to claim, wrongly, that we
escape lockdown. The multicast check runs above the exemption, so the exemption is real
and irrelevant for discovery. See the next section.

### MEASURED: uid 7500 is NOT subject to lockdown at all

This section has now been wrong twice from source reading and is settled by
measurement. WireGuard installed, a real profile imported, always-on with lockdown
enabled, rebooted so `Vpn.loadAlwaysOnPackage()` picks it up:

```
7500   PERMISSION_ACCESS_LOCAL_NETWORK PERMISSION_INTERNET     <- permission map only
                                                                  NO LOCKDOWN_VPN_MATCH
lowest uids carrying lockdown:  1002, 1027, 1068, 10000
total uids under lockdown:      200
```

And behaviourally, with lockdown active and NO tunnel up at all:

```
mosey0: up      mdns rx: 11      answered: 6      EPERM: 0
```

Discovery works. Multicast is neither dropped nor degraded.

**Why is NOT settled.** Three explanations have been tried and two are definitely
wrong. It is not `is_system_uid` (that exemption sits below the multicast check), not
the `UidRangeParcel(1, upper)` range (which would cover 7500), and not "only uids with
packages" — measured:

| uid | | lockdown |
|---|---|---|
| 1000 `system` | has packages | **no** |
| 1001 `radio` | has packages | **no** |
| 1002 `bluetooth` | has packages | **yes** |
| 1027 `nfc` | has packages | **yes** |
| 7500 `system_ext_tarish` | no package | **no** |
| 10000+ | apps | **yes** |

1000 and 1001 have packages and escape; 1002 and 1027 have packages and are caught. So
something distinguishes core system uids from the rest, and this document is not going
to guess at it a fourth time. **The measurement is the fact; the mechanism is open.**

Practical consequence of not knowing the mechanism: do not assume this survives an
Android version bump. Re-measure.

**This is the same fact that cost us the Connectivity patch**, seen from the other
side:

| | consequence |
|---|---|
| no package -> `PermissionMonitor` never grants it the local-network bit | had to patch |
| no package -> `Vpn` never puts it in a lockdown range | exempt for free |

One architectural decision, opposite outcomes at two gates. Worth remembering before
assuming anything about how a third gate treats us.

### This is a gap OUR ARCHITECTURE opened, and it predates the AID

Not caused by choosing uid 7500. In the same measurement uid **9999** (`nobody`) was
also absent from the lockdown map, so the daemon was equally exempt before the AID
existed. What creates the exemption is the decision that the untrusted half is a
**native daemon rather than an app** — which has been true since the first commit.

Stated plainly, because it is the reason this work matters: a person enables "Block
connections without VPN", believes their device cannot move data off itself outside
the tunnel, and Tarish can still advertise, discover and transfer files to a device
across the room. That is a data-exfiltration path under a control they deliberately
switched on. Narrower than a rogue app phoning home, since it is local rather than
internet-facing, but data still leaves on a path the user believes is closed.

So the session model is **remediation, not a feature**. Its priority is not "make Tarish
usable under lockdown" but "stop Tarish being the thing that undermines lockdown".

### So the work is to honour lockdown, not to bypass it

There is no hole to open — we already have one, and we did not ask for it. A person
enabling "Block connections without VPN" believes traffic is blocked; a daemon that
keeps advertising and answering mDNS because it happens to have no package is not
something to quietly benefit from.

> **CORRECTED 2026-09-24. Two claims below this point were wrong, and both were the kind an
> audit would take at face value.**
>
> **"No framework patch, and none should be written."** Two were. `0001-grant-tarish-daemon-
> local-network-access` is REQUIRED — since Android B only uid 0 and 1000 may reach the local
> network, and a native AID can never earn the bit because `PermissionMonitor` derives it
> from packages. `0002-exempt-tarish-daemon-local-traffic-from-vpn-lockdown` was also
> written, and is a **no-op**: it keys on `LOCKDOWN_VPN_MATCH`, which uid 7500 never carries,
> for the same package-vs-AID reason.
>
> **"The app reads `Settings.Secure.always_on_vpn_lockdown`."** It reads **null** while
> lockdown is in force — measured on blazer. A VPN app can put the device in lockdown by
> another path, so the setting is not a reliable signal for anyone, platform-signed or not.
> **The app still cannot detect lockdown**, and the UI therefore cannot truthfully say it is
> active. Checking the `ip -6 rule` output for `prohibit` is the only reliable test, and an
> app cannot run it.
>
> What actually makes sharing work under a kill-switch is **policy routing** — an `ip rule`
> for `tlink0` above `PROHIBIT_NON_VPN`. Authoritative account, including the authenticated
> window and the keep-unlocked heartbeat: grapheneos `docs/VPN-LOCKDOWN.md`.
>
> The original text is kept below because its REASONING — that a hole we did not ask for is
> not something to quietly benefit from — is the argument the implemented design still rests
> on.

So: **no framework patch, and none should be written.** Tarish detects lockdown and
disables itself, and the session model below is what re-enables it after
authentication. Everything is enforced in our own code, which also means it is
fail-closed by construction rather than by asking netd nicely.

Detecting lockdown: the app reads `Settings.Secure.always_on_vpn_lockdown` (and
`always_on_vpn_app`), which is readable to a platform-signed app, and relays it to the
daemon alongside the rest of the policy.

### The session model

Per-transfer authentication does not fit, because **discovery is continuous**. It is
not an event to authorise; it is a state. So authentication opens a bounded session
covering discovery and any transfers inside it.

```
DISABLED ──authenticate──> ACTIVE(lease)
   ▲                            │
   ├── T_idle expired ──────────┤
   ├── T_max expired ───────────┤   regardless of activity
   ├── app died ────────────────┤
   └── daemon restarted ────────┘   lease is in memory, never persisted
```

> **What shipped is simpler than this.** One window of 600s, capped in the daemon, with no
> separate idle or stall clock — plus a keep-unlocked heartbeat the design below does not
> have, which closes the window on silence rather than on a timer. The lease diagram's
> `app died` and `daemon restarted` arrows are both implemented (binder death, and a clear at
> daemon startup). `T_idle` and `T_stall` are not. Treat the timers below as a design that
> was simplified, not as a description of the code.

**Three timers, and the third is not optional:**

- `T_idle` (300s) — counts down when nothing is transferring.
- `T_stall` (30s) — during a transfer, no progress for this long and it stops counting
  as a transfer; the idle clock resumes.
- `T_max` (1800s) — absolute ceiling **regardless of progress**.

`T_max` exists because a stall timer alone does not close the hole. A peer sending one
byte every 29 seconds never stalls and would hold the session open forever, so
"in transfer" would be an unbounded extension primitive. `T_max` bounds it absolutely.

**The clock lives in the daemon**, not the app — the app can be frozen, killed or lied
to, and the enforcement point must own the timing. Use `CLOCK_BOOTTIME`: wall time
would let a clock change extend a session, and `CLOCK_MONOTONIC` stops across suspend,
which would let one survive a night in a pocket.

**DISABLED means silent**, not "visible but refusing": no advertising, no browsing, no
accepting connections. Under lockdown Tarish is not on the air at all until a human opens
it. Easier to reason about and easier to verify.

**The cost, which the app must state rather than let people discover:** under lockdown
you cannot receive spontaneously. A sender finds nothing unless you have already opened
a window. That is correct — an always-available receive path is precisely the standing
hole the setting exists to prevent — but it is a visible behaviour change.

> **Not implemented yet — planned.** Per-transfer authentication under lockdown is a design,
> not shipped code: there is no `BiometricPrompt` in the app today. Today, lockdown makes
> Tarish silent (no advertising/browsing/accepting) until a human opens a discoverable window;
> it does **not** yet require a biometric or device-PIN per transfer. See `docs/TODO.md`
> ("lockdown"). The intended design below stands as the plan.

The intended authentication is `BiometricPrompt` with device-credential fallback, so face,
fingerprint and PIN would all work and the platform decides which is acceptable. The app
prompts, because the daemon has no UI; the daemon enforces, because the app can be
bypassed.
