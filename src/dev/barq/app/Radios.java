package dev.barq.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.util.Log;

/**
 * Turn the radios Barq needs on, and put them back exactly as they were.
 *
 * WHY THIS EXISTS
 *
 * With Wi-Fi off, AWDL does not merely perform badly -- it cannot start at all. Measured
 * on frankel: with Wi-Fi disabled every radio mode and every channel is refused, because
 * the AWDL driver rides the Wi-Fi driver's interface.
 *
 *     barqd: Netlink  + channel [6]        refused: mosey_start_5 returned NULL
 *     barqd: Radiotap + channel [149, 44]  refused: mosey_start_5 returned NULL
 *
 * So "Wi-Fi is on" is a precondition, not a preference. Before this class, the app did
 * not say so -- it did not even hold ACCESS_WIFI_STATE, so it could not tell. A user with
 * Wi-Fi off saw an app that simply never found anybody, which is what was reported.
 *
 * THE RULE THAT MATTERS: RESTORE ONLY WHAT WE CHANGED
 *
 * We turn a radio on only with the user's say-so, and we turn it back off only if WE were
 * the ones who turned it on. A radio the user already had on is never touched. Getting
 * this backwards -- disabling something the user wanted -- is far worse than leaving a
 * radio on, so every ambiguous case below resolves towards leaving it alone.
 *
 * STATE IS DELIBERATELY NOT PERSISTED
 *
 * If the app is killed while a radio is on because of us, we forget, and the radio stays
 * on. That is the intended failure: a leaked "on" is a minor annoyance the user can undo,
 * whereas restoring a stale flag after a reboot would switch off a radio the user had
 * deliberately enabled hours earlier, with no way to connect the two events.
 */
final class Radios {

    private static final String TAG = "Barq";

    private final Context context;

    /** True only while a radio is on BECAUSE WE TURNED IT ON, and the user has not since intervened. */
    private boolean weEnabledWifi;
    private boolean weEnabledBt;

    /**
     * Whether we have actually SEEN the radio reach the on state since we claimed it.
     *
     * Registering for WIFI_STATE_CHANGED_ACTION immediately delivers the CURRENT state,
     * and we register just before switching the radio on -- so the first thing the
     * receiver ever saw was DISABLED, which it read as "the user turned it off" and used
     * to drop a claim we had only just made. Measured: the radios came on and the claim
     * was dropped in the same second, so nothing was ever restored.
     *
     * An off transition only means the user intervened if we had previously watched the
     * radio come on.
     */
    private boolean wifiReachedOn;
    private boolean btReachedOn;

    private BroadcastReceiver watcher;

    Radios(Context context) {
        this.context = context.getApplicationContext();
    }

    // ------------------------------------------------------------------ state ---

    boolean wifiOn() {
        try {
            WifiManager wm = context.getSystemService(WifiManager.class);
            return wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            // Without ACCESS_WIFI_STATE this throws. Claim it is on: that suppresses a
            // prompt we could not act on anyway, and leaves the existing behaviour.
            Log.w(TAG, "cannot read Wi-Fi state", e);
            return true;
        }
    }

    boolean bluetoothOn() {
        try {
            BluetoothManager bm = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
            return a == null || a.isEnabled();
        } catch (Exception e) {
            Log.w(TAG, "cannot read Bluetooth state", e);
            return true;
        }
    }

    /** True when something is off and turning it on would help. */
    boolean anythingOff() {
        return !wifiOn() || !bluetoothOn();
    }

    /** Human-readable list of what is off, for the prompt. Empty when nothing is. */
    String whatIsOff() {
        boolean w = !wifiOn(), b = !bluetoothOn();
        if (w && b) return "Wi-Fi and Bluetooth";
        if (w) return "Wi-Fi";
        if (b) return "Bluetooth";
        return "";
    }

    // ------------------------------------------------------------------ enable ---

    /**
     * Turn on whatever is off. Call only after the user has agreed.
     *
     * @return true if everything Barq needs is now on.
     */
    boolean enableAll() {
        watch();
        boolean ok = true;
        if (!wifiOn()) {
            ok &= setWifi(true, /* remember= */ true);
        }
        if (!bluetoothOn()) {
            ok &= setBluetooth(true, /* remember= */ true);
        }
        return ok;
    }

