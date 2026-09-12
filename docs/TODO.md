# Open work

Ordered by what unblocks the most. Kept here rather than in a chat log so it survives.

**This file leads with what is still open.** It used to lead with "nothing listens on
the port we advertise", which was true when written and had been fixed for a long time
by the time anyone read it again — so the first thing a reader met was a blocker that
did not exist, and the live work was two hundred lines down.

## Open

**Everything about the Quick Share transport is now closed** — see Done. What is left here
is AirDrop-side discovery, two hardware/policy constraints, and questions to answer before
writing anything.

This file has now made its own documented mistake twice. The preamble above was written
because the file used to lead with a blocker that had been fixed for weeks; it then spent
another stretch leading with "the payload runs over Bluetooth, and it should not", which had
also been fixed. **Move a section to Done in the same change that closes it**, not later.

### RISK: Apple may extend its non-contact code requirement to third-party peers

**Not a bug today. A thing to be ready for, because the cost of being caught out is that
AirDrop stops working and looks like our regression.**

Recent iOS shows a matching code on both devices before an AirDrop to a non-contact
proceeds, once per device pair. **Measured behaviour as of 2026-09: it appears only between
two iPhones.** Never for Tarish, and never for stock Quick Share's AirDrop either — which
is Google's own privileged implementation on the same `wonder.ko` and `libmosey`, so this is
not about our stack being unusual.

It is coherent as an Apple-only feature: the code bootstraps a **persistent** trust binding,
which only means something when both ends have a durable cryptographic identity. A
third-party peer has nothing to bind, so it falls back to a per-transfer accept prompt.

**Why it might not stay that way.** If Apple decides third-party peers should establish the
same binding — and "more devices are coming along" is exactly the pressure that would prompt
it — then a confirmation step becomes mandatory for iOS-to-Android, and an implementation
without it is simply refused.

### Two pieces of preparation, in order of value

**1. Make an unexpected `/Ask` refusal legible.** This is cheap and worth doing regardless.
Today a peer that declines tells us very little; if Apple adds a step we would see
"transfers stopped working" and spend days on it. Log the **full** `/Ask` response — status
line, headers and body plist — whenever it is anything other than a plain `200`. We are one
end of that TLS connection, so nothing is hidden from us. A new field or a new status code
would then be obvious on the first failure instead of the tenth.

**2. Keep a stable identity possible.** A trust binding needs something to bind to, and we
currently discard ours twice per restart:

- the mDNS instance name follows `mosey0`'s MAC, which is fresh every AWDL session
- the TLS certificate is generated at each start and deliberately never persisted
  (`sharingd/src/httpd.rs`, `build_acceptor`: *"a key that never touches storage cannot be
  stolen from storage"*)

That reasoning is sound and nothing depends on continuity today. But **it forecloses the
option**, and any confirmation-once scheme would need it. Not a change to make now — a
change to know how to make, so it is a decision rather than a scramble.

### Do not re-derive this

- The prompt is **Apple-to-Apple only** as of 2026-09, confirmed against both Tarish and
  stock Quick Share. An earlier note in `tarish-libawdl/docs/FINDINGS.md` claimed it was
  already a threat to us; that was withdrawn.
- It lives **above AWDL**, in the TLS `/Ask` exchange on port 8770. It is invisible to an
  over-the-air capture and can only be observed from an endpoint — which means from our own
  daemon, not from the Pi.

### An iPhone sends nothing after /Discover, and the cause is still open

**Symptom.** The iPhone lists this device, opens a connection, POSTs `/Discover`, gets our
200, and then never sends `/Ask`. Its screen says "Waiting" indefinitely. Nothing is logged
on our side because nothing arrives.

**Measured, 2026-09-09, blazer against a real iPhone:**

| attempt | `/Discover` -> `/Ask` | outcome |
|---|---|---|
| 1 | 117 s | transferred |
| 2 | — | no `/Ask` in 3+ min |

117 seconds of silence for a 1.37 GB video is the iPhone preparing before it contacts us
again. The second case never proceeded at all.

**Two hypotheses, neither confirmed.**

1. *We advertise no codec support.* `ReceiverMediaCapabilities` was `{"Version":1}`, which
   declares nothing, so an iPhone re-encodes video before sending — minutes of silent work.
   A real Apple receiver's answer was captured verbatim (Version 3, `Codecs.hvc1` with HEVC
   profiles, `IsAirDropable`) and ours now mirrors the parts we can honestly claim. **The
   change is deployed and NOT yet shown to help** — the attempt after it produced no `/Ask`
   at all, which is either a larger file legitimately preparing or the change making things
   worse. It has not been A/B tested.
2. *Stale identity.* `mosey0` is destroyed and recreated with a **new MAC** on every radio
   acquisition, so our AirDrop instance name and link-local address change on every daemon
   restart. A peer holding the old record connects to an address that no longer exists.
   Confirmed to happen; not confirmed to be this symptom's cause.

**The trap to avoid.** Comparing `TotalBytes` between two transfers *to this device* cannot
detect re-encoding — if the sender converts for us, it converts identically every time. That
reasoning was used once to "disprove" hypothesis 1, wrongly. The comparison that carries
information is **iPhone to another Apple device**, which is reported as instant with no
Waiting prompt.

**Next step: A/B it rather than add fields.** Revert `ReceiverMediaCapabilities` to
`{"Version":1}`, send the same file, and time `/Discover` -> `/Ask`. One number decides
whether capabilities matter at all. Stacking more plist keys on an unverified theory is how
this stays open for another week.

### Off-network: the sender delivers, the receiver gets a reset

**Where it stands.** Quick Share between two devices with no shared network now negotiates
the whole way: BLE discovery, RFCOMM bootstrap, UKEY2, offer and acceptance, and a Wi-Fi
Direct upgrade with a real group. The sender then transfers the file **and reports success**:

```
sender    sent offnet8.bin (2097152 bytes in 0.3s, 6949 KB/s)
          transfer 3 complete
receiver  the sender joined from 192.168.49.147:35036
          upgraded the inbound transfer to Wi-Fi Direct
          inbound transfer 1 failed: Connection reset by peer (os error 104)
```

**Zero bytes land.** The sender writes 2 MB into the upgraded channel in 0.3s, declares the
transfer complete, and the receiver's read is reset.

**The likely mechanism, not yet proven.** This project has already solved this shape once,
in `httpd.rs`:

> Closing with unread bytes in the receive buffer makes the kernel send RST rather than FIN,
> and a client that gets RST discards the response it already received.

A sender that blasts 2 MB and closes while the peer still has unread bytes — or unsent ones
of its own — produces exactly an abortive close. `finish_cleanly()` already distinguishes
safe-disconnect v1 peers from v0 ones, and the log reads this peer as `safe-disconnect v1`,
so that ordering is worth re-reading against what actually goes out on the **upgraded**
channel rather than the bootstrap one.

**Worth checking first:** whether the receiver is reading the NEW channel at all, or still
the old one. The handover releases the prior channel on the sending side (`released the old
channel`); the receiving side's equivalent is where a reset would surface if it were reading
a socket the sender has already abandoned.

**Do not re-derive these.** Three fixes already landed here and each was real:

- `MainActivity.onGroupNeeded` was an empty method, so nobody hosted a group and nothing
  transferred off-network at all
- `WifiDirectHost.create()` was not idempotent, so a second caller destroyed a group the
  peer had already joined
