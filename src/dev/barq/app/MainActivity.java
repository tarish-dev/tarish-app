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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.barq.IBarqCallback;
import dev.barq.IBarqService;
import dev.barq.BarqPeer;

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
 * is a per-transfer prompt, closing the app is the only "no", so it has to be a real one.
 */
public final class MainActivity extends Activity {

    private static final String TAG = "BarqUI";
    private static final String SERVICE_NAME = "dev.barq.IBarqService/default";

    /**
     * How long a single visible session lasts if the screen is left open.
     *
     * The daemon expires visibility on its own timer, so a forgotten app does not leave
     * the device advertising indefinitely. Ten minutes matches what AirDrop's own
     * "Everyone for 10 Minutes" setting does, and for the same reason.
     */
    private static final int VISIBLE_SECONDS = 600;

    private final Handler main = new Handler(Looper.getMainLooper());
    private IBarqService service;
    private TextView status;
    private TextView detail;

    private final IBarqCallback callback = new IBarqCallback.Stub() {
        @Override public void onPeerFound(BarqPeer peer) {}
        @Override public void onPeerLost(String peerId) {}
        @Override public void onTransferOffered(long id, String peer, String[] names, long bytes) {}
        @Override public void onTransferProgress(long id, long done, long total) {}

        @Override
        public void onTransferFinished(long transferId, int status) {
            // Files are in the daemon's private storage at this point. Move them
            // somewhere the user can actually open them.
            main.post(() -> collect());
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        connect();
    }

    private ViewGroup buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#101014"));
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        detail = new TextView(this);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        detail.setTextColor(Color.parseColor("#9AA0A6"));
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(12), 0, 0);
        root.addView(detail);

        return root;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void connect() {
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) {
            say("Barq is not running", "the daemon did not publish " + SERVICE_NAME);
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
                say("Visible to everyone", "Others can send to this device while this screen is open");
            }
        } catch (Exception e) {
            Log.e(TAG, "setDiscoverable failed", e);
            say("Not visible", e.getMessage());
        }
    }

    private void collect() {
        if (service == null) {
            return;
        }
        int n = FileCollector.collectAll(this, service);
        if (n > 0) {
            say("Received " + n + (n == 1 ? " file" : " files"), "Saved to Downloads/Barq");
        }
    }

    private void say(String title, String sub) {
        status.setText(title);
        detail.setText(sub == null ? "" : sub);
    }
}