    /**
     * Put back anything WE turned on. Anything the user had on stays on.
     *
     * The caller decides when this is safe -- see MainActivity. It must not run while a
     * transfer is in flight, for the same reason the AWDL radio is not released then.
     */
    void restore() {
        if (weEnabledWifi) {
            Log.i(TAG, "restoring Wi-Fi to off — we were the ones who turned it on");
            setWifi(false, /* remember= */ false);
            weEnabledWifi = false;
            wifiReachedOn = false;
        }
        if (weEnabledBt) {
            Log.i(TAG, "restoring Bluetooth to off — we were the ones who turned it on");
            setBluetooth(false, /* remember= */ false);
            weEnabledBt = false;
            btReachedOn = false;
        }
        unwatch();
    }

    /** Nothing to put back. Called when the user takes ownership of a radio. */
    void forget() {
        weEnabledWifi = false;
        weEnabledBt = false;
        wifiReachedOn = false;
        btReachedOn = false;
        unwatch();
    }

    boolean owesRestore() {
        return weEnabledWifi || weEnabledBt;
    }

    // ------------------------------------------------------------------ toggle ---

    private boolean setWifi(boolean on, boolean remember) {
        try {
            WifiManager wm = context.getSystemService(WifiManager.class);
            if (wm == null) {
                return false;
            }
            // Deprecated since API 29 and returns false for ordinary apps. It works here
            // because Barq is a platform-signed privileged app holding NETWORK_SETTINGS.
            // If that ever stops being true this returns false rather than throwing, and
            // the caller tells the user instead of failing silently -- which is the whole
            // bug this class exists to fix.
            boolean ok = wm.setWifiEnabled(on);
            if (ok && on && remember) {
                weEnabledWifi = true;
            }
            if (!ok) {
                Log.w(TAG, "setWifiEnabled(" + on + ") refused");
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "could not change Wi-Fi state", e);
            return false;
        }
    }

    private boolean setBluetooth(boolean on, boolean remember) {
        try {
            BluetoothManager bm = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
            if (a == null) {
                return false;
            }
            boolean ok = on ? a.enable() : a.disable();
            if (ok && on && remember) {
                weEnabledBt = true;
            }
            if (!ok) {
                Log.w(TAG, "Bluetooth " + (on ? "enable" : "disable") + "() refused");
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "could not change Bluetooth state", e);
            return false;
        }
    }

    // ----------------------------------------------------------------- watcher ---

    /**
     * Stop claiming a radio the moment the user touches it.
     *
     * Without this, a user who turns Wi-Fi off from the quick settings while Barq is open
     * would find us turning it on... no -- worse: we would still believe we owned it, and
     * on the next restore we would switch off a radio they had since re-enabled
     * themselves. Any transition to OFF means we no longer owe anyone a restore.
     */
    private void watch() {
        if (watcher != null) {
            return;
        }
        watcher = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String action = i.getAction();
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    int s = i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                    if (s == WifiManager.WIFI_STATE_ENABLED) {
                        wifiReachedOn = true;
                    } else if ((s == WifiManager.WIFI_STATE_DISABLED || s == WifiManager.WIFI_STATE_DISABLING)
                            && wifiReachedOn) {
                        if (weEnabledWifi) {
                            Log.i(TAG, "Wi-Fi switched off by the user — dropping our claim on it");
                        }
                        weEnabledWifi = false;
                    }
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int s = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    if (s == BluetoothAdapter.STATE_ON) {
                        btReachedOn = true;
                    } else if ((s == BluetoothAdapter.STATE_OFF || s == BluetoothAdapter.STATE_TURNING_OFF)
                            && btReachedOn) {
                        if (weEnabledBt) {
                            Log.i(TAG, "Bluetooth switched off by the user — dropping our claim on it");
                        }
                        weEnabledBt = false;
                    }
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            context.registerReceiver(watcher, f, Context.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            Log.w(TAG, "could not watch radio state", e);
            watcher = null;
        }
    }

    private void unwatch() {
        if (watcher == null) {
            return;
        }
        try {
            context.unregisterReceiver(watcher);
        } catch (Exception e) {
            // Already gone. Not worth reporting.
        }
        watcher = null;
    }
}