- `provide_group` accepted answers nobody asked for, leaving a stale value that made the
  receiver stand up a second listener and wait for a join that had already happened

And three TEST-SETUP errors that cost time and each looked like a product bug:

- "off-network" means Wi-Fi **on but unjoined**. Disabling the radio also disables Wi-Fi
  Direct, so the group can never form
- AWDL on the sender does **not** block the upgrade — identical byte counts across two runs
  disproved it
- a harness calling `QuickShareSender.send()` without `TransferService.watch()` drives a
  different code path from the share sheet, because TransferService owns `onUpgradeNeeded`

### refreshPeers: the button does nothing, and the first diagnosis was wrong

The control is wired, the app's call returns without throwing, and the daemon appears
not to act — nothing logged, no peer dropped.

**A previous version of this entry claimed the daemon exposed only 10 transactions and
did not dispatch refreshPeers. That was wrong**, and the mistake is worth keeping:

The AIDL method count was taken with a grep that missed two methods — `getStatus`
(custom return type, so the pattern did not match) and `openReceivedFile`. The real
map, read from the generated binding rather than counted by hand:

```
getStatus=+0  setDiscoverable=+1  setActive=+2  getPeers=+3  sendFiles=+4
respondToOffer=+5  cancelTransfer=+6  getReceivedFiles=+7  openReceivedFile=+8
deleteReceivedFile=+9  registerCallback=+10  unregisterCallback=+11  refreshPeers=+12
```

So `refreshPeers` is FIRST_CALL_TRANSACTION+12, i.e. **code 13**. The probe that
"proved" it missing called codes 11 and 12 — `registerCallback` and
`unregisterCallback` — with no arguments, which fail for an unrelated reason. Never
count AIDL methods by hand; read `transactions` in the generated source.

**So the cause is unknown again.** What is now established: the binding contains the
method at the right index, and the app's call returns without an exception, which means
the transaction was accepted rather than rejected.

Next, and untested because the device on the cable is a prod build without this code:

```
service call dev.tarish.ITarishService/default 13
```

If the daemon logs on that, the daemon is fine and the app is sending something else.
If it does not, the fault is in the daemon's handler.

### Sending does not find peers: we hear their questions, never their answers

**Top priority.** Reported by multiple users as "sending is unreliable, receiving is
good", and reproduced on mustang against a MacBook in Everyone mode with awdl0 up.

Reproduced with the radio verified up for the whole window, sampled every 15s, so
this is NOT the radio gate and NOT the device dozing — both of which confounded
earlier attempts and produced false negatives:

```
t+15s .. t+90s   wanted=1  mosey0=up  Awake     (every sample)
result:          0 found, 2 lost, 64 _airdrop._tcp QUESTIONS received
```

**The asymmetry is the clue.** We receive the peer's browse questions continuously —
so the AWDL link works, we are in its cluster, and we are on a channel it transmits
on. It never sends an answer. It is browsing, not advertising to us.

Ruled out by measurement:

- not the chip: identical on mustang (Netlink, stable AWDL) and frankel (radiotap)
- not the channel: same result frozen on 6 and on 44
- not the Everyone timeout: the Mac stayed in Everyone throughout
- not a static BLE payload: Apple's own beacon payload is static too (contact hashes
  do not rotate); what rotates on a real sender is the BLE address, which Android
  already randomises for us
- not the BT adapter being wedged: cycling it did not restore discovery

**A dead beacon is NOT the cause — tested.** The service had a real bug (it never
recovered after a Bluetooth cycle, and `am stopservice`/`startservice` killed
advertising without restarting it, which invalidated several measurements taken
during this investigation). That is fixed in tarish-app 2d19065. With the beacon then
verified advertising, the radio up and Bluetooth on:

```
beacon: advertising   wanted=1   mosey0=up   bluetooth=1
60s:    found=0  lost=1  questions=15
```

Unchanged. So the beacon being absent does not explain it.

**Our mDNS stack is proven good IN THE OTHER DIRECTION, on the same link.** With the
phone in receive mode:

```
tarishsharingd::mdns: answered 8 record(s) to ["_airdrop._tcp.local/12"]
```

So the peer's queries reach us, we answer them, and it finds us — that is why
receiving works. The same link, the same interface, the same responder. Only the
reverse direction fails, and it fails because the peer never advertises.

Since a receiver advertises only after seeing and ACCEPTING a sender's BLE beacon,
and ours is confirmed advertising, the surviving explanation is that macOS is not
accepting our beacon, Receivers advertise in
RESPONSE to a sender's beacon — without one a correct mDNS responder stays invisible,
which is exactly the shape of this. Either it is not reaching the Mac or macOS is
rejecting it.

Settling it needs the air, not more inference: capture our advertisement and compare
it byte-for-byte against a real Apple sender's. The author has done this before for
GoOpenDrop and has the reference; the sniffing hardware was not to hand when this was
found.

### After a Bluetooth adapter cycle, the beacon never comes back

Found while testing the above. With Bluetooth toggled off and on:

- `settings get global bluetooth_on` returns 1, so the adapter is up
- no `advertising AirDrop beacon` line appears again
- an explicit `am startservice .../.TarishBleService` produces no line either, though
  the same command logged one before the cycle

So anything that cycles the adapter — airplane mode, a system event, the user
toggling Bluetooth — appears to leave Tarish silently not advertising, with no error
and no recovery. That would break both directions, not just sending.

Not yet isolated: whether the advertiser fails, throws, or is never re-issued. The
service record still exists in dumpsys, so the service is alive. Worth a
`onStartFailure` log and an adapter-state receiver that re-advertises on STATE_ON.



### BCM4383 AirDrop is NOT limited to 0.8 MB/s — that was the band, and it is 7x faster on 5 GHz

**Measured 2026-09-11, frankel (BCM4383) against blazer (BCM4390), 16 MiB, every
transfer hash-verified at both ends.** The only variable is the AWDL channel, forced
with `persist.tarish.channels`:

| direction | channel 6 (2.4 GHz) | channel 149 (5 GHz) |
|---|---|---|
| frankel -> blazer | 19.28s — 0.87 MB/s | 2.64s — **6.36 MB/s** |
| blazer -> frankel | 15.27s — 1.10 MB/s | 1.81s — **9.28 MB/s** |

So the ~0.8 MB/s this project has quoted for BCM4383 is a **2.4 GHz measurement**, not a
chip limit, and the OWL retransmission explanation — no active monitor mode, so frames
are re-sent up to seven times — does not account for a 7x swing that follows the band.

**How frankel ended up on 2.4 GHz is worth reading, because nothing was broken.** Each
step was right on its own:

1. AWDL goes in the opposite band from the Wi-Fi association, so it does not stand on it.
2. The association frequency can only come from the app, and raising AWDL on this chip
   destroys the association it is read from — so the daemon **remembers** the last real
   frequency and treats 0 as "no news". Without that, two devices land on opposite bands.
3. Wi-Fi was then switched off entirely. Nothing reported that, so the memory stood:
   `Wi-Fi is on 5520 MHz — putting AWDL in the other band, [6]`, and
   `not offering [[149, 44]] — same band as the 5520 MHz association`.

The daemon refused both 5 GHz channels to protect an association that no longer existed.
Fixed by carrying a third value: -1 means the adapter is off, which clears the memory and
frees 5 GHz. 0 still means "not associated, no news" and still keeps it.

