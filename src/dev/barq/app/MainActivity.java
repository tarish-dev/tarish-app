package dev.barq.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.barq.BarqPeer;
import dev.barq.BarqStatus;
import dev.barq.IBarqCallback;
import dev.barq.IBarqService;

import java.util.ArrayList;
import java.util.List;

/**
 * Barq: one screen, two modes.
 *
 * <p>Receive and Send are modes of the same screen rather than separate activities. The
 * identity, the service connection and the transfer state are shared between them, so
 * switching should not throw any of it away — and sending should feel like the same
 * tool, not a different app.
 *
 * <p><b>Visibility follows this screen.</b> Being in Receive mode with the screen in
 * front of the user turns discovery on; leaving turns it off. Nothing advertises from
 * boot. That is a privacy position rather than a limitation, and it also stands in for
 * the per-transfer prompt that does not exist yet: while there is no prompt, closing the
 * app is the only "no", so it has to be a real one.
 */
public final class MainActivity extends Activity implements BottomNav.Listener {

    private static final String TAG = "BarqUI";
    private static final String SERVICE_NAME = "dev.barq.IBarqService/default";

    /** One visible session. The daemon expires it on its own timer regardless. */
    private static final int VISIBLE_SECONDS = 600;
    private static final long POLL_MS = 1500;
    /** Renew well inside the daemon's expiry, so there is no window where it has lapsed. */
    private static final long RENEW_AFTER_MS = (VISIBLE_SECONDS - 120) * 1000L;
    private static final int REQ_PICK = 1;

    // Outcomes from IBarqCallback.onTransferFinished.
    private static final int STATUS_FAILED = -1;
    private static final int STATUS_DECLINED = -2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Uri> shared = new ArrayList<>();

    private IBarqService service;
    private BottomNav nav;
    private LinearLayout content;
    private boolean sendMode;
    private long activeTransfer;

    /**
     * The radios Barq needs, and whether we were the ones who switched them on.
     *
     * AWDL cannot start with Wi-Fi off -- not "works badly", cannot start: every mode and
     * channel is refused, because the AWDL driver rides the Wi-Fi driver's interface. A
     * user with Wi-Fi off saw an app that never found anybody and no explanation, which is
     * what was reported. See Radios.
     */
    private Radios radios;

    /** Set once per foreground visit, so a declined prompt is not asked again immediately. */
    private boolean askedAboutRadios;

    /**
     * Deferred restore. Same reasoning as the AWDL radio gate: leaving the app for a file
     * picker or a glance at a notification should not cycle the user's Wi-Fi. Only a real
     * departure does, and never mid-transfer.
     */
    private static final long RADIO_RESTORE_DELAY_MS = 30_000L;

    private final Runnable restoreRadios = new Runnable() {
        @Override
        public void run() {
            if (activeTransfer != 0) {
                // A transfer outlives the foreground. Try again once it is done rather
                // than pulling the radio out from under it.
                main.postDelayed(this, RADIO_RESTORE_DELAY_MS);
                return;
            }
            radios.restore();
        }
    };
    /**
     * What we last asked the daemon for.
     *
     * render() rebuilds every view, so the strip has to be able to re-state the current
     * condition rather than starting from a hardcoded default. Without this, switching
     * to Send and back showed "not visible" while the device was still advertising --
     * the label was a fresh view that nobody had told.
     */
    private boolean discoverable;
    /**
     * What the peer list currently shows.
     *
     * The list used to be torn down and rebuilt on every poll, twice a second. A tap
     * that landed between removeAllViews() and the re-add hit a view that had already
     * been discarded, so tapping a device did nothing at all -- intermittently, which
     * made it look like the send was failing rather than never starting.
     */
    private String peerSignature;
    /** When visibility was last asserted, so it can be renewed before the daemon expires it. */
    private long visibleSince;
    /** The offer waiting for an answer, or null. Set by onTransferOffered. */
    private long offerId;
    private String offerFrom;
    private String[] offerNames = new String[0];

    private String outcomeTitle;
    private String outcomeDetail;

