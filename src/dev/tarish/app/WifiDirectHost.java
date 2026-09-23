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
    /** The group currently hosted, or null. See the idempotence note on create(). */
    private TarishGroup current;

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

        // ALREADY HOSTING? HAND BACK THE SAME GROUP.
        //
        // Two components answer the daemon's request for one — TransferService, and
        // MainActivity when the service is not tracking that transfer. Without this,
        // the second caller's remove() below tears down a group the peer has ALREADY
        // JOINED, and the transfer dies on the network it just moved onto:
        //
        //     the sender joined from 192.168.49.121:48780
        //     upgraded the inbound transfer to Wi-Fi Direct
        //     released the Wi-Fi Direct group              <- 9ms later
        //     inbound transfer failed: Software caused connection abort (os error 103)
        //
        // Measured exactly so. A group is a network, not a per-transfer resource, and
        // handing back the live one is both correct and what the second caller wanted.
        // ...BUT ONLY IF IT IS STILL THERE.
        //
        // `current` is cleared in remove() and nowhere else, so a group that dies any other
        // way -- the driver tearing the interface down, the adapter cycling, the radio slot
        // being taken for AWDL -- leaves a corpse cached here forever. Every later receive
        // then hands the daemon an address that exists on no interface, the daemon binds a
        // listener and advertises goAddress:port, and the peer's connect fails INSTANTLY.
        //
        // Measured on blazer, an hour of receives all falling back to Bluetooth:
        //
        //   app:   already hosting DIRECT-Pp-Android_KEzL; reusing it   (x10, in 8ms)
        //   ip:    Device "p2p-wlan0-0" does not exist
        //   rule:  from all iif lo oif p2p-wlan0-0 [detached] ...
        //   peer:  WifiDirect has successfully connected to DIRECT-Pp-Android_KEzL
        //   peer:  MEDIUM_ERROR [WIFI_DIRECT][CONNECT][ESTABLISH_CONNECTION_FAILED]  <- 7ms
        //
        // The same group name came back for an hour across separate transfers, which is the
        // tell: a live group is recreated per session, a cached one is not.
        //
        // The check is local and synchronous on purpose -- requestGroupInfo() is async and
        // this runs on the binder thread answering the daemon. If the group owner address is
        // still assigned to some interface on this device, the group is real; if it is not,
        // the interface is gone and so is the group.
        if (current != null && current.ssid != null && !current.ssid.isEmpty()) {
            if (stillUp(current)) {
                Log.i(TAG, "already hosting " + current.ssid + "; reusing it");
                return current;
            }
            Log.w(TAG, "cached group " + current.ssid + " is gone — " + current.goAddress
                    + " is on no interface; recreating");
            current = null;
        }

        // Any previous group first. A stale one makes createGroup fail with BUSY, and the
        // failure names nothing that points at the real cause.
        remove();

        if (manager == null) {
            manager = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        }
        if (manager == null) {
            Log.i(TAG, "no Wi-Fi Direct on this device");
            return none;
        }
        // ONE Channel for this host's whole lifetime. Every initialize() registers an
        // IBinder with WifiP2pService (system_server); re-initializing on each create() and
        // never closing it leaked one binder per attempt. The daemon re-asks for a group
        // every 2s and fans the request out to every registered callback, so with a stacked
        // callback list that became thousands of Channels -> the receiver was killed for
        // "too many Binders sent to uid 1000". Reuse the Channel; dispose() closes it.
        if (channel == null) {
            channel = manager.initialize(ctx, ctx.getMainLooper(), null);
        }
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
            // Remembered so a second caller reuses this group rather than replacing it.
            current = out;
            return out;
        } catch (Exception e) {
            Log.w(TAG, "could not host a group", e);
            cleanup(ctx);
            return none;
        }
    }

    /**
     * Is a cached group still real, or is it a handle to an interface that has gone?
     *
     * <p>Asks the only question that matters to the peer: is the address we would advertise
     * as the group owner actually assigned to an interface on this device? getByInetAddress
     * returns the interface holding that address, or null when nothing does -- which is
     * precisely the state a torn-down P2P group leaves behind.
     *
     * <p>A literal address never goes to DNS, so this is a local lookup and safe to call on
     * a binder thread.
     */
    private static boolean stillUp(TarishGroup g) {
        if (g.goAddress == null || g.goAddress.isEmpty()) {
            return false;
        }
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(g.goAddress);
            return java.net.NetworkInterface.getByInetAddress(addr) != null;
        } catch (Exception e) {
            // Unparseable, or no interfaces to enumerate. Either way, not usable.
            return false;
        }
    }

    /** Tear the group down. Safe when there is none. */
    synchronized void remove() {
        current = null;
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

    /**
     * Release the group AND close the WifiP2pManager channel. Call when the owning component
     * is torn down (e.g. TransferService.onDestroy) -- the channel holds a binder in
     * system_server that lives until it is closed, and reusing one across transfers only
     * helps if it is eventually returned.
     */
    synchronized void dispose() {
        remove();
        if (channel != null) {
            try {
                channel.close();
            } catch (Exception ignored) {
                // Already gone, or the adapter cycled and took it with it.
            }
            channel = null;
        }
        manager = null;
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
