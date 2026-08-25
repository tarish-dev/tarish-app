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
 * The share target: pick a nearby device and send.
 *
 * <p>Reached from any app's share sheet. It lists what AirDrop peers are visible right
 * now and sends to the one that is tapped.
 *
 * <p><b>A peer only appears once it has answered.</b> AirDrop carries no name over mDNS
 * — the TXT record holds a flags field and nothing else — so the daemon asks each peer
 * over {@code /Discover}, and a device that will not answer is a device that will not
 * accept a file either. Listing it would be offering something that cannot work.
 */
public final class SendActivity extends Activity {

    private static final String TAG = "BarqSend";
    private static final String SERVICE_NAME = "dev.barq.IBarqService/default";

    /** How often the peer list is refreshed while the picker is open. */
    private static final long POLL_MS = 1500;

    private final Handler main = new Handler(Looper.getMainLooper());

    private IBarqService service;
    private LinearLayout peerList;
    private TextView headline;
    private TextView subhead;
    private LinearLayout progressCard;
    private TextView progressDetail;
    private ProgressBarView progressBar;

    private final List<Uri> shared = new ArrayList<>();
    private long activeTransfer;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshPeers();
            main.postDelayed(this, POLL_MS);
        }
    };

    private final IBarqCallback callback = new IBarqCallback.Stub() {
        @Override public void onPeerFound(BarqPeer peer) {}
        @Override public void onPeerLost(String peerId) {}
        @Override public void onTransferOffered(long id, String peer, String[] names, long bytes) {}

        @Override
        public void onTransferProgress(long id, long done, long total) {
            main.post(() -> {
                if (id != activeTransfer) {
                    return;
                }
                progressBar.setFraction(total > 0 ? (float) done / total : 0f);
                progressDetail.setText(total > 0
                        ? Ui.size(done) + " of " + Ui.size(total)
                        : Ui.size(done) + " sent");
            });
        }

        @Override
        public void onTransferFinished(long id, int status) {
            main.post(() -> {
                if (id != activeTransfer) {
                    return;
                }
                if (status < 0) {
                    headline.setText("Could not send");
                    subhead.setText("The device declined or went away");
                    progressCard.setVisibility(View.GONE);
                    // Back to the picker: the user may want to try another device.
                    main.postDelayed(SendActivity.this::showPicker, 1800);
                } else {
                    headline.setText("Sent");
                    subhead.setText(shared.size() == 1 ? "1 file delivered"
                                                       : shared.size() + " files delivered");
                    progressCard.setVisibility(View.GONE);
                    main.postDelayed(SendActivity.this::finish, 1400);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        collectShared(getIntent());
        setContentView(buildUi());

        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) {
            headline.setText("Barq is not running");
            subhead.setText("The daemon did not publish its service");
            return;
        }
        service = IBarqService.Stub.asInterface(binder);
        try {
            service.registerCallback(callback);
        } catch (Exception e) {
            Log.e(TAG, "could not register callback", e);
        }
        if (shared.isEmpty()) {
            headline.setText("Nothing to send");
            subhead.setText("Share a file to Barq to send it");
        }
    }

    /** Pull the shared content out of the intent, single or multiple. */
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

    // ------------------------------------------------------------------ ui ---

    private ViewGroup buildUi() {
        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Ui.BG);
        scroller.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 24);
        root.setPadding(side, Ui.dp(this, 28), side, Ui.dp(this, 32));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(side, bars.top + Ui.dp(this, 20), side, bars.bottom + Ui.dp(this, 32));
            return insets;
        });

        headline = Ui.text(this, "Send with Barq", 26, Ui.TEXT, true);
        root.addView(headline);

        subhead = Ui.text(this, describeShared(), 14, Ui.TEXT_MUTED, false);
        subhead.setPadding(0, Ui.dp(this, 6), 0, 0);
        root.addView(subhead);

        root.addView(buildProgressCard());

        TextView label = Ui.text(this, "NEARBY DEVICES", 12, Ui.TEXT_FAINT, true);
        label.setLetterSpacing(0.08f);
        label.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 8));
        root.addView(label);

        peerList = new LinearLayout(this);
        peerList.setOrientation(LinearLayout.VERTICAL);
        root.addView(peerList);

        scroller.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroller;
    }

    private View buildProgressCard() {
        progressCard = new LinearLayout(this);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressCard.setBackground(Ui.card(this, Ui.SURFACE, Ui.ACCENT_DIM, 20));
        int p = Ui.dp(this, 18);
        progressCard.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 24);
        progressCard.setLayoutParams(lp);
        progressCard.setVisibility(View.GONE);

        progressBar = new ProgressBarView(this);
        progressCard.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6)));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 12), 0, 0);

        progressDetail = Ui.text(this, "Starting…", 13, Ui.TEXT_MUTED, false);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        progressDetail.setLayoutParams(dp2);
        row.addView(progressDetail);

        TextView cancel = Ui.text(this, "Cancel", 13, Ui.ACCENT, true);
        cancel.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        cancel.setBackground(Ui.card(this, Color.parseColor("#1E2A3D"), Color.TRANSPARENT, 18));
        cancel.setOnClickListener(v -> {
            if (service != null && activeTransfer != 0) {
                try {
                    service.cancelTransfer(activeTransfer);
                    progressDetail.setText("Cancelling…");
                } catch (Exception e) {
                    Log.e(TAG, "cancelTransfer failed", e);
                }
            }
        });
        row.addView(cancel);
        progressCard.addView(row);
        return progressCard;
    }

    private String describeShared() {
        if (shared.isEmpty()) {
            return "Nothing shared";
        }
        return shared.size() == 1 ? "1 file" : shared.size() + " files";
    }

    private View peerRow(BarqPeer peer) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.card(this, Ui.SURFACE, Ui.SURFACE_EDGE, 16));
        int p = Ui.dp(this, 14);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        row.setLayoutParams(lp);

        TextView dot = Ui.text(this, initial(peer.name), 16, Ui.BG, true);
        dot.setGravity(Gravity.CENTER);
        dot.setBackground(Ui.circle(Ui.ACCENT));
        int s = Ui.dp(this, 40);
        row.addView(dot, new LinearLayout.LayoutParams(s, s));

        TextView name = Ui.text(this, peer.name, 15, Ui.TEXT, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = Ui.dp(this, 14);
        name.setLayoutParams(np);
        row.addView(name);

        row.setOnClickListener(v -> sendTo(peer));
        return row;
    }

    private static String initial(String name) {
        return name == null || name.isEmpty()
                ? "?" : name.substring(0, 1).toUpperCase();
    }

    // ------------------------------------------------------------- service ---

    @Override
    protected void onResume() {
        super.onResume();
        main.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(poll);
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

    private void showPicker() {
        headline.setText("Send with Barq");
        subhead.setText(describeShared());
        activeTransfer = 0;
        main.post(poll);
    }

    private void refreshPeers() {
        if (service == null) {
            return;
        }
        BarqPeer[] peers;
        try {
            peers = service.getPeers();
        } catch (Exception e) {
            Log.e(TAG, "getPeers failed", e);
            return;
        }
        peerList.removeAllViews();
        if (peers.length == 0) {
            TextView none = Ui.text(this,
                    "Looking… open AirDrop on the device you want to send to",
                    13, Ui.TEXT_FAINT, false);
            none.setPadding(0, Ui.dp(this, 8), 0, 0);
            peerList.addView(none);
            return;
        }
        for (BarqPeer p : peers) {
            peerList.addView(peerRow(p));
        }
    }

    private void sendTo(BarqPeer peer) {
        if (service == null || shared.isEmpty()) {
            return;
        }
        main.removeCallbacks(poll);

        List<ParcelFileDescriptor> fds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Uri uri : shared) {
            try {
                ParcelFileDescriptor pfd =
                        getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) {
                    continue;
                }
                fds.add(pfd);
                names.add(displayName(uri));
            } catch (Exception e) {
                Log.e(TAG, "could not open " + uri, e);
            }
        }
        if (fds.isEmpty()) {
            headline.setText("Could not read the files");
            subhead.setText("Nothing was sent");
            return;
        }

        headline.setText("Sending to " + peer.name);
        subhead.setText(describeShared());
        progressBar.setFraction(0f);
        progressDetail.setText("Starting…");
        progressCard.setVisibility(View.VISIBLE);
        peerList.removeAllViews();

        try {
            activeTransfer = service.sendFiles(
                    peer.id,
                    fds.toArray(new ParcelFileDescriptor[0]),
                    names.toArray(new String[0]));
        } catch (Exception e) {
            Log.e(TAG, "sendFiles failed", e);
            headline.setText("Could not send");
            subhead.setText(String.valueOf(e.getMessage()));
            progressCard.setVisibility(View.GONE);
        } finally {
            // The daemon duplicates the descriptors it needs, so ours are ours to close.
            for (ParcelFileDescriptor pfd : fds) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                    // Nothing useful to do; the send either took a dup or already failed.
                }
            }
        }
    }

    /**
     * A filename for a content URI.
     *
     * Falls back to the last path segment, and finally to a generic name: a peer needs
     * something to call the file, and an empty name is worse than a dull one.
     */
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