**SWITCHING WI-FI OFF IS NOT HOW YOU GET THIS.** `radiotap0` belongs to the Wi-Fi driver:
it is there while the adapter is enabled and **gone within three seconds of disabling it**
— timed, checked at t+3/10/20/40s. With no tap, AWDL cannot start at all, and says so four
times:

```
Netlink  + channel [149, 44]  refused: mosey_start_5 returned NULL
Netlink  + channel [6]        refused: mosey_start_5 returned NULL
Radiotap + channel [149, 44]  refused: mosey_start_5 returned NULL
Radiotap + channel [6]        refused: mosey_start_5 returned NULL
```

An earlier version of this entry claimed the tap survives Wi-Fi going off. It does not; a
*running AWDL session* holds it open, which is what made the 5 GHz measurement above
possible and what made the claim look true.

**So the -1 "adapter is off" path is nearly unreachable, and it is not the fix.** It is
still right — refusing a band to protect a network that is not there is wrong however
rarely it happens, and the sentinel bug it exposed was real — but do not expect a speed-up
from it. With Wi-Fi off there is no AWDL to be fast.

**Which leaves exactly one route to the 7x, and it is the per-MODE channel choice.** On
4383 the radiotap path takes the physical radio whatever channel is asked for, so the
association is lost during AirDrop *regardless* of band — the entry below establishes that,
and that stock Android does the same. Follow it through: AWDL needs Wi-Fi enabled, raising
AWDL then kills the association, the client reports 0 ("not associated, no news"), the
daemon keeps the remembered 5 GHz frequency and keeps refusing 149 and 44. **frankel is
therefore pinned to 2.4 GHz for AirDrop, permanently.** The refusal protects an association
that this chip has already lost by the time it matters, and it costs 7x to do it.

The change is to choose the channel list per mode — coexistence-safe for Netlink, fastest
for radiotap — rather than from the STA frequency alone.

**The cost, and it is real:** 4383 on 149 and a 4390 peer on 6 are on disjoint channel
sets and will not discover each other. Apple peers hop both (an iPhone splits 4/16 slots
across 149 and 6), so AirDrop to the devices this is FOR still works; it is Tarish-to-
Tarish AirDrop between the two chips that breaks, and Quick Share already covers
Android-to-Android better. Not changed unilaterally — the trade is the maintainer's.

### AWDL and Wi-Fi cannot run together on BCM4383, and the fallback hides it

**Second priority, after VPN lockdown.**

The coexistence work landed and is verified on BCM**4390** (mustang): AWDL goes in the
opposite band from the Wi-Fi association and both run indefinitely. On BCM**4383**
(frankel) the same code picks the right band and Wi-Fi dies anyway, does not recover
when AWDL stops, survives a Wi-Fi toggle, and needs a reboot. The measurements are
summarised under Devices in the README.

The difference is `wondertap`. 4390 exposes it, so `wonder.ko` binds and the Netlink
path drives a real `wonder` wiphy. 4383 does not, so tarishd falls back to driving
`radiotap0` — a monitor interface, which takes the physical radio with it whatever
channel is requested.

**Is `is_dbs_supported` a lie on 4383?** We hardcode it true for every device:

```rust
const CFG_DBS_SUPPORTED: [u8; 2] = [0x08, 0x01];
```

If that chip cannot do dual-band simultaneous, the library believes it can hold both,
does not time-slice, and stands on the STA — exactly what is observed. One byte tests
it (`08 01` -> `08 00`). **Run this first**; if it is the answer, the flag should be
derived from the chip rather than assumed, the same way the STA frequency now is.

**The fallback should not fail this way.** It exists so a device without a `wonder`
wiphy still works, and it does — discovery and transfers are fine on frankel. But
costing the user their network until they reboot is not graceful degradation. Options,
in order of preference:

  1. If the DBS theory holds, fix the config and keep the fallback.
  2. Otherwise refuse the radiotap path **while Wi-Fi is associated**, and say so in
     the app — the same treatment as "AirDrop radio is not running".
  3. Only as a last resort, refuse radiotap entirely, which costs 4383 devices AWDL
     altogether.

**Testing this needs the override properties to work on a user build.** They are
gated behind properties a shell cannot set there, which is why the one-byte test has
not been run. Gate the overrides on `ro.debuggable` and it becomes seconds instead of
a signed build per hypothesis — that plumbing already paid for itself once, finding
the channel answer in two minutes rather than three build cycles.

**UPDATE — measured on a userdebug frankel, and it narrows the problem sharply.**

frankel does not have the Netlink AWDL path at all, and now we know why at module
level rather than by inference:

```
wonder.physical_name = wondertap0     (module parameter, read-only)
interfaces present:    wlan0, wlan1, aware_nmi0     <- no wondertap0
/sys/class/ieee80211:  phy0 only                    <- no AWDL wiphy
driver:                bcmdhd4383
```

`wonder.ko` loads, asks for an interface `bcmdhd4383` never creates, and registers
nothing. So AWDL there is radiotap-only, which takes the whole radio — matching OWL's
own documented limitation exactly.

**The config surface is exhausted.** `channel_hopping=true` IS honoured by the library
— captured decode, no longer an assumption — and the association still dies same-band
with `sta_channel_freq` correctly set. There is nothing left in StartMoseyConfig to
try. So the one-byte `is_dbs_supported` test below is worth less than it looked: this
is not a scheduler that needs better inputs, it is a radio path with no scheduler.

**Google ships the same bug.** Quick Share's AirDrop support on Pixel 10, 10 Pro and
10 Pro XL was widely reported (Nov 2025) to drop Wi-Fi the moment the share sheet
opens, with the network list going empty and connectivity returning when it closes.
Google closed the issue tracker report without a fix. That is our symptom, with their
implementation, on the same hardware — so this is a property of the platform rather
than of our stack, and "match stock behaviour" is not an available answer.

**Do NOT make the radiotap fallback refuse.** That was the plan until the author
tested stock Android on the same device: it drops Wi-Fi too. Refusing would trade a
working feature for an interruption stock does not avoid either, leaving us strictly
worse than the phone shipped. Make it explicit instead — tell the user the radio is
exclusive while sharing and returns afterwards. The band refusal already does this for same-band on 4390; the
radiotap path needs the equivalent.


### Always-on VPN lockdown breaks peer-to-peer, and should not just fail silently

**Priority: first.** A project requirement. **The mechanism is now designed — see
docs/POLICY.md.** Session-based rather than per-transfer, because discovery is
continuous and cannot be authorised as an event.

**Blocked on one measurement**, which decides whether any of it is needed: does uid
7500 ever carry `LOCKDOWN_VPN_MATCH`? Enable lockdown, read `dumpsys connectivity
trafficcontroller`. The bpf program exempts `is_system_uid` (uid < 10000) from the
general lockdown rules — a WIDER exemption than the local-network gate's
`is_system_or_root` — so we may be exempt already, in which case the work is to
honour lockdown voluntarily rather than to bypass it.

Android's *Block connections without VPN* (always-on VPN lockdown) is a good setting
and enterprises rightly turn it on. It also breaks every peer-to-peer transfer that
works by IP — AirDrop, Quick Share and Tarish alike — because the traffic is on a
link-local address that is not the VPN, so it is dropped. Nothing tells the person
why. The device simply stops being able to send or receive, and the app looks broken.

**Behaviour we want**

1. **Detect** that lockdown is on. Tarish is platform-signed and privileged, so the
   hidden setting is readable; that is a starting point, not a design.
