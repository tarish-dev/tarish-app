package dev.tarish.app;

import java.util.List;

/**
 * One entry in the activity list: a transfer that happened, and how it ended.
 *
 * <p>The screen used to remember only files that arrived. That threw away everything the
 * person most wants to look back on — a transfer the sender cancelled, one they declined,
 * one that failed — so the list could not answer "did that go through?". A record is kept
 * for every outcome, in both directions, with enough to show who, what, when and how big,
 * and a details view can lay the rest out.
 */
final class TransferRecord {

    enum Outcome { RECEIVED, SENT, CANCELLED, DECLINED, FAILED }

    final Outcome outcome;
    /** true = we received, false = we sent. */
    final boolean incoming;
    final String peer;
    final int protocol;
    final String[] names;
    final long bytes;
    final long whenMs = System.currentTimeMillis();
    /** The files that landed (received success); empty for every other outcome. */
    final List<FileCollector.Stored> files;

    // A lazily-loaded preview for a single photo/video, cached so re-renders don't reload.
    android.graphics.Bitmap thumb;
    boolean thumbTried;

    TransferRecord(Outcome outcome, boolean incoming, String peer, int protocol,
                   String[] names, long bytes, List<FileCollector.Stored> files) {
        this.outcome = outcome;
        this.incoming = incoming;
        this.peer = peer;
        this.protocol = protocol;
        this.names = names == null ? new String[0] : names;
        this.bytes = bytes;
        this.files = files == null ? java.util.Collections.emptyList() : files;
    }

    /** A received-success record built from what actually landed. */
    static TransferRecord received(String peer, int protocol, List<FileCollector.Stored> files) {
        long total = 0;
        String[] ns = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            total += files.get(i).bytes;
            ns[i] = files.get(i).name;
        }
        return new TransferRecord(Outcome.RECEIVED, true, peer, protocol, ns, total, files);
    }

    String title() {
        if (names.length == 1) {
            return names[0];
        }
        if (names.length > 1) {
            return names.length + " files";
        }
        return incoming ? "Incoming transfer" : "Outgoing transfer";
    }

    /** The one openable file, when there is exactly one. */
    FileCollector.Stored singleFile() {
        return files.size() == 1 ? files.get(0) : null;
    }

    Glyph.Kind icon() {
        return Glyph.kindForNames(names);
    }

    boolean isPreviewable() {
        Glyph.Kind k = icon();
        return singleFile() != null && (k == Glyph.Kind.PHOTO || k == Glyph.Kind.VIDEO);
    }

    String peerLabel() {
        if (peer != null && !peer.isEmpty()) {
            return peer;
        }
        return incoming ? "A nearby device" : "A device";
    }

    String outcomeWord() {
        switch (outcome) {
            case RECEIVED: return "Received";
            case SENT: return "Sent";
            case CANCELLED: return "Cancelled";
            case DECLINED: return "Declined";
            case FAILED: return "Failed";
        }
        return "";
    }

    /** "Received from Alice" / "Sent to Alice" / "Cancelled by the sender", for details. */
    String directionLine() {
        switch (outcome) {
            case RECEIVED: return "Received from " + peerLabel();
            case SENT: return "Sent to " + peerLabel();
            case DECLINED: return incoming ? "You declined it" : peerLabel() + " declined it";
            case CANCELLED: return incoming ? "The sender cancelled it" : "Cancelled";
            case FAILED: return incoming ? "Receive failed" : "Send failed";
        }
        return "";
    }
}
