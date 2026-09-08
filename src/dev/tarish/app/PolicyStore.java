package dev.tarish.app;

import android.content.Context;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import dev.tarish.TarishPolicy;
import dev.tarish.ITarishService;

/**
 * What this device is allowed to do, from the two places that get to say so.
 *
 * <p>The user's choice lives in SharedPreferences. An administrator's choice arrives as
 * a managed configuration. Where both speak, THE ADMINISTRATOR WINS, and the field is
 * marked managed so the settings screen can show it pinned rather than silently refusing
 * to move — a control that ignores a tap reads as a broken app, not as an enforced
 * policy.
 *
 * <p>This class only decides. It does not enforce: {@code tarishsharingd} does, because the
 * daemon is what advertises, browses, accepts connections and writes files. An app that
 * merely hides a button is bypassed by killing the app and talking to the daemon
 * directly, so hiding a button is not a policy control.
 *
 * <p><b>The bound on enforcement while the app is closed.</b> The daemon holds policy in
 * memory and starts denied, so it shares nothing until told. If an administrator tightens
 * policy while the app is not running, the daemon still holds the previous push — but the
 * app also drops visibility in onPause and sending needs the app, so there is nothing to
 * exercise the stale grant with. The app re-pushes on every bind, which closes it the
 * moment anything can happen.
 */
final class PolicyStore {

    private static final String TAG = "TarishPolicy";
    private static final String PREFS = "tarish_policy";

    // Preference keys. Deliberately the same strings as the managed-configuration keys,
    // so there is one vocabulary to reason about rather than two that must be mapped.
    private static final String K_AIRDROP = "airdrop";
    private static final String K_QUICKSHARE = "quickshare";
    private static final String K_CONFIRM = "require_confirmation";
    private static final String K_PIN = "require_pin";
    private static final String K_NAME = "device_name";

    private final Context context;

    PolicyStore(Context context) {
        // Device-protected storage: the app is directBootAware, and reading preferences
        // from credential-encrypted storage before first unlock throws.
        this.context = context.createDeviceProtectedStorageContext();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The managed configuration, or an empty Bundle when nothing is enrolled. */
    private Bundle restrictions() {
        RestrictionsManager rm =
                (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);
        if (rm == null) {
            return new Bundle();
        }
        Bundle b = rm.getApplicationRestrictions();
        return b == null ? new Bundle() : b;
    }

    /**
     * Merge user preference and managed configuration into what the daemon should enforce.
     */
    TarishPolicy effective() {
        Bundle managed = restrictions();
        SharedPreferences p = prefs();
        TarishPolicy out = new TarishPolicy();

        // A managed key that is present PINS the value. Absent means the admin has not
        // expressed an opinion, so the user's choice stands -- which is why this checks
        // containsKey rather than comparing against the default: an administrator
        // deliberately setting "both" is not the same as saying nothing.
        out.airdropManaged = managed.containsKey(K_AIRDROP);
        out.airdrop = out.airdropManaged
                ? modeOf(managed.getString(K_AIRDROP))
                : p.getInt(K_AIRDROP, ITarishService.MODE_BOTH);

        out.quickshareManaged = managed.containsKey(K_QUICKSHARE);
        out.quickshare = out.quickshareManaged
                ? modeOf(managed.getString(K_QUICKSHARE))
                : p.getInt(K_QUICKSHARE, ITarishService.MODE_BOTH);

        out.requireConfirmationManaged = managed.containsKey(K_CONFIRM);
        out.requireConfirmation = out.requireConfirmationManaged
                ? managed.getBoolean(K_CONFIRM, true)
                : p.getBoolean(K_CONFIRM, true);

        // Defaults on. The PIN costs a step on every send, and someone handing a file to
        // a device in front of them may not want it -- but it is the only check that the
        // peer we negotiated with is the one in the room, so it is opted OUT of.
        out.requirePinManaged = managed.containsKey(K_PIN);
        out.requirePin = out.requirePinManaged
                ? managed.getBoolean(K_PIN, true)
                : p.getBoolean(K_PIN, true);

        // An empty managed name is "no opinion", not "call the device nothing". An admin
        // who wants to clear a name sets it to the value they want it to have.
        String pinned = managed.getString(K_NAME, "");
        out.deviceNameManaged = pinned != null && !pinned.trim().isEmpty();
        out.deviceName = out.deviceNameManaged ? pinned.trim() : p.getString(K_NAME, "");

        return out;
    }

    /**
     * Map a managed-configuration string to a mode.
     *
     * <p>An unrecognised value means OFF, not "both". A console that sends something this
     * build does not understand has expressed a restriction we cannot honour, and the
     * safe reading of a restriction we do not understand is the restrictive one.
     */
    private static int modeOf(String value) {
        if (value == null) {
            return ITarishService.MODE_OFF;
        }
        switch (value) {
            case "both":
                return ITarishService.MODE_BOTH;
            case "receive_only":
                return ITarishService.MODE_RECEIVE;
            case "send_only":
                return ITarishService.MODE_SEND;
            case "off":
                return ITarishService.MODE_OFF;
            default:
                Log.w(TAG, "unknown managed mode " + value + " — treating as off");
                return ITarishService.MODE_OFF;
        }
    }

    /** The preference key for a protocol, so a caller need not know the string. */
    static String keyFor(int protocol) {
        return protocol == ITarishService.PROTOCOL_AIRDROP ? K_AIRDROP : K_QUICKSHARE;
    }

    /** Record a user choice. Ignored for a field an administrator has pinned. */
    void setUserMode(String key, int mode) {
        prefs().edit().putInt(key, mode).apply();
    }

    void setUserConfirmation(boolean require) {
        prefs().edit().putBoolean(K_CONFIRM, require).apply();
    }

    void setUserRequirePin(boolean require) {
        prefs().edit().putBoolean(K_PIN, require).apply();
    }

    void setUserDeviceName(String name) {
        prefs().edit().putString(K_NAME, name == null ? "" : name.trim()).apply();
    }

    static String keyAirdrop() {
        return K_AIRDROP;
    }

    static String keyQuickshare() {
        return K_QUICKSHARE;
    }

    /** Is any field pinned by an administrator? Drives the "managed" note in settings. */
    static boolean anyManaged(TarishPolicy p) {
        return p.airdropManaged
                || p.quickshareManaged
                || p.requireConfirmationManaged
                || p.requirePinManaged
                || p.deviceNameManaged;
    }

    static boolean allowsSend(int mode) {
        return mode == ITarishService.MODE_SEND || mode == ITarishService.MODE_BOTH;
    }

    static boolean allowsReceive(int mode) {
        return mode == ITarishService.MODE_RECEIVE || mode == ITarishService.MODE_BOTH;
    }

    /** Fold a direction change into the single mode value the schema uses. */
    static int withSend(int mode, boolean send) {
        boolean receive = allowsReceive(mode);
        return combine(send, receive);
    }

    static int withReceive(int mode, boolean receive) {
        boolean send = allowsSend(mode);
        return combine(send, receive);
    }

    private static int combine(boolean send, boolean receive) {
        if (send && receive) {
            return ITarishService.MODE_BOTH;
        }
        if (send) {
            return ITarishService.MODE_SEND;
        }
        if (receive) {
            return ITarishService.MODE_RECEIVE;
        }
        return ITarishService.MODE_OFF;
    }
}