2. **Under lockdown, every transfer is individually authorised by the person** —
   biometric or device PIN, per send and per receive. Not a setting, not a
   remembered choice, not a session: the enterprise turned this on deliberately, and
   the only defensible way to make a hole in it is a human opening it once, knowingly,
   for one transfer.
3. **Open the path for that transfer only, then close it again.** Fail closed: if
   tarishd or tarishsharingd dies mid-transfer, or the app is killed, the exemption must
   not outlive it. An exemption that survives a crash is precisely the hole the
   setting exists to prevent.
4. **No device credential, no transfer.** If there is no PIN and no enrolled
   biometric there is nothing to authorise with, so under lockdown Tarish refuses to
   send or receive at all. Refusing is the correct answer here, not a fallback.
5. **Say so in the app.** Tarish behaves differently under lockdown and the UI should
   state that plainly — the same reasoning as the "AirDrop radio is not running"
   card: an app that silently does less is indistinguishable from one that is broken.

**Measured: Google's own implementation fails the same way**

Tested by the author on the previous build, with privileged GMS and Play Store
installed: with *Block connections without VPN* on, **AirDrop through Google's own
stack does not work either**. Same setting, same outcome.

Three things follow, and they matter more than the original framing:

1. **This is not a Tarish deficiency.** The whole peer-to-peer class is blocked, and a
   privileged, system-integrated, Google-signed implementation is blocked with it.
   Anyone hitting this on stock Android hits it too.
2. **There is no app-level workaround to copy.** Google, with a privileged app and
   every platform integration available to them, did not solve it — so we should not
   expect to find a supported API that quietly exempts us. If one existed, theirs
   would use it.
3. **The fix is therefore a platform change, and we can make one.** Tarish ships inside
   an OS we build. Google's app could not modify netd, the bpf rules or
   ConnectivityService; we can. That is a real advantage and it cuts both ways: we
   would be putting a hole in a security control *in our own OS*, for our own app,
   which is exactly why the per-transfer human authorisation above is a requirement
   and not a nicety. A platform exemption with no human in the loop is a backdoor
   with our name on it.

**Still to measure**

- The test above used Google's stack, which runs as a privileged *app* uid.
  `tarishsharingd` is a native daemon with its own uid, and lockdown is applied over uid
  ranges. It is possible the daemon is already outside them and only the app-side
  traffic was blocked — that would change what needs building. **Answer this first.**
- Which layer actually drops the packet: the bpf owner match, an iptables rule, or
  routing. The narrowest place to make a scoped, temporary exception is whichever one
  it is, and guessing wrong means weakening more than necessary.
- Which mechanism actually opens the path, and can it be scoped to one socket rather
  than one uid? A per-uid exemption for the transfer window is much wider than it
  sounds — everything that daemon does is exempt for that period.
- Does the person get one prompt per transfer, or one per file? Per transfer.
- What does the *sender* see when the receiver is under lockdown and declines to
  authorise? It has to be distinguishable from an ordinary decline, or the sender
  retries into a wall.

**What must not be done**: a persistent exemption, a "remember this device" option,
or anything that survives a reboot. If the answer is not "a human authorised this
specific transfer, just now", the transfer does not happen.

- **Channel 149 is hardcoded, and it is not legal everywhere.** `CHANNELS = &[149]`
  (5745 MHz, U-NII-3). Permitted in Qatar, the US and much of Asia; largely **not**
  permitted for Wi-Fi in the EU. The country is now read correctly and follows the
  device, but in a region where 149 is barred the vendor library will refuse to bring
  the radio up and Tarish will fail closed with a correct country in the log -- which
  will read as a different bug than it is.

  Apple picks per region (2.4 GHz ch 6, or 5 GHz 44/149), so the fix is a channel
  table keyed on the regulatory domain rather than a constant. Until then the honest
  statement is: Tarish works where channel 149 is permitted.

- **Drop `android_logger` from the privileged half — before any production build.**

  `tarishd`'s own header states the rule: *"every dependency is part of its threat
  model, and one property read does not justify one."* It then links a full regex
  engine for log filtering. Confirmed in its runtime maps:

  ```
  libregex  libregex_automata  libregex_syntax  libaho_corasick  libmemchr  libenv_filter
  ```

  AOSP builds `libandroid_logger` with the `regex` feature and `libenv_filter`
  baked in, and ships no regex-free variant, so this arrives whether or not it is
  wanted. `tarishd` never uses filter strings — it sets a tag and a max level — so
  the whole engine is dead weight in the one process holding `CAP_NET_ADMIN` and
  `CAP_NET_RAW`.

  Fix is ~20 lines calling `__android_log_write` through libc, leaving `tarishd`
  linking only libc and the `log` facade. **Deliberately deferred:** convenient
  logging is worth more than the dependency while the daemon is still being
  developed, and swapping the logger mid-development trades a real debugging aid
  for a theoretical gain. Do it when hardening for a release build, and re-check
  the maps afterwards rather than assuming.

- **Two `unsafe` sites without a `SAFETY:` tag** — `unsafe impl Send for Session`
  (which has an untagged justification above it) and a `mem::zeroed()` on a
  `sockaddr_nl`. Both benign; tagged now, noted here because the audit that finds
  them should find zero next time.

- **Offer sizes.** `onTransferOffered` reports `totalBytes = 0`. Apple's `/Ask` carries
  file names and UTIs but no sizes, so the prompt cannot say how big the transfer is
  without inventing a number. Worth checking whether `Items` carries one on some senders.
- **One transfer at a time.** `serve()` handles connections inline, so an offer waiting on
  a person blocks the accept loop for up to 45 s. Correct for AirDrop, which does one
  transfer at a time, but it means a second peer probing during a prompt is ignored rather
  than refused.
- **Single-client `setActive`.** The foreground flag is one boolean, not per-client, so
  two clients would fight over it. There is one client today and the AIDL is ours.


### How a peer lists a dual-protocol device ONCE — now LIVE, not hypothetical

**Observed**, on two Android phones that both support AirDrop and Quick Share: the
receiver lists the sender **once**, not twice. Something correlates the two
advertisements, and we do not know what.

This was filed as a question to answer before the Quick Share migration started. The
migration is finished, so **Tarish is now exactly such a device** — speaking AirDrop to
Apple peers and Quick Share to Android ones, from one process, advertising both. If the
correlation is something we are getting wrong, an Android peer sees us twice, and nobody has
looked yet. Check what a peer's list actually shows before assuming it is fine.

What is established: the two identities share **nothing** at protocol level.

| | AirDrop | Quick Share |
|---|---|---|
| mDNS | `_airdrop._tcp.local` | `_FC9F5ED42C8A._tcp.local` |
| instance | 12 hex, rotating | 4-char endpoint id |
| BLE | Apple mfg data `0x004C` | Google service UUID `0xFE2C` |
| name in discovery | **none** -- TXT is only `flags=` | in endpoint info |

AirDrop's TXT carries no device name at all; the name arrives later as
`ReceiverComputerName` in the `/Discover` response. So a browser cannot even compare
names until after a TLS connection. Correlation therefore cannot be happening at the
mDNS layer.

**Leading hypothesis: both advertisements come from the same BLE adapter**, so a
scanner groups them by source address before either protocol is involved. That would
explain dedup with no shared identifier anywhere above the link layer.

