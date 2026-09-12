package dev.tarish.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Unpacks a `.notesairdropdocument` — an AirDrop from Apple Notes.
 *
 * <p><b>Why this exists.</b> A note does not arrive as text and an audio note does not
 * arrive as audio. Both arrive as this one file type, an Apple Notes protobuf, and Android
 * has no handler for it. Without this the user receives a blob containing their own note,
 * and a sharing app that cannot show you what was shared has not shared anything.
 *
 * <p>Measured on real transfers (2026-09-12): a text note is 444 bytes carrying one UTF-8
 * string; a note with two voice recordings is 82,424 bytes carrying the same text plus two
 * complete M4A files plus Apple's own speech-to-text transcript.
 *
 * <p><b>Nothing here is a full protobuf implementation and it does not need to be.</b>
 * Apple's schema is private and will change. The approach is deliberately structural
 * rather than positional: walk every length-delimited field looking for things that are
 * recognisably text or recognisably M4A. That survives Apple rearranging the message,
 * which a hardcoded field path would not.
 */
final class AppleNote {

    /** The UTI Apple puts in the `/Ask` plist for these, and the file extension. */
    static final String UTI = "com.apple.notes.airdrop.document";
    static final String EXTENSION = ".notesairdropdocument";

    /** Don't recurse forever into a malformed or hostile document. */
    private static final int MAX_DEPTH = 8;

    /** The note's text, or null if none was recognisable. */
    final String text;
    /** Every complete embedded recording, in order. */
    final List<byte[]> recordings;

    private AppleNote(String text, List<byte[]> recordings) {
        this.text = text;
        this.recordings = recordings;
    }

    static boolean looksLikeOne(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(EXTENSION);
    }

    static AppleNote parse(byte[] doc) {
        List<String> strings = new ArrayList<>();
        collectStrings(doc, 0, strings);
        return new AppleNote(pickBody(strings), extractM4a(doc));
    }

    /**
     * Choose the note's own text out of everything string-shaped in the document.
     *
     * <p>Not simply the longest: an 82 KB note contains
     * {@code com.apple.notes.ICCloudSyncingObjectActivityEvent}, which is longer than the
     * note was. **The discriminator is whitespace** — Apple's class names and property
     * keys are single tokens, and prose in any language has spaces or newlines in it. A
     * one-word note is missed, which is a better failure than confidently showing the
     * user an Apple class name.
     */
    private static String pickBody(List<String> strings) {
        String best = null;
        for (String s : strings) {
            if (!isProse(s)) {
                continue;
            }
            if (best == null || s.length() > best.length()) {
                best = s;
            }
        }
        return best;
    }

    private static boolean isProse(String s) {
        if (s.startsWith("com.apple") || s.startsWith("public.")) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every embedded M4A.
     *
     * <p><b>The boundary is the next {@code ftyp}, not a box walk.</b> These documents
     * carry a non-standard {@code cord} box whose length is nonsense, so walking boxes to
     * the end overruns into the next file. Two recordings were extracted at 38,747 and
     * 38,638 bytes this way, both with {@code ftyp}, {@code mdat} and {@code moov} intact.
     *
     * <p>(An MP4 box size of 1 means a 64-bit length follows and 0 means "to end of file".
     * Worth knowing, and the reason a naive walker stops at the first {@code mdat} and
     * produces a 28-byte fragment that still identifies as M4A.)
     */
    private static List<byte[]> extractM4a(byte[] d) {
        List<Integer> starts = new ArrayList<>();
        byte[] needle = "ftypM4A".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + needle.length <= d.length; i++) {
            if (matches(d, i, needle) && i >= 4) {
                starts.add(i - 4); // the box length precedes the type
            }
        }
        List<byte[]> out = new ArrayList<>();
        for (int n = 0; n < starts.size(); n++) {
            int from = starts.get(n);
            int to = (n + 1 < starts.size()) ? starts.get(n + 1) : d.length;
            if (to > from) {
                byte[] blob = new byte[to - from];
                System.arraycopy(d, from, blob, 0, to - from);
                out.add(blob);
            }
        }
        return out;
    }

    private static boolean matches(byte[] d, int at, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (d[at + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------- protobuf walking ---

    /** Every length-delimited field that decodes as printable UTF-8, recursively. */
    private static void collectStrings(byte[] b, int depth, List<String> out) {
        if (depth > MAX_DEPTH) {
            return;
        }
        int i = 0;
        while (i < b.length) {
            long key;
            int[] pos = { i };
            try {
                key = varint(b, pos);
            } catch (IndexOutOfBoundsException e) {
                return;
            }
            i = pos[0];
            int wire = (int) (key & 7);
            switch (wire) {
                case 2: {
                    long len;
                    pos[0] = i;
                    try {
                        len = varint(b, pos);
                    } catch (IndexOutOfBoundsException e) {
                        return;
                    }
                    i = pos[0];
                    // A length that overruns means we are not actually in a protobuf
                    // here -- stop rather than guess, because guessing manufactures
                    // fields that were never in the document.
                    if (len < 0 || i + len > b.length) {
                        return;
                    }
                    byte[] v = new byte[(int) len];
                    System.arraycopy(b, i, v, 0, (int) len);
                    i += (int) len;
                    String s = asPrintableUtf8(v);
                    if (s != null) {
                        out.add(s);
                    } else {
                        collectStrings(v, depth + 1, out);
                    }
                    break;
                }
                case 0:
                    pos[0] = i;
                    try {
                        varint(b, pos);
                    } catch (IndexOutOfBoundsException e) {
                        return;
                    }
                    i = pos[0];
                    break;
                case 5:
                    i += 4;
                    break;
                case 1:
                    i += 8;
                    break;
                default:
                    return; // 3 and 4 are deprecated groups; treat as end of usable data
            }
        }
    }

    /**
     * The bytes as a string, or null if they are not printable UTF-8.
     *
     * <p>Strictness matters: this is the only thing separating a text field from a blob of
     * audio that happens to decode. Anything with control characters other than tab or
     * newline is rejected.
     */
    private static String asPrintableUtf8(byte[] v) {
        if (v.length < 2) {
            return null;
        }
        String s = new String(v, StandardCharsets.UTF_8);
        // A round-trip mismatch means the bytes were not valid UTF-8 -- the decoder
        // substituted replacement characters rather than failing.
        if (!java.util.Arrays.equals(s.getBytes(StandardCharsets.UTF_8), v)) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r') {
                continue;
            }
            if (Character.isISOControl(c)) {
                return null;
            }
        }
        return s;
    }

    private static long varint(byte[] b, int[] pos) {
        long r = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= b.length || shift > 63) {
                throw new IndexOutOfBoundsException();
            }
            int c = b[pos[0]++] & 0xff;
            r |= ((long) (c & 0x7f)) << shift;
            if ((c & 0x80) == 0) {
                return r;
            }
            shift += 7;
        }
    }
}
