package dev.barq.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The AirDrop BLE advertisement.
 *
 * <p>This is the trigger for the whole exchange. An AirDrop sender broadcasts this
 * beacon; nearby receivers that see it bring up AWDL and start answering mDNS. Without
 * it a peer never asks for {@code _airdrop._tcp.local} at all, so a perfectly correct
 * mDNS responder stays invisible -- which is exactly what we measured before this
 * existed.
 *
 * <p><b>Layout</b>, inside a manufacturer-specific AD structure with Apple's company
 * ID {@code 0x004C}:
 *
 * <pre>
 *   05                  AirDrop message type
 *   12                  length of what follows (18)
 *   00 x8               zeroes
 *   01                  version
 *   aa aa               SHA-256(Apple ID)[0..2]
 *   pp pp               SHA-256(phone)[0..2]
 *   ee ee               SHA-256(email)[0..2]
 *   ee ee               SHA-256(email2)[0..2]
 *   00                  trailer
 * </pre>
 *
 * <p>The identifier fields are two-byte truncated SHA-256 hashes. They are how a
 * receiver decides whether the sender is a known contact: it hashes its own
 * identifiers and compares. Truncation to two bytes means collisions are common by
 * design -- it narrows the field rather than proving identity, and the real check
 * happens later over TLS.
 *
 * <p><b>Provenance.</b> This layout is not from a specification; Apple publishes none.
 * It was reverse-engineered by the author against real Apple devices in GoOpenDrop,
 * and is reimplemented here from that description rather than copied. Credit is
 * recorded in docs/CREDITS.md.
 */
final class AirDropBeacon {

    /** Apple's Bluetooth SIG company identifier. */
    static final int APPLE_COMPANY_ID = 0x004C;

    /** Message type for AirDrop within Apple's manufacturer data. */
    static final byte TYPE_AIRDROP = 0x05;

    private static final byte VERSION = 0x01;
    private static final int BODY_LEN = 0x12;

    private AirDropBeacon() {}

    /**
     * Build the manufacturer-data payload for "visible to everyone".
     *
     * <p>All four identifier slots are zero. A receiver in contacts-only mode will not
     * match them and will ignore us, which is the correct outcome: we are claiming no
     * identity rather than guessing at one. Receivers set to everyone respond.
     */
    static byte[] everyone() {
        return build(new byte[2], new byte[2], new byte[2], new byte[2]);
    }

    /**
     * Build the payload advertising specific contact identifiers.
     *
     * <p>Each argument is hashed and truncated here; callers pass the raw identifier,
     * never a hash, so there is one place that knows the truncation length.
     */
    static byte[] forContact(String appleId, String phone, String email, String email2) {
        return build(hash2(appleId), hash2(phone), hash2(email), hash2(email2));
    }

    private static byte[] build(byte[] apple, byte[] phone, byte[] email, byte[] email2) {
        byte[] out = new byte[20];
        int i = 0;
        out[i++] = TYPE_AIRDROP;
        out[i++] = (byte) BODY_LEN;
        i += 8;                       // eight zero bytes, already zero
        out[i++] = VERSION;
        out[i++] = apple[0];  out[i++] = apple[1];
        out[i++] = phone[0];  out[i++] = phone[1];
        out[i++] = email[0];  out[i++] = email[1];
        out[i++] = email2[0]; out[i++] = email2[1];
        out[i] = 0x00;                // trailer
        return out;
    }

    /** First two bytes of SHA-256 over the identifier, or zeroes if it is absent. */
    private static byte[] hash2(String value) {
        if (value == null || value.isEmpty()) {
            return new byte[2];
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return new byte[] { digest[0], digest[1] };
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated on every Android platform; treat as unreachable
            // rather than degrading to an unhashed identifier on the air.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** True if a scanned Apple manufacturer payload is an AirDrop beacon. */
    static boolean isAirDrop(byte[] manufacturerData) {
        return manufacturerData != null
                && manufacturerData.length > 0
                && manufacturerData[0] == TYPE_AIRDROP;
    }
}
