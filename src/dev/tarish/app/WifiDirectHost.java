package dev.tarish.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

import dev.tarish.TarishGroup;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Stands up a Wi-Fi Direct group for a sender to join, so an inbound transfer can leave
 * Bluetooth.
 *
 * <p>THE RECEIVER HOSTS. The side that receives {@code UPGRADE_PATH_REQUEST} creates the
 * network and answers with the credentials; the sender joins it. {@link WifiDirectJoiner} is
 * the mirror image, for when we are the one sending.
 *
 * <p>Nothing privileged is involved. {@code createGroup()} needs NEARBY_WIFI_DEVICES and
 * CHANGE_WIFI_STATE, both of which this app already holds for scanning — creating a group is
 * no more privileged than joining one.
 */
final class WifiDirectHost {
    private static final String TAG = "TarishWifiDirectHost";

    /**
     * How long to wait for the group to form.
     *
     * Measured at 4-8 s on real hardware, and first-time driver init is slower. The daemon
     * holds the sender's request while this runs, so it cannot be generous without the
     * sender giving up first — twenty is the same budget the joining side uses.
     */
    private static final long FORM_TIMEOUT_MS = 20_000;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private ConnectionWatcher watcher;

    /**
     * Create the group and describe it. Blocks until it forms or the wait runs out.
     *
     * Returns a group with an empty ssid on any failure, which is an ordinary answer: the
     * transfer continues over Bluetooth rather than failing.
     */
    synchronized TarishGroup create(Context ctx) {
        TarishGroup none = new TarishGroup();
        none.ssid = "";
        none.passphrase = "";
        none.goAddress = "";
        none.frequency = 0;

        // Any previous group first. A stale one makes createGroup fail with BUSY, and the
        // failure names nothing that points at the real cause.
        remove();

        manager = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            Log.i(TAG, "no Wi-Fi Direct on this device");
            return none;
        }
        channel = manager.initialize(ctx, ctx.getMainLooper(), null);
        if (channel == null) {
            Log.w(TAG, "WifiP2pManager.initialize returned null");
            return none;
        }

        watcher = new ConnectionWatcher();
        ctx.registerReceiver(watcher,
                new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                Context.RECEIVER_NOT_EXPORTED);

        try {
            if (!awaitAction("createGroup", listener -> manager.createGroup(channel, listener))) {
                cleanup(ctx);
                return none;
            }
            WifiP2pInfo info = watcher.await(FORM_TIMEOUT_MS);
            if (info == null || !info.groupFormed || !info.isGroupOwner) {
                // Not group owner means something else formed a group with us in it, which
                // is not a network we can invite a sender onto.
                Log.w(TAG, "no group formed, or we are not its owner");
                cleanup(ctx);
                return none;
            }

            WifiP2pGroup group = awaitGroupInfo();
            if (group == null || group.getNetworkName() == null || group.getPassphrase() == null) {
                Log.w(TAG, "the group formed but reported no credentials");
                cleanup(ctx);
                return none;
            }

            TarishGroup out = new TarishGroup();
            out.ssid = group.getNetworkName();
            out.passphrase = group.getPassphrase();
            // FROM WifiP2pInfo, not assumed to be 192.168.49.1. That is a convention, and a
            // wrong address here is a sender that joins and can then reach nothing.
            out.goAddress = info.groupOwnerAddress == null
                    ? ""
                    : info.groupOwnerAddress.getHostAddress();
            out.frequency = group.getFrequency();
            Log.i(TAG, "hosting " + out.ssid + " on " + out.goAddress
                    + " at " + out.frequency + " MHz");
            return out;
        } catch (Exception e) {
            Log.w(TAG, "could not host a group", e);
            cleanup(ctx);
            return none;
        }
    }

    /** Tear the group down. Safe when there is none. */
    synchronized void remove() {
        if (manager == null || channel == null) {
            return;
        }
        try {
            manager.removeGroup(channel, null);
            Log.i(TAG, "released the Wi-Fi Direct group");
        } catch (Exception ignored) {
            // Already gone, or the adapter cycled and took it with it.
        }
    }

    private void cleanup(Context ctx) {
        if (watcher != null) {
            try {
                ctx.unregisterReceiver(watcher);
            } catch (Exception ignored) {
                // Registered a moment ago; only a teardown race gets here.
            }
            watcher = null;
        }
        remove();
    }

    /** Fire a WifiP2pManager call and wait for its own success/failure, not for the link. */
    private boolean awaitAction(String what, ActionCall call) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        call.run(new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                ok[0] = true;
                done.countDown();
            }

            @Override public void onFailure(int reason) {
                // reason 2 is BUSY, which usually means a group is already up somewhere.
                Log.w(TAG, what + " refused, reason=" + reason);
                done.countDown();
            }
        });
        if (!done.await(5, TimeUnit.SECONDS)) {
            Log.w(TAG, what + " never answered");
            return false;
        }
        return ok[0];
    }

    /** The group's own details, which carry the name and passphrase. */
    private WifiP2pGroup awaitGroupInfo() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final WifiP2pGroup[] out = {null};
        manager.requestGroupInfo(channel, group -> {
            out[0] = group;
            done.countDown();
        });
        return done.await(5, TimeUnit.SECONDS) ? out[0] : null;
    }

    private interface ActionCall {
        void run(WifiP2pManager.ActionListener listener);
    }

    /** Waits for the broadcast saying the group is up. */
    private static final class ConnectionWatcher extends BroadcastReceiver {
        private final CountDownLatch formed = new CountDownLatch(1);
        private volatile WifiP2pInfo info;

        @Override
        public void onReceive(Context c, Intent intent) {
            WifiP2pInfo i = intent.getParcelableExtra(
                    WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo.class);
            if (i != null && i.groupFormed) {
                info = i;
                formed.countDown();
            }
        }

        WifiP2pInfo await(long timeoutMs) throws InterruptedException {
            return formed.await(timeoutMs, TimeUnit.MILLISECONDS) ? info : null;
        }
    }
}
