package dev.tarish.app;

import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import dev.tarish.ITarishService;

/**
 * Gets a LIVE handle on tarishsharingd, every time.
 *
 * <h2>Why this exists</h2>
 *
 * The daemon restarts — every deploy does it, and it can crash — and a proxy held across
 * that is dead in a way that fails silently or lately:
 *
 * <ul>
 *   <li>{@code TransferService} held one and went deaf: no onUpgradeNeeded, so a Wi-Fi
 *       Direct offer was never joined, and no onTransferFinished, so the previous group was
 *       never released. Nothing logged, because nothing failed.</li>
 *   <li>{@code QuickShareReceiver} held one and threw DeadObjectException on the first
 *       inbound connection after a push — an incoming transfer that died before the daemon
 *       ever heard about it.</li>
 * </ul>
 *
 * Two components, the same bug, fixed twice in different ways. So it lives here now: ask for
 * the daemon when you need it rather than remembering one, and the third component cannot
 * repeat it.
 *
 * <p>Cheap enough to call per use — {@code ServiceManager.getService} is a lookup in a
 * process-local cache and a binder ping, not a round trip to start anything.
 */
final class Daemon {
    private static final String TAG = "TarishDaemon";
    private static final String SERVICE_NAME = "dev.tarish.ITarishService/default";

    private Daemon() {
    }

    /** The daemon, or null when it is not published. Never a dead proxy. */
    static ITarishService get() {
        try {
            IBinder b = ServiceManager.getService(SERVICE_NAME);
            if (b == null || !b.pingBinder()) {
                return null;
            }
            return ITarishService.Stub.asInterface(b);
        } catch (Exception e) {
            Log.w(TAG, "the daemon is not reachable", e);
            return null;
        }
    }
}
