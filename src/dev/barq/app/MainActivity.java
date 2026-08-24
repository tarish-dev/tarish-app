package dev.barq.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
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


/**
 * The receive screen.
 *
 * <p><b>This screen IS the visibility control.</b> The device advertises while it is in
 * front of the user and stops when it is not: {@code onResume} turns discovery on,
 * {@code onPause} turns it off. Nothing advertises from boot.
 *
 * <p>That is a deliberate privacy position rather than a limitation. A device that
 * announces itself to every AirDrop scanner in range from the moment it powers on is a
 * choice nobody made; requiring the app to be open makes being findable an act.
 *
 * <p>It also stands in for a consent prompt, which does not exist yet: the daemon
 * accepts any transfer while visible and refuses every transfer while not. Until there
 * is a per-transfer prompt, closing the app is the only "no", so it has to be a real one
 * — and the pulse has to be honest about which state we are in.
 */
public final class MainActivity extends Activity {

    private static final String TAG = "BarqUI";
    private static final String SERVICE_NAME = "dev.barq.IBarqService/default";

    /**
     * How long one visible session lasts.
     *
     * The daemon expires visibility on its own timer, so a forgotten app does not leave
     * the device advertising indefinitely. Ten minutes is what AirDrop's own "Everyone
     * for 10 Minutes" allows, for the same reason.
     */
    private static final int VISIBLE_SECONDS = 600;

    private final Handler main = new Handler(Looper.getMainLooper());

    private IBarqService service;
    private RadarView radar;
    private TextView deviceName;
    private TextView statusLine;
    private TextView countdown;
    private LinearLayout receivedCard;
    private LinearLayout receivedList;
    private LinearLayout hintCard;
    private LinearLayout progressCard;
    private TextView progressTitle;
    private TextView progressDetail;
    private ProgressBarView progressBar;
    private long activeTransfer;
    private TextView receivedTitle;

    private long visibleUntil;

    /** Ticks the countdown once a second while the screen is up. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long left = (visibleUntil - System.currentTimeMillis()) / 1000;
            if (left > 0) {
                countdown.setText(String.format("%d:%02d remaining", left / 60, left % 60));
                countdown.setVisibility(View.VISIBLE);
                main.postDelayed(this, 1000);
            } else {
                // The daemon owns expiry; the UI just stops claiming otherwise.
                countdown.setVisibility(View.GONE);
                showInvisible();
            }
        }
    };

    private final IBarqCallback callback = new IBarqCallback.Stub() {
        @Override public void onPeerFound(BarqPeer peer) {}
        @Override public void onPeerLost(String peerId) {}

        @Override
        public void onTransferOffered(long id, String peer, String[] names, long bytes) {
            main.post(() -> showReceiving(id));
        }

        @Override
        public void onTransferProgress(long id, long done, long total) {
            main.post(() -> updateProgress(id, done, total));
        }

        @Override
        public void onTransferFinished(long transferId, int status) {
            main.post(() -> {
                hideReceiving();
                if (status < 0) {
                    // Negative means it ended without files -- cancelled, or failed.
                    // Saying so beats leaving a bar frozen at the last percentage.
                    say("Transfer stopped", null);
                    return;
                }
                // Files are in the daemon's private storage at this point. Move them
                // somewhere the user can actually open them.
                collect();
            });
        }
    };

    // ------------------------------------------------------------------ ui ---

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // The default ActionBar draws its own "Barq" on top of ours. Removing it is
        // what makes this read as a sheet rather than a settings page.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        setContentView(buildUi());
        connect();
    }

    private ViewGroup buildUi() {
        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Ui.BG);
        scroller.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 24);
        root.setPadding(side, Ui.dp(this, 28), side, Ui.dp(this, 32));

        // Draw edge to edge, then inset by the real system bars. Hard-coding a status
        // bar height would be wrong on a device with a different cutout, and the first
        // version of this screen had its title hidden underneath the status bar.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(side, bars.top + Ui.dp(this, 20),
                         side, bars.bottom + Ui.dp(this, 32));
            return insets;
        });

        TextView title = Ui.text(this, "Barq", 30, Ui.TEXT, true);
        root.addView(title);

        TextView subtitle = Ui.text(this, "Receive files from nearby devices", 14, Ui.TEXT_MUTED, false);
        subtitle.setPadding(0, Ui.dp(this, 4), 0, 0);
        root.addView(subtitle);

        // The pulse and the identity it belongs to are one block, centred in whatever
        // space is left between the header and the bottom card. Stacking everything at
        // the top left the lower half of the screen empty and made the page look
        // unfinished rather than calm.
        LinearLayout centre = new LinearLayout(this);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(centre, cp);

        radar = new RadarView(this);
        centre.addView(radar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 240)));

        deviceName = Ui.text(this, "", 21, Ui.TEXT, true);
        deviceName.setGravity(Gravity.CENTER);
        deviceName.setPadding(0, Ui.dp(this, 22), 0, 0);
        centre.addView(deviceName);

        statusLine = Ui.text(this, "", 15, Ui.ACCENT, false);
        statusLine.setGravity(Gravity.CENTER);
        statusLine.setPadding(0, Ui.dp(this, 6), 0, 0);
        centre.addView(statusLine);

        countdown = Ui.text(this, "", 13, Ui.TEXT_FAINT, false);
        countdown.setGravity(Gravity.CENTER);
        countdown.setPadding(0, Ui.dp(this, 4), 0, 0);
        countdown.setVisibility(View.GONE);
        centre.addView(countdown);

        root.addView(buildProgressCard());
        root.addView(buildReceivedCard());
        root.addView(buildHint());

        // fillViewport plus a weighted child is what lets the middle block centre while
        // the page still scrolls once the received list grows past one screen.
        scroller.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroller;
    }

    private View buildReceivedCard() {
        receivedCard = new LinearLayout(this);
        receivedCard.setOrientation(LinearLayout.VERTICAL);
        receivedCard.setBackground(Ui.card(this, Ui.SURFACE, Ui.SURFACE_EDGE, 20));
        int p = Ui.dp(this, 18);
        receivedCard.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 32);
        receivedCard.setLayoutParams(lp);
        receivedCard.setVisibility(View.GONE);   // nothing to show until something lands

        receivedTitle = Ui.text(this, "Received", 13, Ui.TEXT_MUTED, true);
        receivedTitle.setLetterSpacing(0.08f);
        receivedCard.addView(receivedTitle);

        receivedList = new LinearLayout(this);
        receivedList.setOrientation(LinearLayout.VERTICAL);
        receivedList.setPadding(0, Ui.dp(this, 10), 0, 0);
        receivedCard.addView(receivedList);

        TextView where = Ui.text(this, "Saved to Downloads/Barq", 12, Ui.TEXT_FAINT, false);
        where.setPadding(0, Ui.dp(this, 12), 0, 0);
        receivedCard.addView(where);

        return receivedCard;
    }

    /** Shown while bytes are arriving: what, how far, and a way to stop it. */
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