**The experiment**, using what already exists: `TarishBleService` logs the source address
of every AirDrop beacon it sees. Add a second scan filter for `0xFE2C` and log the same
way, then watch the two phones that actually exhibit the behaviour. If both payloads
appear from one address at any given moment, the hypothesis holds.

**The caveat that could kill it:** Android uses resolvable private addresses that
rotate. If the two payloads rotate *together* the grouping still works; if they rotate
independently, address correlation cannot be the mechanism and something else is.

Deliberately **not** implemented yet. Fixing before understanding would bake in a guess,
and the last several wire-format guesses in this project were wrong while every
measurement was right.

### Understand before implementing: does AirDrop signal device state?

Reported from using iOS: AirDrop appears to carry a state indicating the screen has been
turned off. Not yet located in any wire format we hold, and **not** recorded in
GoOpenDrop -- its `ReceivedBLEBeacon.DataReceived` captures the beacon payload and
nothing ever decodes it.

Two places it could live, and we do not know which:

- **The undecoded `flags` bits.** `flags` is a capability bitmap, not a constant:
  GoOpenDrop sends `136` = `0x88` (`SUPPORTS_MIXED_TYPES | SUPPORTS_DISCOVER_MAYBE`),
  and we measured Mosey sending `489` = `0x1E9`, which is those two bits plus bits
  0, 5, 6 and 8. Four undecoded bits, and device state is a plausible occupant.
- **The eight zero bytes in the BLE beacon.** Our AirDrop advertisement carries

  ```
  05 12 | 00 00 00 00 00 00 00 00 | 01 | aa aa pp pp ee ee ee ee | 00
        ^^^^^^^^^^^^^^^^^^^^^^^^^ never explained
  ```

  copied verbatim from a measured capture, which was the right call. But eight bytes of
  always-zero is unusual in an otherwise dense format, and status flags that happen to
  be zero in the captured state would look exactly like this.

Apple also broadcasts a separate **NearbyInfo** message (type `0x10`) alongside
AirDrop's `0x05`, which is a third candidate.

**The experiment**, cheap and using what exists: `TarishBleService` already scans Apple
beacons and logs the sender address. Log the full manufacturer payload instead, then
lock and unlock an iPhone while it advertises and diff the bytes. The same method
decoded the framed cpio container in one capture.

**Why it matters beyond curiosity:** if Apple already models "device present but screen
off", then Tarish's app-open visibility rule maps onto something the protocol expects
rather than being our own invention, and a peer could show us accurately instead of
listing a device that will refuse.

### App

- [x] Share-sheet target, transfer UI, Quick Settings tile
- [x] Retire Bada — done; its Quick Share protocol was ported into `tarishsharingd`, and
      `bada/` is a read-only reference now
- [ ] BLE advertise on a device whose adapter was cycled — see the beacon entry above
- [ ] The brand kit's 47 icons and its type system (Archivo / Public Sans, JetBrains Mono
      for codes, sizes and throughput) are not applied yet; colour, themes and the mark are

### Rig

- [ ] Monitor-mode 5 GHz adapter + BLE, OWL as a controllable peer.
      See [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md).

## Done

Quick Share is finished as a transport: bidirectional, to Windows and to stock Android,
with and without a shared network.

|  | shared network | off-network |
|---|---|---|
| **send** | 22 MB/s | Wi-Fi Direct 10.5 MB/s (21.6 MB in 2.0s); Bluetooth bootstrap ~150 KB/s |
| **receive** | 21.6 MB in 0.46s | Wi-Fi Direct, group up in 3.4s on 5 GHz; Bluetooth ~115 KB/s |

`libtarish_protocol` carries 202 tests. The two rules that cost the most to learn:
**the advertiser hosts the upgrade network and the discoverer joins it** — not sender and
receiver — and **Wi-Fi LAN is a bootstrap medium, never an upgrade target.**

### A received file vanished when a stale MediaStore row held its name — SOLVED

**Symptom.** The transfer succeeded, the daemon wrote the file to its inbox, and the file
never appeared in the app. The inbox copy stayed — correctly, it is only deleted once the
file is stored — so every subsequent attempt failed the same way, forever.

**Cause.** MediaStore keeps the row and the file in separate places and they can disagree.
Delete a received file from outside the app and the row survives, still owning the path.
`IS_PENDING` is what makes this fatal: the bytes are written to `.pending-<n>-<name>` and
**clearing the flag is what renames the file onto its final path**. So the insert
succeeded, every byte copied, and the very last call failed:

```
E TarishCollect: could not store probe.bin
E TarishCollect: android.database.sqlite.SQLiteConstraintException:
                 UNIQUE constraint failed: files._data (code 2067)
        at dev.tarish.app.FileCollector.collectOne(FileCollector.java:128)
```

which was caught, logged and dropped — the worst shape a failure can take, because
everything upstream reported success.

**MediaStore's own de-duplication does not cover it.** `ensureUniqueFileColumns` looks at
the *filesystem*: it finds no file, so it does not rename to `name (1)`. The collision is
with a row. That is also why this never shows up in ordinary testing — receive the same
file twice with both copies present and MediaStore quietly stores the second as `(1)`,
which is exactly what `RPReplay_Final1686524027 (1).mov` and `(2)` on the test device are.

**Fix.** `dropStaleRows()` removes rows whose file is gone before inserting. The existence
check is `openFileDescriptor()`, not `File.exists()` — the app holds **no storage
permission at all**, so it cannot stat the path. That same absence is what makes this safe:
an unpermissioned query returns only rows the app owns, so it can never delete another
app's entry. `publish()` then handles what is left — a live row owned by someone else — by
renaming to `name (1)` rather than giving up, and returns the name actually used.

**One trap in the fix itself:** `service.deleteReceivedFile()` takes the name the *daemon*
knows the file by, not the name it was stored under. After a rename the two differ, and
passing the stored name leaves the inbox copy behind.

**Verified on hardware, before and after, blazer 2026-09-11.** Staged by deleting the file
at `/data/media/0/...` rather than through `/sdcard` — a FUSE unlink tells MediaProvider to
drop the row too, so it does not leave an orphan and does not reproduce this at all. The
old build threw the exception above; the new one logged `dropping a stale MediaStore row
for probe.bin` / `stored probe.bin (2097152 bytes)` and drained the inbox.

### The payload ran over Bluetooth — SOLVED, it runs over Wi-Fi Direct now

**Closed.** Kept because the wrong turns in it are the useful part; the outcome is
in the summary at the top of the Done section.

Quick Share sends work, and they are slow: ~126 KB/s for a 2.7 MB file on frankel. That is
not the send window and not the framing. **It is the medium.**

