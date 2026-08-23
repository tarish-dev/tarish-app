# Credits

Barq stands on other people's work. This file records what and whose.

## Bada — kyujin-cho

<https://github.com/kyujin-cho/Bada>

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
