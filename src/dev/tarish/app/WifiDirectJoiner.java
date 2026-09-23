package dev.tarish.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import dev.tarish.ITarishService;
import dev.tarish.TarishUpgrade;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Joins the Wi-Fi Direct group a Quick Share peer stood up, and hands the daemon a socket
 * on it.
 *
 * WHY THIS IS IN THE APP
 *
 * A Quick Share transfer with no shared network bootstraps over Bluetooth, which measures
 * around 70 KB/s -- a 3 MB photo takes forty seconds. The protocol's answer is a bandwidth
 * upgrade: the peer forms a Wi-Fi Direct group and sends its name, passphrase and port,
 * and we move the same encrypted conversation onto a socket over that link.
 *
 * tarishsharingd decodes that offer and cannot act on it. WifiP2pManager is framework API
 * and the daemon is a native service -- the same boundary that puts BLE scanning here. So
 * the daemon asks (ITarishCallback.onUpgradeNeeded), this joins, and the socket goes back
 * over binder (ITarishService.provideUpgradeSocket).
 *
 * WHAT THIS DOES NOT NEED
 *
 * Nothing privileged. Joining by network name and passphrase is
 * {@code WifiP2pManager.connect()} with a {@link WifiP2pConfig}, which asks only for
 * NEARBY_WIFI_DEVICES and CHANGE_WIFI_STATE and shows no dialog. It is specifically NOT
 * {@code WifiNetworkSpecifier}, which would put a system prompt in front of every transfer
 * unless the app held NETWORK_SETTINGS. Bada joins this way holding nothing more, which is
 * what establishes it: the privileged half of the negotiation runs on the PEER's device,
 * where the peer already has what it needs.
 *
 * DECLINING IS A NORMAL OUTCOME
 *
 * Every failure here answers with a null socket rather than throwing. The transfer is
 * already running over Bluetooth and finishes there, only slower, so a permission we do
 * not have or a driver that will not associate costs speed and not the transfer. The one
 * thing that must not happen is silence: the daemon has parked the transfer waiting for
 * this answer.
 */
final class WifiDirectJoiner {
    private static final String TAG = "TarishWifiDirect";

    /** WIFI_DIRECT, in the wire protocol's own medium numbering. */
    private static final int MEDIUM_WIFI_DIRECT = 8;

    /**
     * The WHOLE budget for joining and connecting, counted from the moment we are asked.
     *
     * ONE DEADLINE, NOT ONE PER STAGE. This was three independent timeouts -- 20s for the
     * group, 5s for the connect listener, 10s for the socket -- which add to 35s against a
     * daemon that gives up at 30s. Every stage could be inside its own limit while the whole
     * thing was outside the only limit that matters, and the daemon would abandon a join
     * that was about to succeed.
     *
     * Twenty-five seconds, so the answer always beats the daemon's wait with margin. Keep it
     * BELOW await_upgrade's timeout in sharingd; if that changes, this changes.
     */
    private static final long JOIN_BUDGET_MS = 25_000;

    /**
     * The most any single stage may take, so one slow step cannot eat the whole budget and
     * leave nothing for the rest. The real bound is always the deadline.
     */
    private static final long STAGE_CAP_MS = 15_000;

    /**
     * Wi-Fi Direct network names always begin this way. Checked because a name that does
     * not is not a group we can join, and WifiP2pConfig.Builder would throw on it -- an
     * exception out of a callback thread rather than a declined upgrade.
     */
    private static final String DIRECT_PREFIX = "DIRECT-";

    /**
     * Teardown for the group each transfer joined, so the radio is released when the
     * transfer ends rather than when the process does.
     *
     * Keyed by transfer id: the group must OUTLIVE this class's work -- we hand the socket
     * over and return, and the transfer then runs on it for as long as the files take. So
     * nothing here can tear down on the way out, and {@link #release} is called from
     * TransferService when the transfer finishes.
     */
    private static final Map<Long, Runnable> teardowns = new HashMap<>();

    private WifiDirectJoiner() {
    }

