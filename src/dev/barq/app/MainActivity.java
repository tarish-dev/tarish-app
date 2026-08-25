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
    private LinearLayout status;
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
                    toastLine("Transfer stopped");
                } else if (!sendMode) {
                    collect();
                } else {
                    toastLine("Sent");
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

        TextView title = Ui.text(this, "Barq", 32, Ui.TEXT, false);
        title.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 4));
        column.addView(title);

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
        content.addView(Ui.sectionLabel(this, "You'll appear as"));
        identity = Ui.identityRow(this, Glyph.Kind.BOLT, android.os.Build.MODEL, "Not visible");
        content.addView(identity);

        content.addView(Ui.sectionLabel(this, "Sharing with you"));
        status = Ui.statusCard(this, Glyph.Kind.SWAP, "Getting ready…");
        content.addView(status);

        content.addView(buildProgressCard());
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

    /** A one-line state change on the identity card, which is where the eye already is. */
    private void toastLine(String s) {
        if (!sendMode && identity != null) {
            setIdentityState(s);
        }
    }

    private void setIdentityState(String state) {
        if (identity == null || identity.getChildCount() < 2) {
            return;
        }
        View col = identity.getChildAt(1);
        if (col instanceof LinearLayout && ((LinearLayout) col).getChildCount() >= 2) {
            View sub = ((LinearLayout) col).getChildAt(1);
            if (sub instanceof TextView) {
                ((TextView) sub).setText(state);
            }
        }
    }

    private void setStatus(Glyph.Kind glyph, String label) {
        if (status == null) {
            return;
        }
        int i = content.indexOfChild(status);
        if (i < 0) {
            return;
        }
        LinearLayout replacement = Ui.statusCard(this, glyph, label);
        content.removeViewAt(i);
        content.addView(replacement, i);
        status = replacement;
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
            setIdentityState("Barq is not running");
            return;
        }
        try {
            service.setDiscoverable(visible, visible ? VISIBLE_SECONDS : 0);
            if (!sendMode) {
                setIdentityState(visible ? "Temporarily visible to everyone" : "Not visible");
                setStatus(Glyph.Kind.SWAP, visible ? "Ready to receive" : "Not receiving");
            }
        } catch (Exception e) {
            Log.e(TAG, "setDiscoverable failed", e);
            setIdentityState("Could not reach the Barq service");
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
        long bytes = 0;
        for (FileCollector.Stored f : got) {
            bytes += f.bytes;
        }
        setStatus(Glyph.Kind.CHECK, got.size() + (got.size() == 1 ? " file · " : " files · ")
                + Ui.size(bytes) + "\nSaved to Downloads/Barq");
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
        peerBox.removeAllViews();
        if (peers.length == 0) {
            TextView none = Ui.text(this,
                    "Looking for devices…\nOn an Apple device, open AirDrop and set it to Everyone",
                    13, Ui.TEXT_FAINT, false);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(this, 34), 0, 0);
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
        // A name that is still the raw 12-hex identifier means /Discover has not
        // answered yet, which is worth saying rather than showing a bare hex string.
        return p.name != null && p.name.matches("[0-9a-f]{12}") ? "Not answering" : "AirDrop";
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