Bluetooth is only supposed to carry the BOOTSTRAP -- handshake, PIN, consent. Stock then
performs a bandwidth upgrade and moves the payload to Wi-Fi Direct, a hotspot, or the LAN.
It never sends a multi-megabyte file over Bluetooth. We do, because `outbound.rs` answers
every `UPGRADE_PATH_AVAILABLE` with `UPGRADE_FAILURE`:

    OfflineFrame::BandwidthUpgrade(body) => {
        // WE CANNOT TAKE ONE, SO SAY SO.

Declining was right when it was written -- an ignored offer leaves the negotiation open
and the transfer is reported failed with every byte delivered, which is what the Windows
"cannot complete transfer" was. It is not right as a destination.

**What it needs.**

- **Solicit, do not wait.** Stock GMS receivers never offer an upgrade unprompted: they
  advertise `autoUpgradeBandwidth: false` and stay idle until the sender sends
  `BANDWIDTH_UPGRADE_NEGOTIATION{UPGRADE_PATH_REQUEST}`. Bada sends it right after the
  sharing FSM's first PKE frame, so the receiver's group bring-up overlaps the consent
  wait rather than following it.
- **Adopt the offer**, connect on the new medium, then tear the old one down in order:
  `LAST_WRITE_TO_PRIOR_CHANNEL`, `SAFE_TO_CLOSE_PRIOR_CHANNEL`.
- **Failure policy**, which is not symmetric: a Bluetooth bootstrap falls back to staying
  on Bluetooth for anything that goes wrong BEFORE the teardown starts (no offer,
  malformed offer, adopt timeout). After `LAST_WRITE` it is terminal -- the old channel is
  no longer safe to stream on.

**The protocol half is already written and tested**: `tarish_protocol::upgrade`, including
`a_whole_upgrade_completes_on_both_sides`. What is missing is the radio.

**Do WIFI_LAN first.** When both devices are on the same network the upgrade is a TCP
connection to an address the peer hands us -- no Wi-Fi Direct, no P2P group, no new
framework surface, and it is the path Windows already offered us unprompted
(`172.20.9.166:58151`, which we declined). Wi-Fi Direct is the one that matters offline and
is the bigger piece: `WifiP2pManager` lives in the framework, so like BLE it belongs in the
app with the daemon driving it over AIDL.

Until then the send window is worth what it is worth and no more: 256 KiB of packets in
flight keeps the radio busy, but the radio is the ceiling.

### Cancelling — SOLVED, and it was a reporting bug, not a recovery bug

**Closed.** Kept because the wrong turns in it are the useful part; the outcome is
in the summary at the top of the Done section.

Reported from real use 2026-09-05, after PIN entry, wrong-PIN and both-sides cancel were
otherwise tested and working.

**The bug, and it is one line.** Enter the right PIN, then decline on the RECEIVER, and the
sender says the file was sent.

`fsm.rs`, the sender's handling of the peer's answer:

    (State::Introduction, Frame::Response(_)) => {
        // A refusal is a normal answer, not a failure.
        self.state = State::Done;
        vec![Effect::Done]
    }

and `outbound.rs`, deciding what happened:

    return Ok(fsm.state() == State::Done && total > 0);

A refusal lands in `State::Done`, which is also where a completed transfer lands, so the
two are indistinguishable at the only place that decides. `total` does not help -- it is
the size of what we OFFERED, not of what went. So `Ok(true)`, `STATUS_OK`, "Sent".

Treating a refusal as a normal ending is right. Reusing the state that means "the files
went" is what is wrong.

Two ways to fix it, and the second is better:

1. Track in `outbound::send` whether `Effect::BeginSending` was ever handled, and return
   that instead of a state comparison. Small, local, and leaves the ambiguity in the FSM
   for the next caller to trip over.
2. Give the FSM a distinct terminal state -- `State::Refused` -- so "finished, nothing
   sent" is not spelled the same as "finished, everything sent". The inbound side can use
   it too, and the existing test
   `a_refused_send_finishes_rather_than_failing` becomes the test that pins the
   difference rather than the one that hides it.

**While in there, the rest of cancelling wants checking.** These were not all exercised:

- receiver cancels MID-TRANSFER, after accepting -- does the sender stop promptly, and
  report cancelled rather than failed or sent?
- sender cancels mid-transfer -- does the receiver discard the partial file rather than
  keep a truncated one?
- either side cancels during the PIN wait, which now has a 120 s window
- a cancel that arrives while the L2CAP writer is blocked in `wait_for_room` -- the pacing
  wait checks `closed` but not the transfer's cancel flag, so it may sit there until the
  ack timeout

Cancel at the PIN prompt is done and reports "Cancelled" correctly; that one is the model
for how the others should read.

### Quick Share to a stock Android peer — SOLVED, both directions

**Closed.** Kept because the wrong turns in it are the useful part; the outcome is
in the summary at the top of the Done section.

Windows works end to end over RFCOMM. A stock Pixel does not, and the reason is that it
does not accept RFCOMM at all -- **its advertisement says so, and that took far too long
to notice.**

    PHONE    endpoint='WLTQ' name='K-N6'      EXTRA FIELDS mask=0x01 -> L2CAP PSM 177
    WINDOWS  endpoint='D7B0' name='K-ProArt'  no extra fields

A peer publishing a PSM refuses RFCOMM -- accepted, closed inside 200 ms, no frame either
way. A peer publishing none accepts it. Verified with both devices off Wi-Fi entirely, so
none of this is about the network.

**The L2CAP stack, as far as it is understood.** Every packet is
`[len:4][service_id_hash:3][payload]`, where `000000` is the control channel and `fc9f5e`
is ours. In order:

| step | packet | peer's answer |
|---|---|---|
| data connection | `[3][fc9f5e]` | `[23]` ready |
| socket introduction | control `SocketControlFrame{INTRODUCTION, {fc9f5e, V2}}` | accepted |
| multiplex request | data `[len][MultiplexFrame{CONTROL, CONNECTION_REQUEST}]` | **ack, then DISCONNECTION** |

Each of those was found by being wrong first, and each wrong version failed silently:

- a bare `[3]` with no service hash is answered `[24]`, a refusal
- asking `[1]` first gets the channel closed without a word
- skipping the introduction gets every data packet ignored and the channel dropped ~20 s
  later
- writing multiplex frames without the `fc9f5e` packet prefix does the same

**NO MULTIPLEX LAYER.** Nearby has one, and this peer does not use it:

    onIncomingConnection(BLE) mode: LEGACY ... failed to initialize the connection
    java.io.IOException: In readConnectionRequestFrame, expected a CONNECTION_REQUEST
    v1 OfflineFrame but got a UNKNOWN_FRAME_TYPE frame instead

LEGACY means one service per channel, so the first thing after the introduction is the
ordinary Nearby CONNECTION_REQUEST. A MultiplexFrame there is acknowledged by byte count
and the socket then closed, which from the sending side is indistinguishable from being
refused. `tarish_protocol::multiplex` is kept and tested for peers that do multiplex.

**And the endpoint id must be FOUR CHARACTERS.** Ours was the mDNS instance label --
`instance_name()` base64url-encodes a ten-byte structure and yields fourteen. Windows
accepted it for weeks. A Pixel does not, and does not merely refuse:

    FATAL EXCEPTION: highpool[467]
    Process: com.google.android.gms.persistent
    java.lang.IllegalArgumentException: ConnectionsDevice's endpoint id must be
    assigned with length 4.

It takes down `com.google.android.gms.persistent`, so the peer stops answering because the
service handling us has died. That is why this looked like a protocol refusal for hours.

**Verified 2026-09-05:** L2CAP connect, data connection, introduction, `peer is Android,
safe-disconnect v4`, PIN, acceptance, 453693 bytes, `transfer 1 complete`.

**HOW IT WAS FOUND, because it is the lesson.** Six rounds of inference from a reference
implementation got the framing right and then stopped paying. Connecting the receiving
phone over adb and reading ITS log gave the answer in one line, twice -- the LEGACY mode
error and the endpoint-id crash. Neither was visible from the sending side, and neither
was in Bada. **When a peer will not talk to you and you can hold it, read its log first.**

Remaining: the peer rotates its BLE address and PSM every advertisement set, so a row more
than a few seconds old fails at connect. The app should re-resolve immediately before
dialing and retry once.

Credit for the whole layer: Bada's `BleL2capInitialControlClient`, `NearbyBleSocketFrames`
and `ble_frames.proto`.

### Quick Share offline: RFCOMM — SOLVED, and it is only the bootstrap

**Closed.** Kept because the wrong turns in it are the useful part; the outcome is
in the summary at the top of the Done section.

Established by testing against two real peers 2026-09-04, then by reading Bada 2026-09-05.

| peer | RFCOMM connect | then |
|---|---|---|
| Windows Quick Share | accepted | full UKEY2 handshake, encrypted channel, then "cannot complete transfer" |
| Android Quick Share (two devices) | accepted | **ignores everything** — never answers the ConnectionRequest |

**The first version of this entry concluded the door was wrong, and that was wrong.** It
said the initial control connection had to be BLE L2CAP or GATT wrapped in a MultiplexFrame
stream, and laid out four steps starting with extracting a PSM. Reading Bada instead of
inferring from the symptom says otherwise, on three separate pieces of evidence:

- Its send-route priority is **LAN → RFCOMM → BLE L2CAP → BLE GATT** (`SendBootstrapPlan`),
  so RFCOMM outranks both BLE routes for exactly the peer we were testing against.
- `UserFacingMediumFeatures.BLUETOOTH_CLASSIC_BOOTSTRAP_ROUTE_ENABLED` is `true`, and its
  comment records *why*: "Stock GMS receivers bootstrap off-LAN over RFCOMM (verified by
  HCI snoop of stock-to-stock transfers); their BLE GATT/L2CAP server paths are unreliable
  because stock senders never exercise them."
