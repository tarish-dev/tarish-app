package dev.barq.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Uri> shared = new ArrayList<>();

    private IBarqService service;
    private BottomNav nav;
    private LinearLayout content;
    private boolean sendMode;
    private long activeTransfer;

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
            if (sendMode) {
                refreshPeers();
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
                activeTransfer = id;
                showProgress("Starting…", 0f);
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
                hideProgress();
                if (status < 0) {
                    setIdentityState("transfer stopped", false);
                } else if (!sendMode) {
                    collect();
                } else {
                    setIdentityState("sent", false);
                }
            });
        }
    };

    // ------------------------------------------------------------------ ui ---

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
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
            setDiscoverable(false);
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
        stateLine = Ui.text(this, "not visible", 13, Ui.TEXT_MUTED, false);
        col.addView(stateLine);
        identity.addView(col);
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
        content.addView(Ui.sectionLabel(this, "Selected files"));
        LinearLayout files = Ui.cardBox(this);
        files.setOrientation(LinearLayout.HORIZONTAL);
        files.setGravity(Gravity.CENTER_VERTICAL);
        files.addView(Ui.glyphInCircle(this, Glyph.Kind.CHECK, Ui.ACCENT, Ui.SURFACE_SUNK, 42));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 16);
        col.setLayoutParams(lp);
        col.addView(Ui.text(this, shared.isEmpty() ? "Nothing selected" : "Selected files",
                            16, Ui.TEXT, true));
        col.addView(Ui.text(this, describeShared(), 14, Ui.TEXT_MUTED, false));
        files.addView(col);
        content.addView(files);

        content.addView(buildProgressCard());

        content.addView(Ui.sectionLabel(this, "Send to nearby devices"));
        peerBox = Ui.cardBox(this);
        peerBox.setMinimumHeight(Ui.dp(this, 150));
        content.addView(peerBox);
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
        // Receiving and sending are mutually exclusive here: advertising while picking a
        // peer would leave the device findable for a reason the user did not ask for.
        setDiscoverable(!send);
        render();
    }

    // ------------------------------------------------------------- service ---

    private void connect() {
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) {
            Log.e(TAG, "daemon did not publish " + SERVICE_NAME);
            return;
        }
        service = IBarqService.Stub.asInterface(binder);
        try {
            service.registerCallback(callback);
        } catch (Exception e) {
            Log.e(TAG, "could not register callback", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startService(new Intent(this, BarqBleService.class));
        setDiscoverable(!sendMode);
        main.post(poll);
        if (!sendMode) {
            collect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(poll);
        setDiscoverable(false);
        stopService(new Intent(this, BarqBleService.class));
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

    private void setDiscoverable(boolean visible) {
        if (service == null) {
            setIdentityState("barq service unavailable", false);
            return;
        }
        try {
            service.setDiscoverable(visible, visible ? VISIBLE_SECONDS : 0);
            if (!sendMode) {
                setIdentityState(visible ? "visible to everyone nearby" : "not visible",
                                 visible);
            }
        } catch (Exception e) {
            Log.e(TAG, "setDiscoverable failed", e);
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

    private String describeShared() {
        if (shared.isEmpty()) {
            return "Share a file to Barq to send it";
        }
        return shared.size() == 1 ? "1 item" : shared.size() + " items";
    }

    private void refreshPeers() {
        if (service == null || peerBox == null) {
            return;
        }
        BarqPeer[] peers;
        try {
            peers = service.getPeers();
        } catch (Exception e) {
            Log.e(TAG, "getPeers failed", e);
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
            tile.setOnClickListener(v -> sendTo(p));
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

    private void sendTo(BarqPeer peer) {
        if (service == null || shared.isEmpty()) {
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
}
