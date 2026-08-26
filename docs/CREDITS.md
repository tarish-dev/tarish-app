# Credits

Barq stands on other people's work. This file records what and whose.

## Bada — kyujin-cho

<https://github.com/kyujin-cho/Bada>

> **Licence status: none, and this is a decision point, not a detail.** The
> repository carries no LICENSE file and no licence statement in its README, which
> by default means all rights reserved — no permission to copy, modify or
> redistribute. Attribution does not substitute for permission; crediting someone is
> a condition some licences impose, never a replacement for having one.
>
> **Operator decision (2026-08-26):** Barq stays private for now, so Bada may be used
> internally; permission will be sought from the author, and if none comes the work
> will be credited regardless.
>
> **The trigger is publication, not time.** Internal use is a weak exposure. Shipping
> Bada's code inside a public Barq is not, and it cannot be undone afterwards. So the
> question to ask is never "did he reply yet" but "am I about to make this public" —
> and if the answer is yes and the licence is still absent, the code has to come out
> or be reimplemented first.
>
> What is always safe: protocols are not copyrightable. Reading Bada to learn how
> Quick Share works, and writing an independent implementation, is legitimate and is
> exactly what this project already did with OpenDrop for AirDrop. `core-protocol` is
> pure Kotlin/JVM by design — "this module has NO `android.*`" — which makes it
> readable as a specification.

An independent Quick Share implementation for Android, without GMS. Barq's app
side takes its structure from Bada: share-sheet integration, a foreground
receiver service, a Quick Settings tile, and NFC tap-to-share. Bada was also what
we ran on the device while the Barq daemon was being built, and reading how it
behaves is what identified the split this project is built around — its receiver
already ran headlessly, which showed the transport did not need to live in an app
at all.

## OpenDrop / OWL — seemoo-lab, TU Darmstadt

<https://github.com/seemoo-lab/opendrop> · <https://github.com/seemoo-lab/owl>

The original public reverse engineering of AirDrop and AWDL. Barq does not use
this code, but the protocol understanding underneath everything here — the
election, the availability windows, what AirDrop actually speaks over the link —
is theirs first.

## GoOpenDrop

<https://github.com/bodaay/GoOpenDrop>

A Go implementation of the AirDrop protocol layer, referenced for the plist
dialect, cpio framing and the HTTPS exchange.


## GoOpenDrop — the operator

An earlier from-scratch AirDrop implementation in Go, including its BLE layer.

Barq's AirDrop BLE advertisement layout comes from that work: the Apple
manufacturer-data framing, the AirDrop message type, and the four two-byte truncated
SHA-256 contact-identifier slots. Apple documents none of this, and the layout was
established there by reverse-engineering against real Apple devices.

Reimplemented here rather than reused, at the author's request. The value taken is the
description of how AirDrop works, which was the part that could not have been guessed
— and the previous attempt at guessing a wire format in this project was wrong twice.