- `useNearbyMultiplexInitialTransport` defaults to `false` and no caller sets it. Multiplex
  is a **LAN** option, and the transport-based constructor hardcodes it off. Nothing
  multiplexes an RFCOMM bootstrap.

And the peers agree: our captured Android advertisements are the fast form, which carries
no PSM at all. Bada's own runbook expects exactly that —
`rejected=[wifi-lan=missing, ble-l2cap=peer-psm-missing]`.

So the socket was right. **What was wrong was the ConnectionRequest, in five places** — all
fields a stock Android receiver requires and none of which produce an error when absent:

| field | what it is | absent means |
|---|---|---|
| `endpoint_info` | **we sent an empty vector** | the receiver builds its "X wants to share" prompt from this. Empty leaves it with a request it cannot show anyone |
| `medium_metadata` (7) | this device's radios | request is not dispatched |
| `connections_device` (12) | endpoint id + info again, inside the `Device` oneof | read in preference to the flat fields |
| `multiplex_socket_bitmask` (5, response) | present and **zero** | Samsung One UI 8.0.5 FINs ~104 ms after our ACCEPT |
| `safe_to_disconnect_version` (7, response) | 1 | One UI 7+ drops us before the consent dialog |

`keep_alive_timeout_millis` was also 30 s where stock is 600 s, in both the request and
(newly) the response — the field was added to `ConnectionResponseFrame` in Dec 2024 and a
Galaxy S24 Ultra FINs ~150 ms without it.

Every one of these is silent. That is why the symptom was a socket that connects and then
does nothing, and why it read as the wrong transport. Fixed in `frames.rs`; the two
derived fields are built inside `connection_request` rather than taken from the caller, so
no caller can omit them again. Five tests assert presence against the encoded bytes,
because a round-trip test cannot catch this — our own parser is happy either way, which is
how they came to be missing.

Credit for all of it: Bada's `OutboundFrames`, whose comments record each field against the
device that needed it.

**Then four more of the same kind, from the same source** — Bada's `AGENTS.md` is a list of
requirements discovered one device at a time, and each of these fails without an error:

- **The ConnectionResponse exchange is send-first, then receive.** We read the peer's
  before sending ours. Against a peer that does the same, that is a plain deadlock: both
  sides block on a read until one times out. Windows happens to send first, which is
  exactly why it was the only peer that ever got past this point.
- **A FILE payload's LAST_CHUNK terminator is its own frame** — empty body, offset =
  total size — and so is a BYTES payload's. We fused body and flag into one frame in both
  paths. Our own receiver reads that correctly, so it round-trips in tests and looks right
  on the wire; a stock receiver reassembles nothing from it. Every sharing frame goes
  through the BYTES path, so an introduction sent that way reaches the peer and produces
  no accept prompt.
- **`IntroductionFrame.use_case` must be NEARBY_SHARE**, and each `FileMetadata.id` must
  equal its `payload_id`. Ours numbered attachments 1..n. Samsung keys its receive-side
  bookkeeping on `id` and discards an attachment it cannot match.
- **The DisconnectionFrame must set `request_safe_to_disconnect`**, and the sender must
  wait for the ack before closing. Having advertised `safe_to_disconnect_version = 1`, a
  bare close is a broken promise: the FIN arrives before the peer drains its read pipeline
  and every payload still in there is marked failed — so the transfer succeeds on our side
  and fails on theirs.

That last one only became a requirement *because* we started advertising the version, so
it and the response fields have to land together.

**Untested on hardware.** Windows got further than Android on the old shape, so it may
still fail at the same place; if it does, the next suspect is unchanged — see below.

**What is NOT wrong, and should not be re-litigated:** the crypto. Against Windows the
plaintext handshake completes, the channel comes up, the peer is identified and a session
PIN derives. HKDF is on RFC vectors, D2D derivation and AES-CBC on Bada's. The remaining
Windows-side failure is most likely the SecureMessage envelope, the one layer in that path
with no foreign-implementation vector.


### Quick Share: the socket — DONE, all four quadrants run on hardware

**Closed.** Kept because the wrong turns in it are the useful part; the outcome is
in the summary at the top of the Done section.

`libtarish_protocol` is complete and covered by 127 tests, including one that runs a whole
share between two peers in-process — UKEY2 handshake, key derivation, encrypted channel,
paired-key exchange, introduction, acceptance, a 300 KB file in 64 KiB chunks,
reassembled and compared byte for byte. `quickshare::connection::serve` is the I/O loop
around it and compiles into the daemon.

Three things stand between that and receiving a file on hardware:

1. **A `Host` implementation.** `serve` needs four methods. `ask` should reuse the
   existing `Transfers::await_answer`, which is what already makes the AirDrop prompt
   work — the app needs no change, and `onTransferOffered` already carries
   `PROTOCOL_QUICKSHARE` so the prompt badges itself correctly. `create` must sanitise
   the peer's filename: `httpd::safe_leaf` and `httpd::non_clobbering` already do exactly
   this for AirDrop and should be made `pub(crate)` and reused rather than reimplemented.

2. **A TCP listener on wlan0**, on the port the mDNS record advertises, spawning a thread
   per connection into `serve`. Gate it on the Quick Share policy — the daemon already
   holds `policy.quickshare`, and `allows_receive` is the check.

3. **mDNS advertising.** Discovery today only BROWSES. Nothing can find this device as a
   Quick Share endpoint until it publishes an SRV, TXT and A record for
   `_FC9F5ED42C8A._tcp` on wlan0. The identity layer for it is done (`quickshare::mod`
   has the instance encoding, endpoint id and TXT keys, verified against Windows Quick
   Share); what is missing is the responder.