        progressTitle = Ui.text(this, "Receiving", 15, Ui.TEXT, true);
        progressCard.addView(progressTitle);

        progressBar = new ProgressBarView(this);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        bp.topMargin = Ui.dp(this, 14);
        progressCard.addView(progressBar, bp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 12), 0, 0);

        progressDetail = Ui.text(this, "Starting\u2026", 13, Ui.TEXT_MUTED, false);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        progressDetail.setLayoutParams(dp2);
        row.addView(progressDetail);

        TextView cancel = Ui.text(this, "Cancel", 13, Ui.ACCENT, true);
        cancel.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        cancel.setBackground(Ui.card(this, Color.parseColor("#1E2A3D"), Color.TRANSPARENT, 18));
        cancel.setOnClickListener(v -> cancelActive());
        row.addView(cancel);

        progressCard.addView(row);
        return progressCard;
    }

    private void showReceiving(long id) {
        activeTransfer = id;
        progressBar.setFraction(0f);
        progressTitle.setText("Receiving");
        progressDetail.setText("Starting\u2026");
        progressCard.setVisibility(View.VISIBLE);
        if (hintCard != null) {
            hintCard.setVisibility(View.GONE);
        }
    }

    private void updateProgress(long id, long done, long total) {
        if (id != activeTransfer) {
            return;   // a late update from a transfer that is no longer on screen
        }
        progressCard.setVisibility(View.VISIBLE);
        if (total > 0) {
            progressBar.setFraction((float) done / total);
            progressDetail.setText(Ui.size(done) + " of " + Ui.size(total)
                    + "  \u00b7  " + (done * 100 / total) + "%");
        } else {
            // No TotalBytes from the peer: report what has arrived rather than a
            // percentage we cannot compute.
            progressDetail.setText(Ui.size(done) + " received");
        }
    }

    private void hideReceiving() {
        activeTransfer = 0;
        progressCard.setVisibility(View.GONE);
    }

    private void cancelActive() {
        if (service == null || activeTransfer == 0) {
            return;
        }
        try {
            service.cancelTransfer(activeTransfer);
            progressTitle.setText("Cancelling\u2026");
            progressDetail.setText("Waiting for the sender to stop");
        } catch (Exception e) {
            Log.e(TAG, "cancelTransfer failed", e);
        }
    }

    /**
     * What to do next.
     *
     * Shown until something arrives, then replaced by the received list. Without it the
     * screen states that it is visible and gives no clue what to do with that.
     */
    private View buildHint() {
        hintCard = new LinearLayout(this);
        hintCard.setOrientation(LinearLayout.HORIZONTAL);
        hintCard.setBackground(Ui.card(this, Ui.SURFACE, Ui.SURFACE_EDGE, 18));
        int p = Ui.dp(this, 16);
        hintCard.setPadding(p, p, p, p);
        hintCard.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = Ui.text(this, "\u2318", 16, Ui.ACCENT, true);
        dot.setGravity(Gravity.CENTER);
        dot.setBackground(Ui.circle(Color.parseColor("#1E2A3D")));
        int s2 = Ui.dp(this, 34);
        hintCard.addView(dot, new LinearLayout.LayoutParams(s2, s2));

        TextView t = Ui.text(this,
                "On an Apple device, open AirDrop and choose this device",
                13, Ui.TEXT_MUTED, false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(this, 12);
        t.setLayoutParams(tp);
        hintCard.addView(t);
        return hintCard;
    }

    /** One row: a coloured chip with the file's extension, its name, and its size. */
    private View fileRow(String name, long bytes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));

        TextView chip = Ui.text(this, extensionOf(name), 10, Ui.BG, true);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(Ui.circle(Ui.ACCENT));
        int s = Ui.dp(this, 34);
        row.addView(chip, new LinearLayout.LayoutParams(s, s));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Ui.dp(this, 12);
        textCol.setLayoutParams(tp);

        TextView n = Ui.text(this, name, 14, Ui.TEXT, false);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        textCol.addView(n);

        if (bytes > 0) {
            textCol.addView(Ui.text(this, Ui.size(bytes), 12, Ui.TEXT_FAINT, false));
        }
        row.addView(textCol);
        return row;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "FILE";
        return ext.length() > 4 ? ext.substring(0, 4).toUpperCase() : ext.toUpperCase();
    }

    // -------------------------------------------------------------- service ---

    private void connect() {
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) {
            deviceName.setText("Barq is not running");
            statusLine.setTextColor(Ui.TEXT_MUTED);
            statusLine.setText("The daemon did not publish its service");
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
        deviceName.setText(android.os.Build.MODEL);
        // BLE advertising is what makes an Apple device ask for us at all, so it starts
        // and stops with visibility rather than running from boot.
        startService(new Intent(this, BarqBleService.class));
        setDiscoverable(true);
        // Anything received while the app was closed is still waiting in the daemon.
        collect();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
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
            return;
        }
        try {
            service.setDiscoverable(visible, visible ? VISIBLE_SECONDS : 0);
            if (visible) {
                radar.setActive(true);
                statusLine.setTextColor(Ui.ACCENT);
                statusLine.setText("Visible to everyone nearby");
                visibleUntil = System.currentTimeMillis() + VISIBLE_SECONDS * 1000L;
                main.removeCallbacks(tick);
                main.post(tick);
            } else {
                showInvisible();
            }
        } catch (Exception e) {
            Log.e(TAG, "setDiscoverable failed", e);
            radar.setActive(false);
            statusLine.setTextColor(Ui.TEXT_MUTED);
            statusLine.setText("Could not reach the Barq service");
        }
    }

    private void showInvisible() {
        radar.setActive(false);
        statusLine.setTextColor(Ui.TEXT_MUTED);
        statusLine.setText("Not visible");
        countdown.setVisibility(View.GONE);
    }

    private void say(String title, String sub) {
        deviceName.setText(title);
        if (sub != null) {
            statusLine.setText(sub);
        }
    }

    private void collect() {
        if (service == null) {
            return;
        }
        for (FileCollector.Stored f : FileCollector.collectAll(this, service)) {
            receivedList.addView(fileRow(f.name, f.bytes));
        }
        int n = receivedList.getChildCount();
        if (n == 0) {
            return;
        }
        receivedTitle.setText(n == 1 ? "RECEIVED 1 FILE" : "RECEIVED " + n + " FILES");
        receivedCard.setVisibility(View.VISIBLE);
        if (hintCard != null) {
            hintCard.setVisibility(View.GONE);
        }
    }
}