    // Receive-mode views, rebuilt whenever the mode changes.
    private LinearLayout identity;
    private LinearLayout inbox;
    private TextView inboxLabel;
    private TextView deviceLine;
    private TextView stateLine;
    private View liveDot;
    private final List<FileCollector.Stored> received = new ArrayList<>();
    private LinearLayout peerBox;
    private TextView progressLabel;
    private ProgressBarView progressBar;
    private LinearLayout progressCard;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            // Keep trying to get back to the daemon. This is the only recovery path
            // that works for a service which restarts: the proxy is dead for good, and
            // a client that does not rebind sits there looking alive and doing nothing
            // until someone force-stops it.
            if (service == null) {
                if (connect()) {
                    Log.i(TAG, "reconnected to the daemon");
                    setDiscoverable(!sendMode, "reconnect");
                    render();
                }
                main.postDelayed(this, POLL_MS);
                return;
            }
            if (sendMode) {
                refreshPeers();
            } else if (discoverable
                    && System.currentTimeMillis() - visibleSince > RENEW_AFTER_MS) {
                // The daemon expires visibility on its own timer and has no way to tell
                // us. Renewing while this screen is up means the state it shows stays
                // true -- otherwise the phone goes quiet after ten minutes while the
                // screen still claims to be visible, which is a lie in the direction
                // that matters.
                setDiscoverable(true, "renew");
            }
            main.postDelayed(this, POLL_MS);
        }
    };

    private final IBarqCallback callback = new IBarqCallback.Stub() {
        @Override public void onPeerFound(BarqPeer peer) {}
        @Override public void onPeerLost(String peerId) {}

        @Override
        public void onTransferOffered(long id, String peer, String[] names, long bytes) {
            main.post(() -> {
                // Do NOT start progress here. Nothing is being received yet -- the
                // daemon is holding the connection open waiting for this answer, and
                // showing a progress bar for a transfer nobody has agreed to was how
                // the old auto-accept looked from the outside.
                // Never prompt for a transfer WE started.
                //
                // The daemon no longer raises an offer on the send path, which is what
                // made tapping a peer put an Accept/Decline card on the sending phone.
                // This is the second lock on that door: the two callbacks now mean
                // opposite things, and confusing them again would look exactly like a
                // device asking itself for permission.
                if (id == activeTransfer) {
                    Log.w(TAG, "ignoring an offer for our own transfer " + id);
                    return;
                }
                offerId = id;
                offerFrom = (peer == null || peer.isEmpty()) ? "A nearby device" : peer;
                offerNames = names == null ? new String[0] : names;
                if (sendMode) {
                    // An offer arrives on the receive side by definition. Switch, or
                    // the prompt would be built into a screen nobody is looking at.
                    sendMode = false;
                }
                render();
            });
        }

        @Override
        public void onTransferProgress(long id, long done, long total) {
            main.post(() -> {
                if (id != activeTransfer) {
                    return;
                }
                showProgress(total > 0
                        ? Ui.size(done) + " of " + Ui.size(total)
                        : Ui.size(done), total > 0 ? (float) done / total : 0f);
            });
        }

        @Override
        public void onTransferFinished(long id, int status) {
            main.post(() -> {
                activeTransfer = 0;
                offerId = 0;   // whatever happened, the question is answered
                hideProgress();
                if (status == STATUS_DECLINED) {
                    // Someone pressed Decline. That is an answer, not a fault, and
                    // saying "could not send" would invite a retry that gets refused
                    // again.
                    showOutcome("Declined", "the other device turned it down");
                } else if (status == STATUS_FAILED) {
                    showOutcome("Could not send", "the transfer did not complete");
                } else if (!sendMode) {
                    collect();
                } else {
                    showOutcome("Sent", describeShared() + " delivered");
                }
            });
        }
    };

    // ------------------------------------------------------------------ ui ---

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        radios = new Radios(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Ui.BG);

        collectShared(getIntent());
        // Arriving from a share sheet means the user already chose to send.
        sendMode = !shared.isEmpty();

        setContentView(buildShell());
        connect();
        render();
    }

    /**
     * A share arriving while the screen is already open.
     *
     * The activity is singleTask, so a second share does NOT run onCreate -- without
     * this the files are dropped on the floor and the screen sits in whatever mode it
     * was already in, which is exactly what it did: sharing to an open Barq showed the
     * receive screen and no files.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        shared.clear();
        collectShared(intent);
        if (!shared.isEmpty()) {
            sendMode = true;
            setDiscoverable(false, "onNewIntent");
            render();
        }
    }

    /** Title, scrolling content, and the mode bar pinned to the bottom. */
    private ViewGroup buildShell() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.BG);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 20);
        column.setPadding(side, Ui.dp(this, 24), side, Ui.dp(this, 24));
        column.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(side, bars.top + Ui.dp(this, 16), side, Ui.dp(this, 24));
            return insets;
        });

        // Wordmark: bolt then lowercase name, tightly set. A plain title in the
        // system font is what every settings page looks like.
        LinearLayout mark = new LinearLayout(this);
        mark.setGravity(Gravity.CENTER_VERTICAL);
        Glyph bolt = new Glyph(this, Glyph.Kind.BOLT, Ui.ACCENT);
        int b = Ui.dp(this, 30);
        mark.addView(bolt, new LinearLayout.LayoutParams(b, b));
        TextView title = Ui.text(this, "barq", 27, Ui.TEXT, true);
        title.setLetterSpacing(-0.03f);
        title.setPadding(Ui.dp(this, 6), 0, 0, 0);
        mark.addView(title);
        column.addView(mark);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        column.addView(content);

        scroller.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        nav = new BottomNav(this, this);
        nav.setBackgroundColor(Ui.SURFACE);
        page.addView(nav);
        return page;
    }

    /** Rebuild the body for the current mode. */
    private void render() {
        content.removeAllViews();
        nav.setMode(sendMode);
        if (sendMode) {
            buildSend();
        } else {
            buildReceive();
        }
    }

    private void buildReceive() {
        if (offerId != 0) {
            content.addView(Ui.sectionLabel(this, "Incoming"));
            content.addView(buildOfferCard());
        }
        content.addView(Ui.sectionLabel(this, "This device"));
        content.addView(buildIdentityStrip());
        content.addView(buildProgressCard());

        inboxLabel = Ui.sectionLabel(this, "Inbox");
        content.addView(inboxLabel);
        inbox = Ui.cardBox(this);
        inbox.setPadding(0, 0, 0, 0);
        content.addView(inbox);
        renderInbox();
    }

    /**
     * "X wants to send you Y" — with the two answers, and no default.
     *
     * Deliberately not a system dialog: this is the one screen in Barq where a person
     * is being asked to trust another device, and it should look like the rest of the
     * app rather than like something the platform threw up. It is also the only view
     * that can be sure it is on top, because receiving requires the app to be open.
     */
    private LinearLayout buildOfferCard() {
        LinearLayout card = Ui.cardBox(this);

        card.addView(Ui.text(this, offerFrom + " wants to send", 17, Ui.TEXT, true));

        String what;
        if (offerNames.length == 0) {
            what = "a file";
        } else if (offerNames.length == 1) {
            what = offerNames[0];
        } else {
            what = offerNames.length + " files — " + String.join(", ", offerNames);
        }
        TextView detail = Ui.text(this, what, 13, Ui.TEXT_FAINT, false);
        detail.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14));
        card.addView(detail);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView decline = Ui.text(this, "DECLINE", 13, Ui.TEXT, true);
        decline.setGravity(Gravity.CENTER);
        decline.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        decline.setBackground(Ui.card(this, Ui.SURFACE, Ui.RULE, 10));
        decline.setOnClickListener(v -> answerOffer(false));
        row.addView(decline, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));

        TextView accept = Ui.text(this, "ACCEPT", 13, Ui.BG, true);
        accept.setGravity(Gravity.CENTER);
        accept.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        accept.setBackground(Ui.card(this, Ui.ACCENT, Ui.ACCENT, 10));
        accept.setOnClickListener(v -> answerOffer(true));
        row.addView(accept, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(row);
        return card;
    }

    private void answerOffer(boolean accept) {
        long id = offerId;
        offerId = 0;
        if (service == null) {
            render();
            return;
        }
        try {
            service.respondToOffer(id, accept);
        } catch (Exception e) {
            Log.w(TAG, "could not answer offer " + id, e);
        }
        if (accept) {
            activeTransfer = id;
            render();
            showProgress("Receiving…", 0f);
        } else {
            render();
        }
    }

    /**
     * Name, and a live dot when discoverable.
     *
     * A strip rather than a card with a big glyph: this is a status line, and the
     * previous version gave the device's own name more weight than anything the user
     * came here to do.
     */
    private View buildIdentityStrip() {
        identity = Ui.cardBox(this);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);

        liveDot = Ui.dot(this, Ui.TEXT_FAINT, 8);
        identity.addView(liveDot);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 12);
        col.setLayoutParams(lp);

        deviceLine = Ui.text(this, android.os.Build.MODEL.toUpperCase(), 15, Ui.TEXT, true);
        deviceLine.setLetterSpacing(0.04f);
        col.addView(deviceLine);
        stateLine = Ui.text(this, "", 13, Ui.TEXT_MUTED, false);
        col.addView(stateLine);
        identity.addView(col);
        // Re-state what is actually true, rather than a default that may already be wrong.
        if (!transportUp()) {
            setIdentityState("AirDrop radio unavailable \u2014 see Send screen", false);
        } else {
            setIdentityState(discoverable ? "visible to everyone nearby" : "not visible",
                             discoverable);
        }
        return identity;
    }

    /**
     * Everything received this session, newest first, each openable.
     *
     * The old screen collected a file into Downloads and then showed nothing at all --
     * the transfer succeeded and left no trace the user could act on. A list that
     * persists, with a size, a time and a way to open it, is the whole point of the
     * screen after a transfer lands.
     */
    private void renderInbox() {
        if (inbox == null) {
            return;
        }
        inbox.removeAllViews();
        if (received.isEmpty()) {
            TextView empty = Ui.text(this, "Nothing received yet", 13, Ui.TEXT_FAINT, false);
            int p = Ui.dp(this, 16);
            empty.setPadding(p, p, p, p);
            inbox.addView(empty);
            inboxLabel.setText("INBOX");
            return;
        }
        inboxLabel.setText("INBOX  ·  " + received.size());
        for (int i = received.size() - 1; i >= 0; i--) {
            if (i < received.size() - 1) {
                inbox.addView(Ui.rule(this));
            }
            inbox.addView(fileRow(received.get(i)));
        }
    }

    private View fileRow(FileCollector.Stored f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 14);
        row.setPadding(p, p, p, p);

        TextView ext = Ui.text(this, extensionOf(f.name), 10, Ui.ACCENT, true);
        ext.setGravity(Gravity.CENTER);
        ext.setLetterSpacing(0.06f);
        ext.setBackground(Ui.card(this, Ui.SURFACE_SUNK, Ui.RULE, 8));
        int w = Ui.dp(this, 42), h = Ui.dp(this, 34);
        row.addView(ext, new LinearLayout.LayoutParams(w, h));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 12);
        col.setLayoutParams(lp);
        TextView n = Ui.text(this, f.name, 14, Ui.TEXT, false);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(n);
        col.addView(Ui.text(this, Ui.size(f.bytes) + "  ·  " + ago(f.receivedAt),
                            12, Ui.TEXT_FAINT, false));
        row.addView(col);

        TextView open = Ui.text(this, "OPEN", 11, Ui.ON_ACCENT, true);
        open.setLetterSpacing(0.1f);
        open.setGravity(Gravity.CENTER);
        open.setBackground(Ui.card(this, Ui.ACCENT, Color.TRANSPARENT, 8));
        open.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        open.setOnClickListener(v -> openFile(f));
        row.addView(open);
        return row;
    }

    private void openFile(FileCollector.Stored f) {
        if (f.uri == null) {
            return;
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setData(f.uri);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
        } catch (Exception e) {
            Log.w(TAG, "nothing can open " + f.name, e);
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        String e = dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "FILE";
        return e.length() > 4 ? e.substring(0, 4).toUpperCase() : e.toUpperCase();
    }

    private static String ago(long when) {
        long secs = Math.max(0, (System.currentTimeMillis() - when) / 1000);
        if (secs < 60) {
            return "just now";
        }
        long mins = secs / 60;
        return mins < 60 ? mins + " min ago" : (mins / 60) + " h ago";
    }

    private void buildSend() {
        content.addView(Ui.sectionLabel(this, "Files"));
        LinearLayout files = Ui.cardBox(this);
        files.setOrientation(LinearLayout.HORIZONTAL);
        files.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(lp);
        String heading = outcomeTitle != null ? outcomeTitle
                : shared.isEmpty() ? "No files chosen" : describeShared();
        String sub = outcomeDetail != null ? outcomeDetail
                : shared.isEmpty() ? "choose files, or share to barq from any app"
                                   : firstNames();
        col.addView(Ui.text(this, heading, 15,
                            outcomeTitle != null ? Ui.ACCENT : Ui.TEXT, true));
        col.addView(Ui.text(this, sub, 12, Ui.TEXT_FAINT, false));
        files.addView(col);

        // Barq should be usable on its own, not only as a share target. Without this
        // the send half of the app could do nothing unless another app started it.
        TextView choose = Ui.text(this, shared.isEmpty() ? "CHOOSE" : "CHANGE",
                                  11, Ui.ON_ACCENT, true);
        choose.setLetterSpacing(0.1f);
        choose.setGravity(Gravity.CENTER);
        choose.setBackground(Ui.card(this, Ui.ACCENT, Color.TRANSPARENT, 8));
        choose.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        choose.setOnClickListener(v -> pickFiles());
        files.addView(choose);
        content.addView(files);

        content.addView(buildProgressCard());

        // Choosing again clears a previous outcome, so the row stops reporting a
        // transfer the user has moved on from.
        // Section label with a refresh affordance on the right.
        //
        // WHY A MANUAL REFRESH EXISTS. Discovery is mDNS, so a peer that goes away
        // stays listed until its TTL runs out, and one that was never fully resolved
        // stays half-resolved. On a link that flaps -- frankel, where AWDL owns the
        // radio and Wi-Fi scanning contends with it -- that stale state is exactly
        // what the user is looking at when they wonder why their Mac is not there.
        // Waiting out a TTL is not something a person should have to know about.
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.sectionLabel(this,
                shared.isEmpty() ? "Nearby devices" : "Send to nearby devices");
        labelRow.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = Ui.text(this, "REFRESH", 11, Ui.ACCENT, true);
        refresh.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        refresh.setOnClickListener(v -> forceRediscover());
        labelRow.addView(refresh);
        content.addView(labelRow);
        peerBox = Ui.cardBox(this);
        peerBox.setMinimumHeight(Ui.dp(this, 150));
        peerSignature = null;   // fresh views, so the next refresh MUST populate them
        content.addView(peerBox);
        if (shared.isEmpty()) {
            TextView hint = Ui.text(this, "Choose files to enable sending",
                                    12, Ui.TEXT_FAINT, false);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, Ui.dp(this, 10), 0, 0);
            content.addView(hint);
        }
        refreshPeers();
    }

    private View buildProgressCard() {
        progressCard = Ui.cardBox(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        progressCard.setLayoutParams(lp);
        progressCard.setVisibility(View.GONE);

        progressBar = new ProgressBarView(this);
        progressCard.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6)));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 12), 0, 0);
        progressLabel = Ui.text(this, "", 13, Ui.TEXT_MUTED, false);
        LinearLayout.LayoutParams tp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        progressLabel.setLayoutParams(tp);
        row.addView(progressLabel);

        TextView cancel = Ui.text(this, "Cancel", 13, Ui.ON_ACCENT, true);
        cancel.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        cancel.setBackground(Ui.card(this, Ui.ACCENT_FILL, Color.TRANSPARENT, 18));
        cancel.setOnClickListener(v -> cancelActive());
        row.addView(cancel);
        progressCard.addView(row);
        return progressCard;
    }

    private void showProgress(String label, float fraction) {
        if (progressCard == null) {
            return;
        }
        progressCard.setVisibility(View.VISIBLE);
        progressBar.setFraction(fraction);
        progressLabel.setText(label);
    }

    /**
     * Report how a transfer ended, where the user is already looking.
     *
     * In send mode that is the files row, which is the only part of the screen that was
     * about this transfer. Previously an outcome went to the identity strip, which in
     * send mode is not even visible -- so a decline produced no feedback at all.
     */
    private void showOutcome(String title, String detail) {
        if (sendMode) {
            outcomeTitle = title;
            outcomeDetail = detail;
            render();
        } else {
            setIdentityState(title.toLowerCase(), false);
        }
    }

    private void hideProgress() {
        if (progressCard != null) {
            progressCard.setVisibility(View.GONE);
        }
    }

    private void setIdentityState(String state, boolean live) {
        if (stateLine != null) {
            stateLine.setText(state);
        }
        if (liveDot != null) {
            liveDot.setBackground(Ui.circle(live ? Ui.LIVE : Ui.TEXT_FAINT));
        }
    }



    // -------------------------------------------------------------- modes ---

    @Override
    public void onModeChosen(boolean send) {
        if (send == sendMode) {
            return;
        }
        sendMode = send;
        // Ask first, then rebuild: render() replaces every view, so a state written
        // before it lands on views that are about to be thrown away.
        setDiscoverable(!send, "modeSwitch");
        render();
    }

    // ------------------------------------------------------------- service ---

    /**
     * Bind to the daemon, replacing a dead proxy if there is one.
     *
     * The daemon can restart -- it is a native service under init, and it does restart
     * on crash or upgrade. When it does, every proxy the client holds is permanently
     * dead: each call throws and no amount of retrying revives it. Catching the
     * exception and carrying on, which is what this did, meant the app looked alive and
     * silently did nothing until it was force-stopped and reopened.
     *
     * @return true if a usable binding exists afterwards
     */
    private boolean connect() {
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) {
            Log.e(TAG, "daemon did not publish " + SERVICE_NAME);
            service = null;
            return false;
        }
        service = IBarqService.Stub.asInterface(binder);
        try {
            service.registerCallback(callback);
            // Notice the daemon going away, rather than finding out on the next call
            // and treating it as an ordinary failure.
            binder.linkToDeath(deathRecipient, 0);
        } catch (Exception e) {
            Log.e(TAG, "could not register callback", e);
            service = null;
            return false;
        }
        // Re-assert the foreground after a reconnect. A daemon that has just
        // restarted has no idea we are on screen, and without this nothing would
        // tell it until the next onResume -- which, if the user never leaves the
        // app, may not come at all.
        //
        // Outside the try above on purpose: setActive has its own handling for an
        // older daemon, and must not be able to null out a binding that works.
        setActive(true);
        return true;
    }

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "daemon died — will rebind");
            service = null;
            // Do NOT rebind immediately: a service that has just died has not
            // re-registered yet, so the first attempt reliably finds nothing. The poll
            // loop retries until it is back, which also covers a daemon that takes a
            // while to come up.
            main.post(() -> setIdentityState("reconnecting\u2026", false));
        }
    };

    /**
     * Recover from a dead proxy discovered mid-call.
     *
     * linkToDeath is the fast path, but a call can still land on a proxy that has just
     * died, so every caller treats a failure as "reconnect and try once more".
     */
    private boolean reconnect() {
        Log.i(TAG, "rebinding to the daemon");
        service = null;
        return connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (service == null) {
            connect();
        }
        startService(new Intent(this, BarqBleService.class));
        // Cancel any pending restore: the user came back, so the radios stay as they are.
        main.removeCallbacks(restoreRadios);
        promptForRadiosIfNeeded();
        setActive(true);
        setDiscoverable(!sendMode, "onResume");
        main.post(poll);
        if (!sendMode) {
            collect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(poll);
        setDiscoverable(false, "onPause");
        // The radio, unlike visibility, is released on leaving the foreground. The
        // daemon holds it for another half-minute so a file picker or a glance at
        // another app does not tear the link down and back up.
        setActive(false);
        stopService(new Intent(this, BarqBleService.class));
        askedAboutRadios = false;
        // Put back only what we turned on, and not for another half minute. Anything the
        // user already had on is never touched -- see Radios.
        if (radios.owesRestore()) {
            main.removeCallbacks(restoreRadios);
            main.postDelayed(restoreRadios, RADIO_RESTORE_DELAY_MS);
        }
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            try {
                service.unregisterCallback(callback);
            } catch (Exception e) {
                Log.w(TAG, "could not unregister callback", e);
            }
        }
        super.onDestroy();
    }

    /**
     * Tell the daemon whether we are in the foreground, which is what governs the
     * AWDL radio.
     *
     * Separate from setDiscoverable because the two genuinely differ: in Send mode
     * we are NOT discoverable and very much need the radio. Gating the link on
     * visibility would take it down under every outgoing transfer.
     */
    /**
     * The frequency Wi-Fi is associated on, in MHz, or 0 if it is not.
     *
     * The daemons cannot answer this. Both are native, neither has framework access,
     * and the association frequency is not exposed as a file either could read -- so
     * the app is the only place it can come from, and it is why setActive carries it.
     *
     * It decides which BAND AWDL uses. The chip does 2.4 and 5 GHz simultaneously but
     * cannot hold two 5 GHz channels, so AWDL goes in the other band from Wi-Fi.
     * Getting this wrong drops the Wi-Fi association within about three seconds.
     */
    private int staFrequencyMhz() {
        try {
            WifiManager wm = getSystemService(WifiManager.class);
            if (wm == null) {
                return 0;
            }
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) {
                return 0;
            }
            int f = info.getFrequency();
            return f > 0 ? f : 0;
        } catch (Exception e) {
            // Not fatal: the daemon treats 0 as "unknown" and keeps its previous choice.
            Log.w(TAG, "could not read the Wi-Fi frequency", e);
            return 0;
        }
    }

    private void setActive(boolean active) {
        if (service == null) {
            return;
        }
        try {
            service.setActive(active, staFrequencyMhz());
        } catch (Exception e) {
            // An older daemon does not have this method. That is survivable: it
            // simply keeps the radio up, which is what it did before this existed.
            Log.w(TAG, "setActive unavailable", e);
        }
    }

    /**
     * Is the AWDL transport actually up?
     *
     * Worth asking separately from anything else, because when it is down every other
     * screen is truthful and useless: no peers, not discoverable, no error. The daemon
     * refuses to bring the radio up without a regulatory country, and a phone that has
     * never joined a Wi-Fi network and has no SIM has none -- which presents as the app
     * simply not working, with nothing anywhere saying why.
     */
    private boolean transportUp() {
        if (service == null) {
            return false;
        }
        try {
            BarqStatus st = service.getStatus();
            return st != null && st.linkUp;
        } catch (Exception e) {
            return false;
        }
    }

    private void setDiscoverable(boolean visible, String why) {
        Log.i(TAG, "setDiscoverable(" + visible + ") from " + why + " sendMode=" + sendMode);
        if (service == null) {
            setIdentityState("barq service unavailable", false);
            return;
        }
        try {
            service.setDiscoverable(visible, visible ? VISIBLE_SECONDS : 0);
            discoverable = visible;
            if (visible) {
                visibleSince = System.currentTimeMillis();
            }
            if (!sendMode) {
                setIdentityState(visible ? "visible to everyone nearby" : "not visible",
                                 visible);
            }
        } catch (Exception e) {
            Log.w(TAG, "setDiscoverable failed, rebinding", e);
            if (reconnect()) {
                try {
                    service.setDiscoverable(visible, visible ? VISIBLE_SECONDS : 0);
                    discoverable = visible;
                    if (visible) {
                        visibleSince = System.currentTimeMillis();
                    }
                    setIdentityState(visible ? "visible to everyone nearby" : "not visible",
                                     visible);
                    return;
                } catch (Exception again) {
                    Log.e(TAG, "still cannot reach the daemon", again);
                }
            }
            setIdentityState("cannot reach the barq service", false);
        }
    }

    private void collect() {
        if (service == null) {
            return;
        }
        List<FileCollector.Stored> got = FileCollector.collectAll(this, service);
        if (got.isEmpty()) {
            return;
        }
        received.addAll(got);
        renderInbox();
    }

    // ---------------------------------------------------------------- send ---

    private void collectShared(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            if (u != null) {
                shared.add(u);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> us = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
            if (us != null) {
                shared.addAll(us);
            }
        }
    }

    /**
     * Open the system picker.
     *
     * OPEN_DOCUMENT rather than GET_CONTENT: it returns a persistable URI the daemon can
     * still read after the picker is gone, which matters because the transfer outlives
     * this screen.
     */
    private void pickFiles() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (Exception e) {
            Log.e(TAG, "no document picker available", e);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_PICK || result != RESULT_OK || data == null) {
            return;
        }
        outcomeTitle = null;
        outcomeDetail = null;
        shared.clear();
        if (data.getClipData() != null) {
            android.content.ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                shared.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            shared.add(data.getData());
        }
        render();
    }

    /** The first couple of filenames, so the row says what is actually going. */
    private String firstNames() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < shared.size() && i < 2; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(displayName(shared.get(i)));
        }
        if (shared.size() > 2) {
            b.append(" +").append(shared.size() - 2);
        }
        return b.toString();
    }

    private String describeShared() {
        if (shared.isEmpty()) {
            return "nothing chosen";
        }
        return shared.size() == 1 ? "1 file ready" : shared.size() + " files ready";
    }

    private void refreshPeers() {
        if (service == null || peerBox == null) {
            return;
        }
        BarqPeer[] peers;
        try {
            peers = service.getPeers();
        } catch (Exception e) {
            // Almost certainly a dead proxy from a daemon restart. Rebind and let the
            // next tick populate; failing silently here is what made the list stay
            // empty until the app was restarted by hand.
            Log.w(TAG, "getPeers failed, rebinding", e);
            reconnect();
            return;
        }
        // A peer whose "name" is still the raw 12-hex mDNS identifier has not answered
        // /Discover yet, and a device that will not answer cannot accept a file either.
        // Showing the hex string as though it were a device name is what made the list
        // look broken -- it is not a name, it is the absence of one.
        List<BarqPeer> named = new ArrayList<>();
        for (BarqPeer p : peers) {
            if (p.name != null && !p.name.matches("[0-9a-f]{12}")) {
                named.add(p);
            }
        }
        peers = named.toArray(new BarqPeer[0]);

        // Everything the tiles are drawn FROM belongs in the signature, not just the
        // peers -- the gate below reads `shared`, so a selection change with an
        // unchanged peer list must still redraw.
        StringBuilder sig = new StringBuilder(shared.isEmpty() ? "gated\n" : "live\n");
        for (BarqPeer p : peers) {
            sig.append(p.id).append('|').append(p.name).append('\n');
        }
        if (sig.toString().equals(peerSignature)) {
            return;   // nothing changed; leave the views (and their listeners) alone
        }
        peerSignature = sig.toString();

        peerBox.removeAllViews();
        if (peers.length == 0) {
            TextView none = Ui.text(this,
                    "Looking…\nOn an Apple device, open AirDrop and set it to Everyone",
                    13, Ui.TEXT_FAINT, false);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(this, 30), 0, Ui.dp(this, 14));
            peerBox.addView(none);
            return;
        }
        // A row of tiles, wrapping every three, which is how the reference lays peers out.
        LinearLayout row = null;
        for (int i = 0; i < peers.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                peerBox.addView(row);
            }
            BarqPeer p = peers[i];
            LinearLayout tile = Ui.deviceTile(this, glyphFor(p), p.name, kindOf(p));
            if (shared.isEmpty()) {
                // Dimmed and inert. A tile that looks tappable and silently does
                // nothing is worse than one that plainly cannot be used -- tapping it
                // and getting no response reads as the app being broken.
                tile.setAlpha(0.35f);
                tile.setOnClickListener(v -> nudgeChooseFiles());
            } else {
                tile.setAlpha(1f);
                tile.setOnClickListener(v -> sendTo(p));
            }
            row.addView(tile, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    /**
     * Apple peers are the only kind Barq talks to today, so everything is a device
     * glyph rather than pretending to distinguish phone from laptop — which we cannot:
     * AirDrop's mDNS records carry no device type, only a name learned from /Discover.
     */
    private static Glyph.Kind glyphFor(BarqPeer p) {
        return Glyph.Kind.LAPTOP;
    }

    private static String kindOf(BarqPeer p) {
        return "airdrop";
    }

    private void cancelActive() {
        if (service == null || activeTransfer == 0) {
            return;
        }
        try {
            service.cancelTransfer(activeTransfer);
            progressLabel.setText("Cancelling…");
        } catch (Exception e) {
            Log.e(TAG, "cancelTransfer failed", e);
        }
    }

    /**
     * Say why a device cannot be picked yet, and offer the way out.
     *
     * Reached by tapping a dimmed tile. The dimming states it; this explains it for
     * anyone who tries anyway.
     */
    private void nudgeChooseFiles() {
        outcomeTitle = "Choose files first";
        outcomeDetail = "pick something to send, then tap a device";
        render();
    }

    private void sendTo(BarqPeer peer) {
        Log.i(TAG, "sendTo " + peer.name + " with " + shared.size() + " file(s)");
        if (service == null) {
            Log.w(TAG, "no service");
            return;
        }
        if (shared.isEmpty()) {
            Log.w(TAG, "nothing chosen");
            return;
        }
        List<ParcelFileDescriptor> fds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Uri uri : shared) {
            try {
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd != null) {
                    fds.add(pfd);
                    names.add(displayName(uri));
                }
            } catch (Exception e) {
                Log.e(TAG, "could not open " + uri, e);
            }
        }
        if (fds.isEmpty()) {
            showProgress("Could not read the files", 0f);
            return;
        }
        showProgress("Starting…", 0f);
        try {
            activeTransfer = service.sendFiles(peer.id,
                    fds.toArray(new ParcelFileDescriptor[0]),
                    names.toArray(new String[0]));
        } catch (Exception e) {
            Log.e(TAG, "sendFiles failed", e);
            showProgress("Could not send", 0f);
        } finally {
            // The daemon duplicates what it needs, so these are ours to close.
            for (ParcelFileDescriptor pfd : fds) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                    // Already sent or already failed; nothing useful to do.
                }
            }
        }
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver()
                .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "no display name for " + uri, e);
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "file" : last;
    }

    /**
     * Offer to turn on whatever Barq needs, if anything is off.
     *
     * Asked at most once per foreground visit: a user who says no should be able to look
     * around the app without being nagged, and the answer is obvious enough from the empty
     * peer list. Saying yes is remembered only for as long as it takes to put it back.
     */
    /**
     * Drop everything we think we know about peers and browse again.
     *
     * Deliberately clears the local view too rather than waiting for the next poll:
     * a refresh that leaves the stale rows on screen for a second reads as "nothing
     * happened", and the user taps it again.
     */
    private void forceRediscover() {
        if (service == null) {
            Log.w(TAG, "refresh: not connected to the daemon");
            return;
        }
        try {
            service.refreshPeers();
            peerSignature = null;       // force the next poll to rebuild the rows
            if (peerBox != null) {
                peerBox.removeAllViews();
                TextView searching = Ui.text(this, "Searching\u2026", 12, Ui.TEXT_FAINT, false);
                searching.setGravity(Gravity.CENTER);
                searching.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
                peerBox.addView(searching);
            }
            Log.i(TAG, "refresh: asked the daemon to re-browse");
        } catch (RemoteException e) {
            Log.w(TAG, "refresh failed", e);
        }
    }

    private void promptForRadiosIfNeeded() {
        if (askedAboutRadios || !radios.anythingOff()) {
            return;
        }
        askedAboutRadios = true;
        final String off = radios.whatIsOff();
        new AlertDialog.Builder(this)
                .setTitle("Turn on " + off + "?")
                .setMessage("Barq needs " + off + " to find nearby devices. "
                        + "If you had it off, Barq turns it back off when you leave.")
                .setPositiveButton("Turn on", (d, w) -> {
                    if (!radios.enableAll()) {
                        // Do not fail silently. Failing silently with Wi-Fi off is the
                        // exact bug this path exists to fix, and a refusal here means
                        // something is wrong with our privileges, not with the user.
                        Log.w(TAG, "could not turn on " + off);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Could not turn on " + off)
                                .setMessage("Turn it on from Settings, then come back.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }
}
