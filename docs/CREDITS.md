# Credits

Barq stands on other people's work. This file records what and whose.

## Bada — kyujin-cho

<https://github.com/kyujin-cho/Bada>

> **Licence: Apache 2.0.** This entry previously said "none, and this is a decision
> point" on the basis that the repository has no LICENSE file. That was wrong, and the
> mistake was looking in the wrong place: **430 of Bada's 434 Kotlin files carry**
>
> ```
> Copyright 2026 Bada contributors.
> Licensed under the Apache License, Version 2.0.
> ```
>
> A per-file header is a licence grant. Apache 2.0 permits use, modification and
> redistribution, including in a closed-source product, provided the copyright notice
> and licence are preserved and modified files are marked as changed.
>
> **So the decision that was parked no longer needs making.** There is no permission to
> seek and no publication trigger to watch: Barq may use this code, publicly, today.
> What Apache 2.0 does require is attribution, which is what this file is for, plus the
> notice retained in each derived file — see the header on `protocol/src/hkdf.rs`.
>
> Recorded rather than quietly deleted because the earlier conclusion drove real
> decisions, including a plan to reimplement rather than port. Knowing it was based on
> a missing LICENSE file, and not on an actual absence of licence, is the useful part.
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


## GoOpenDrop — the author

An earlier from-scratch AirDrop implementation in Go, including its BLE layer.

Barq's AirDrop BLE advertisement layout comes from that work: the Apple
manufacturer-data framing, the AirDrop message type, and the four two-byte truncated
SHA-256 contact-identifier slots. Apple documents none of this, and the layout was
established there by reverse-engineering against real Apple devices.

Reimplemented here rather than reused, at the author's request. The value taken is the
description of how AirDrop works, which was the part that could not have been guessed
— and the previous attempt at guessing a wire format in this project was wrong twice.