    /**
     * Join, connect, and answer the daemon. Blocks; call it off the main thread.
     *
     * Answers exactly once, on every path.
     */
    static void join(Context ctx, ITarishService service, long transferId, TarishUpgrade up) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = attempt(ctx, transferId, up);
        } catch (Throwable t) {
            // Deliberately Throwable. This runs on a binder thread's behalf and the answer
            // below is what unparks the transfer, so an unexpected failure must still be
            // reported rather than killing the thread with the daemon still waiting.
            Log.w(TAG, "join failed", t);
        }
        try {
            service.provideUpgradeSocket(transferId, pfd);
        } catch (Exception e) {
            Log.w(TAG, "could not answer the upgrade request", e);
        } finally {
            if (pfd != null) {
                // The descriptor was duplicated into the parcel; ours is now surplus.
                try {
                    pfd.close();
                } catch (Exception ignored) {
                    // Nothing useful to do, and the transfer is already under way.
                }
            }
        }
    }

    /** Milliseconds left before the daemon stops waiting, capped so no stage hogs it. */
    private static long remaining(long deadline) {
        return Math.min(STAGE_CAP_MS, Math.max(0, deadline - android.os.SystemClock.elapsedRealtime()));
    }

    private static ParcelFileDescriptor attempt(Context ctx, long transferId, TarishUpgrade up)
            throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + JOIN_BUDGET_MS;
        if (up.medium != MEDIUM_WIFI_DIRECT) {
            // Hotspot (medium 3) reaches here as an ordinary access point and is not
            // implemented. Declining is correct rather than guessing at a join.
            Log.i(TAG, "offered medium " + up.medium + ", which is not Wi-Fi Direct; declining");
            return null;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "joining by network name needs API 29+; declining");
            return null;
        }
        if (up.ssid == null || !up.ssid.startsWith(DIRECT_PREFIX)) {
            Log.w(TAG, "peer's network name is not a Wi-Fi Direct group; declining");
            return null;
        }
        if (up.passphrase == null || up.passphrase.length() < 8 || up.passphrase.length() > 63) {
            // Length only. The value is a WPA2 passphrase and does not belong in a log.
            Log.w(TAG, "peer's passphrase is not a usable length; declining");
            return null;
        }
        if (up.port <= 0 || up.port > 65535) {
            Log.w(TAG, "peer offered port " + up.port + "; declining");
            return null;
        }

        WifiP2pManager manager =
                (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            Log.i(TAG, "no Wi-Fi Direct on this device; declining");
            return null;
        }
        WifiP2pManager.Channel channel = manager.initialize(ctx, Looper.getMainLooper(), null);
        if (channel == null) {
            Log.i(TAG, "WifiP2pManager.initialize returned null; declining");
            return null;
        }

        ConnectionWatcher watcher = new ConnectionWatcher();
        // NOT_EXPORTED because the only sender we want is the system. Apps targeting 34+
        // must state a flag for a registered receiver, and while a system-only broadcast is
        // exempt, relying on that exemption means a SecurityException the moment the action
        // stops being classed as protected -- and this failing looks exactly like a peer
        // that would not form a group.
        ctx.registerReceiver(watcher,
                new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                Context.RECEIVER_NOT_EXPORTED);
        boolean joined = false;
        try {
            WifiP2pConfig config = new WifiP2pConfig.Builder()
                    .setNetworkName(up.ssid)
                    .setPassphrase(up.passphrase)
                    // The group is for this transfer. Persisting it would leave the device
                    // rejoining a one-off network later.
                    .enablePersistentMode(false)
                    .build();
            // The peer formed the group and gave us its passphrase, so there is nothing to
            // push a button for. Left at PBC so the platform does not raise a PIN prompt.
            config.wps.setup = WpsInfo.PBC;

            if (!connect(manager, channel, config, remaining(deadline))) {
                return null;
            }
            WifiP2pInfo info = watcher.await(remaining(deadline));
            if (info == null) {
                Log.w(TAG, "Wi-Fi Direct did not form a group before the deadline; declining");
                cancel(manager, channel);
                return null;
            }
            joined = true;

            // The peer usually names no address, because it does not know what the group
            // will hand out. The framework does: the group owner is the peer that formed
            // it, which is the one we are connecting to.
            InetAddress target;
            if (up.gateway != null && !up.gateway.isEmpty()) {
                target = InetAddress.getByName(up.gateway);
            } else if (info.groupOwnerAddress != null) {
                target = info.groupOwnerAddress;
            } else {
                Log.w(TAG, "group formed but no address to connect to; declining");
                cancel(manager, channel);
                return null;
            }

            ParcelFileDescriptor sock = connectSocket(ctx, target, up.port, remaining(deadline));
            if (sock == null) {
                cancel(manager, channel);
                return null;
            }
            // Handed over. The group has to stay up for the whole transfer, so teardown is
            // registered against the transfer instead of happening here.
            remember(transferId, manager, channel);
            joined = false;   // ownership passed to the teardown
            Log.i(TAG, "joined " + up.ssid + " and connected to " + target + ":" + up.port);
            return sock;
        } finally {
            try {
                ctx.unregisterReceiver(watcher);
            } catch (Exception ignored) {
                // Registered a few lines up; only a teardown race gets here.
            }
            if (joined) {
                // Formed a group and then could not use it. Release the radio rather than
                // leaving the device associated to a network nothing will speak on.
                cancel(manager, channel);
            }
        }
    }

    /** Fire WifiP2pManager.connect and wait for its own success/failure, not for the link. */
    private static boolean connect(WifiP2pManager manager, WifiP2pManager.Channel channel,
            WifiP2pConfig config, long budgetMs) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                ok[0] = true;
                done.countDown();
            }

            @Override public void onFailure(int reason) {
                Log.w(TAG, "WifiP2pManager.connect refused, reason=" + reason);
                done.countDown();
            }
        });
        // The listener is answered on the main looper and is quick. This bound only exists
        // so a listener that is never called cannot hold the transfer.
        // Answered on the main looper and quick. Bounded only so a listener that is never
        // called cannot hold the transfer, and bounded by the SHARED budget so it cannot
        // spend time the stages after it need.
        if (!done.await(Math.min(5_000, budgetMs), TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "WifiP2pManager.connect never answered; declining");
            return false;
        }
        return ok[0];
    }

    /**
     * Bind a socket to the Wi-Fi Direct network, so the kernel knows which one we meant.
     *
     * <p>Found by INTERFACE NAME rather than by transport type. There is no public
     * {@code TRANSPORT_WIFI_P2P} to match on, and the group's interface is reliably
     * {@code p2p-wlan0-N} — the N changes per group, which is why this matches a prefix and
     * not a fixed name.
     *
     * <p>Best effort by design. Every failure path here leaves the socket unbound, which is
     * how this worked before, so adding it cannot make anything worse:
     * <ul>
     *   <li>no P2P network yet — the group may still be forming</li>
     *   <li>{@code getAllNetworks()} hiding it, which is a real possibility under VPN
     *       lockdown, where ConnectivityService filters what a blocked uid may see. If that
     *       turns out to be the case, this needs the routing-rule approach instead — see
     *       grapheneos/docs/VPN-LOCKDOWN.md.</li>
     *   <li>{@code bindSocket} throwing, e.g. the network going away mid-call</li>
     * </ul>
     */
    private static void bindToP2p(Context ctx, FileDescriptor fd) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                String iface = lp == null ? null : lp.getInterfaceName();
                if (iface != null && iface.startsWith("p2p-")) {
                    n.bindSocket(fd);
                    Log.i(TAG, "bound the upgrade socket to " + iface);
                    return;
                }
            }
            Log.i(TAG, "no Wi-Fi Direct network to bind to; continuing unbound");
        } catch (Throwable t) {
            // Deliberately Throwable: this is an optimisation on the path to a transfer, and
            // nothing here is worth failing the transfer over.
            Log.w(TAG, "could not bind the upgrade socket to the P2P network", t);
        }
    }

    /**
     * Connect a TCP socket with a real deadline, and return it as a descriptor.
     *
     * Built on Os rather than java.net.Socket because the daemon needs the DESCRIPTOR, and
     * a Socket's fd is not reachable through public API. Non-blocking connect plus poll
     * gives the timeout: a blocking connect to an address on a link that just came up can
     * sit for the kernel's full retry schedule, which is minutes, and the daemon would give
     * up on us long before that.
     */
    private static ParcelFileDescriptor connectSocket(Context ctx, InetAddress target,
                                                      int port, long budgetMs) {
        FileDescriptor fd = null;
        try {
            fd = Os.socket(
                    target instanceof Inet4Address ? OsConstants.AF_INET : OsConstants.AF_INET6,
                    OsConstants.SOCK_STREAM, 0);
            // SAY WHICH NETWORK, BEFORE CONNECTING. Two things depend on it.
            //
            // 1. THE VPN KILL-SWITCH. "Block connections without VPN" installs
            //      14000: from all fwmark 0x0/0x20000 iif lo uidrange 1-10206 prohibit
            //    which covers this app's uid. Note `fwmark 0x0/0x20000`: it only matches when
            //    the "explicitly selected network" bit is CLEAR. A socket bound to a Network
            //    carries that bit, so the block stops matching -- no routing rules, no
            //    privileged changes, just saying which network we meant. This is Android's
            //    own mechanism, not a trick.
            //
            // 2. ROUTING GENERALLY. Android routes by fwmark with a table per network, and an
            //    unmarked socket goes to the default network -- which off-network is nothing
            //    at all. The manifest note on INTERNET already says only the app can bind to
            //    the P2P network the framework just created.
            //
            // Best effort on purpose: if the network cannot be found or the bind fails we
            // carry on unbound, which is exactly what this did before. It can only help.
            bindToP2p(ctx, fd);
            int flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0);
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags | OsConstants.O_NONBLOCK);

            boolean connected;
            try {
                Os.connect(fd, target, port);
                connected = true;
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.EINPROGRESS) {
                    throw e;
                }
                connected = false;
            }
            if (!connected) {
                StructPollfd p = new StructPollfd();
                p.fd = fd;
                p.events = (short) OsConstants.POLLOUT;
                int ready = Os.poll(new StructPollfd[] {p}, (int) budgetMs);
                if (ready == 0) {
                    Log.w(TAG, "connect to " + target + ":" + port + " timed out");
                    Os.close(fd);
                    return null;
                }
                // Writable does not mean connected -- a refused connection is also
                // writable, and the error is only readable through SO_ERROR.
                int err = Os.getsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_ERROR);
                if (err != 0) {
                    Log.w(TAG, "connect to " + target + ":" + port + " failed, errno=" + err);
                    Os.close(fd);
                    return null;
                }
            }
            // Back to blocking: the daemon reads and writes this as an ordinary stream and
            // would see spurious EAGAIN otherwise.
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags);
            // dup, so closing our copy after the parcel is sent does not close the daemon's.
            ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(fd);
            Os.close(fd);
            return pfd;
        } catch (Exception e) {
            Log.w(TAG, "could not connect to " + target + ":" + port, e);
            if (fd != null) {
                try {
                    Os.close(fd);
                } catch (ErrnoException ignored) {
                    // Already closed, or never opened.
                }
            }
            return null;
        }
    }

    private static void remember(long transferId, WifiP2pManager manager,
            WifiP2pManager.Channel channel) {
        Runnable stale;
        synchronized (teardowns) {
            stale = teardowns.put(transferId, () -> cancel(manager, channel));
        }
        // One transfer, one group. A second join for the same id means the first was
        // abandoned, and its group would otherwise be held until the process exits.
        if (stale != null) {
            stale.run();
        }
    }

    /**
     * Release the group a transfer was using. Safe to call for a transfer that never
     * joined one, which is the common case.
     */
    static void release(long transferId) {
        Runnable t;
        synchronized (teardowns) {
            t = teardowns.remove(transferId);
        }
        if (t != null) {
            Log.i(TAG, "releasing the Wi-Fi Direct group for transfer " + transferId);
            t.run();
        }
    }

    private static void cancel(WifiP2pManager manager, WifiP2pManager.Channel channel) {
        // Both, and in this order. cancelConnect ends a negotiation still in progress;
        // removeGroup ends one that completed. Which applies depends on how far the join
        // got, and asking for the wrong one is merely a no-op.
        try {
            manager.cancelConnect(channel, null);
        } catch (Exception ignored) {
            // Nothing to do: we are giving up either way.
        }
        try {
            manager.removeGroup(channel, null);
        } catch (Exception ignored) {
            // Same.
        }
    }

    /** Waits for the broadcast that says the group is up. */
    private static final class ConnectionWatcher extends BroadcastReceiver {
        private final CountDownLatch formed = new CountDownLatch(1);
        private volatile WifiP2pInfo info;

        @Override
        public void onReceive(Context c, Intent intent) {
            WifiP2pInfo i = intent.getParcelableExtra(
                    WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo.class);
            // groupFormed alone is not enough: the broadcast also fires for a group we are
            // forming but have not joined, and connecting then fails with no route.
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