Only (3) is real protocol work; (1) and (2) are plumbing. Sending — the outbound
direction — needs the same three plus `fsm::Outbound`, which is written and tested.

**Not blocking, but worth knowing:** `libtarish_protocol` is a dylib rather than an rlib.
It was declared `rust_library_rlib` first and Soong emitted a correct-looking
`--extern tarish_protocol=<valid rlib>` that rustc still could not resolve. Worth another
look if someone wants the static link; it is not worth blocking on, as long as
whatever deploys the daemon carries the .so with it.


### 6 GHz shares 5 GHz's radio chain — ANSWERED, the grouping was right

`same_band_as_sta` groups 6 GHz with 5 GHz, and the function's comment admitted that
was a guess. Measured on mustang (BCM4390), pinned to a 6 GHz BSSID at 6215 MHz:

```
AWDL channel 6   (2.4 GHz)  ->  both alive, 45s soak
AWDL channel 149 (5 GHz)    ->  Wi-Fi gone in under 20s, still gone at 60s
```

So 6 GHz is not a third independent band on this chip: `is_dbs_supported` means 2.4
plus ONE upper band, not all three. Withholding 5 GHz from a 6 GHz association is
correct and costs nothing that was available anyway.

Getting the phone onto 6 GHz needed the BSSID pinned — the SSID is broadcast on both
bands under one name and steering puts it on 5 GHz every time:

```
cmd wifi connect-network '<ssid>' wpa2 '<pass>' -b <6GHz-bssid>
```

One trap worth keeping: `iw phy phy0 info` reports nothing on these devices because
the Wi-Fi phy is **phy1** (phy0 does not exist; the other phy is `wonder`). A grep
against it returns zero matches and reads exactly like "no 6 GHz support", which
briefly looked like a regulatory restriction. The real check showed 50 channels at
20 dBm and country QA.


### tarishsharingd could not send on wlan0 — SOLVED, and not by the uid alone

The daemon advertised AirDrop happily and every Quick Share mDNS query died with
EPERM at `sendto`, while `socket`, `bind` and `IP_MULTICAST_IF` all succeeded.

The old entry here concluded "the uid is the variable, PROVEN", on the evidence that
identical code sent as uid 1000 and failed as 9999. That was a true measurement and
an incomplete diagnosis, and acting on it alone would not have fixed anything: a
dedicated AID at 7500 failed *identically*.

The actual gate is `is_local_network_access_blocked()` in Connectivity's
`bpf/progs/netd.c`. Since Android B it exempts only uid 0 and uid 1000; every other
uid needs `PERMISSION_BIT_ACCESS_LOCAL_NETWORK` in `sUidPermissionChunkMap`, which
`PermissionMonitor` derives from PACKAGES. A native daemon has no package, so it can
never earn the bit however it is numbered. The older kernel rule exempted everything
below uid 10000, which would have covered 9999 and 7500 both — so this is a rule that
recently got narrower, not one we had misread.

Two parts, both shipped:

- the daemon runs as its own AID, `system_ext_tarish` (7500), declared through
  `TARGET_FS_CONFIG_GEN`. This buys isolation and legibility, not network access.
- the integrator grants the bit:
  tarish-daemon's `patches/packages_modules_Connectivity/0001-grant-tarish-daemon-local-network-access.patch`

Why this never affected AirDrop, which had been doing mDNS for weeks: the access map
is keyed by INTERFACE, and `mosey0` is not a managed network. The gate is wlan0-only.

Full mechanism and the three rejected alternatives are recorded with the OS
integration.


- **Per-transfer consent.** `/Ask` blocked on `respondToOffer` rather than answering 200
  unconditionally. No answer within 45 s is a refusal, and `/Upload` is refused outright
  without an accepted offer so a peer cannot skip the prompt by opening a new connection.
- **On-demand AWDL.** The radio is held only while a client is on screen, a transfer is
  running, or the device is advertising. See ARCHITECTURE.md.

### The original blocking list — all of it shipped

Kept because the reasoning is the record of how the protocol was worked out, and
because the mis-framing in it is worth remembering: mDNS and BLE were repeatedly
blamed for "discovery not working" when the actual cause was that nothing was bound to
the advertised port, so no peer could ever finish resolving us.

### Blocking: nothing listens on the port we advertise

Our SRV says `Android_XXXXXXXX.local:8770` and **nothing is bound to 8770**. Checked on
the device: no listening socket in either daemon.

This is not a detail below discovery, it is a prerequisite *for* discovery finishing.
A sender does not list a peer because it answered mDNS. It resolves the SRV, opens
**TLS** to that port and sends `POST /Discover`; the device appears in the AirDrop
window only if that returns a valid response. So even a peer that browses, resolves and
reaches us gets connection-refused and shows nothing.

Which means the mDNS and BLE work, both of which are correct, could not have produced a
visible device on their own. That was mis-framed for several cycles as "discovery is the
blocker".

- [ ] Bind a TLS listener on the advertised port
- [ ] `POST /Discover` returning a valid Apple binary plist
- [x] Certificate story — **settled, and it is not an unknown.** A self-signed
      certificate is sufficient; the peer does not validate ours. Contacts mode is
      deliberately out of scope: it needs an Apple validation record extracted from a
      real device that expires yearly, for identity that means little between two
      platforms with no trust relationship anyway.
- [ ] Minimal binary plist writer — no plist crate exists in the AOSP tree, and the
      `/Discover` and `/Ask` bodies are flat dicts of strings and data blobs
- [ ] TLS via `libopenssl` (BoringSSL-backed, already in the tree). No HTTP crate
      exists either, but the surface is four routes and hand-writing it matches how
      tarishsharingd already hand-parses DNS.

Only once something answers on that port does the question below become testable at all.

### Then: what makes a peer start browsing `_airdrop._tcp`

Our mDNS advertisement is verified byte-for-byte identical in shape to Google's Mosey,
and in every capture so far the only device asking for `_airdrop._tcp` has been us. BLE
advertising is now live and correct, and it did not by itself change that.

Two device-side tests, neither yet run, that separate "our beacon is wrong" from "the
test setup was never valid":

- [ ] **Confirm the Mac is set to "Everyone", not "Contacts Only".** Tarish's beacon
      carries zeroed identifier hashes — an honest "no identity". A Contacts-Only
      receiver is *supposed* to ignore that, so on that setting the result is expected
      and proves nothing.
- [ ] **Capture with the Mac actually SENDING** — share sheet open on a file, not the
      AirDrop receive window. Every capture so far has had the Mac in receive mode,
      where it waits to be found rather than looking. The sender is the side that
      browses `_airdrop._tcp`.

Then, if both are clean and it still does not appear:

- [ ] **Trace `mosey_update`.** Tarish calls only `mosey_start_5` and `mosey_stop`.
      Google's daemon also calls `mosey_update(handle, ptr, 1, 0)`, and the pointer's
      contents were never identified. It is the leading candidate for populating the
      AWDL service-response TLVs that Mosey's state dump reports.
      Its real arguments can be read by hooking the call in Google's daemon.

### Protocol, once discovery works

- [ ] HTTPS layer: `POST /Discover`, `/Ask`, `/Upload`, Apple binary plist bodies
- [ ] TLS: certificate handling for "everyone" versus contacts-only — the real unknown
- [ ] cpio reader/writer for the payload archive
- [ ] Act on a received BLE beacon rather than holding AWDL continuously, which costs
      more power than Apple spends
