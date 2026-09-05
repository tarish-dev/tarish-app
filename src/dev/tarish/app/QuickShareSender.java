package dev.tarish.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import dev.tarish.TarishPeer;
import dev.tarish.ITarishService;

/**
 * Opening a Bluetooth connection to a Quick Share peer, and handing it to the daemon.
 *
 * <p><b>Why the app does this at all.</b> Reaching a peer with no network means Bluetooth,
 * and framework Bluetooth is unreachable from a native service — the same reason BLE
 * scanning lives here. So the app connects; the daemon runs the protocol, which is the
 * half that is tested against captured traffic.
 *
 * <p><b>Why a socket pair rather than the Bluetooth socket itself.</b> Android does not
 * expose a {@link BluetoothSocket}'s file descriptor through public API. Reaching into
 * its hidden fields by reflection would work today and break on an OS update with no
 * warning. Instead the app creates a socket pair, gives one end to the daemon, and pumps
 * bytes between the Bluetooth streams and the other end. That costs a copy in each
 * direction, which is real but small next to a radio.
 *
 * <p>The pump is the only part of a Quick Share transfer that runs in this process, and
 * it understands nothing: it moves bytes and stops when either side closes.
 */
final class QuickShareSender {

    private static final String TAG = "TarishQS";

    /**
     * The RFCOMM service a stock Quick Share peer listens on.
     *
     * <p>A type-3 name-based UUID over {@code "NearbySharing"}, which is how Nearby
     * Connections maps a service name onto an RFCOMM service record. It never appears on
     * the air — SDP looks it up — so it cannot be captured, only derived.
     *
     * <p>Kept in step with {@code service::bluetooth_service_uuid()} in the daemon, which
     * derives rather than hardcodes it and pins the result in a test.
     */
    private static final UUID NEARBY_RFCOMM_UUID =
            UUID.fromString("a82efa21-ae5c-3dde-9bbc-f16da7b16c5a");

    /**
     * How long to wait for a Bluetooth connect before giving up.
     *
     * Long enough for a real connect on a busy radio, short enough that a stale address
     * reports back while the person is still looking at the screen.
     */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private QuickShareSender() {}

    /**
     * Connect to {@code peer} and start a transfer through the daemon.
     *
     * @return the transfer id, or 0 if it could not be started.
     */
    static long send(ITarishService service, TarishPeer peer,
                     ParcelFileDescriptor[] files, String[] names) {
        boolean reachable = (peer.psm > 0 && peer.bleAddress != null && !peer.bleAddress.isEmpty())
                || (peer.bluetoothMac != null && !peer.bluetoothMac.isEmpty());
        if (!reachable) {
            // Discoverable but not reachable: the peer advertised no address. Saying so
            // is better than a connection attempt that cannot succeed.
            Log.w(TAG, "peer " + peer.id + " published no Bluetooth address");
            return 0;
        }

        // WHICH SOCKET THE PEER ASKED FOR.
        //
        // A peer that published an L2CAP PSM refuses RFCOMM -- it accepts the connection
        // and closes it inside 200 ms without sending a frame, which reads exactly like
        // a peer that is asleep. A peer that published none accepts RFCOMM and completes
        // whole transfers. This is not a preference to tune: it is in the advertisement.
        boolean l2cap = usesL2cap(peer);
        BluetoothSocket socket = open(peer);
        if (socket == null && l2cap) {
            // ONE RETRY, ON A FRESHLY RESOLVED PEER.
            //
            // A stock peer rotates its BLE address and its PSM together, every
            // advertisement set. The row we were handed can already be pointing at an
            // address that no longer exists by the time someone taps send, and the
            // connect then blocks until the watchdog kills it -- which is what "stuck at
            // starting" was.
            //
            // Re-resolving costs one binder call and uses the Bluetooth MAC to find the
            // peer again, because that is the one identifier a stock peer does NOT
            // rotate: its endpoint id, BLE address and PSM all change together.
            TarishPeer fresh = resolve(service, peer);
            if (fresh != null) {
                Log.i(TAG, "retrying " + peer.id + " on a freshly advertised address");
                peer = fresh;
                l2cap = usesL2cap(peer);
                socket = open(peer);
            }
        }
        if (socket == null) {
            return 0;
        }

        ParcelFileDescriptor[] pair;
        try {
            pair = ParcelFileDescriptor.createSocketPair();
        } catch (Exception e) {
            Log.w(TAG, "could not create a socket pair", e);
            closeQuietly(socket);
            return 0;
        }

        long id;
        try {
            // pair[0] goes to the daemon; we keep pair[1] and pump.
            id = l2cap
                    ? service.sendFilesOnL2capSocket(peer.id, pair[0], files, names)
                    : service.sendFilesOnSocket(peer.id, pair[0], files, names);
        } catch (Exception e) {
            Log.w(TAG, "daemon refused the transfer", e);
            closeQuietly(socket);
            closeQuietly(pair[0]);
            closeQuietly(pair[1]);
            return 0;
        } finally {
            // The daemon duplicated it; our copy is dead weight and would hold the pipe
            // open after the daemon closed its end, so the pump would never see EOF.
            closeQuietly(pair[0]);
        }

        if (id == 0) {
            closeQuietly(socket);
            closeQuietly(pair[1]);
            return 0;
        }
        pump(socket, pair[1]);
        return id;
    }

    /** Whether this peer asked to be reached over L2CAP rather than RFCOMM. */
    private static boolean usesL2cap(TarishPeer peer) {
        return peer.psm > 0 && peer.bleAddress != null && !peer.bleAddress.isEmpty();
    }

    /** Open whichever socket the peer's advertisement asked for. */
    private static BluetoothSocket open(TarishPeer peer) {
        return usesL2cap(peer)
                ? connectL2cap(peer.bleAddress, peer.psm)
                : connect(peer.bluetoothMac);
    }

    /**
     * Find the peer again in the daemon's current list, by the identifier it does not
     * rotate.
     *
     * Returns null when nothing matches -- the peer may simply not have advertised since
     * -- in which case the caller keeps the failure it already has rather than inventing
     * a second one.
     */
    private static TarishPeer resolve(ITarishService service, TarishPeer stale) {
        if (stale.bluetoothMac == null || stale.bluetoothMac.isEmpty()) {
            return null;
        }
        TarishPeer[] peers;
        try {
            peers = service.getPeers();
        } catch (Exception e) {
            Log.w(TAG, "could not re-resolve the peer", e);
            return null;
        }
        if (peers == null) {
            return null;
        }
        for (TarishPeer p : peers) {
            if (stale.bluetoothMac.equals(p.bluetoothMac) && usesL2cap(p)) {
                // Only worth taking if it actually differs; otherwise we would retry the
                // same dead address and wait out the watchdog twice.
                boolean moved = !stale.bleAddress.equals(p.bleAddress) || stale.psm != p.psm;
                return moved ? p : null;
            }
        }
        return null;
    }

    /**
     * Open an L2CAP connection-oriented channel to a peer that published a PSM.
     *
     * **To the BLE address, not the Bluetooth MAC.** The channel rides the LE link, and
     * those are different addresses on the same device. The LE one is usually a
     * resolvable private address that ROTATES -- we have watched one phone use three in
     * as many minutes -- so it is only valid for as long as the advertisement that
     * carried it, which is why it comes from the peer row rather than from anything
     * cached here.
     */
    private static BluetoothSocket connectL2cap(String address, int psm) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is off");
            return null;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            // INSECURE for the same reason as RFCOMM: Quick Share peers are not paired,
            // and asking for a secure channel puts a pairing dialog in front of someone
            // who only wanted to receive a file.
            BluetoothSocket socket = device.createInsecureL2capChannel(psm);
            connectWithDeadline(socket);
            Log.i(TAG, "L2CAP connected to " + address + " psm=" + psm);
            return socket;
        } catch (Exception e) {
            // A rotated address is the likely cause: the PSM was advertised from an
            // address that no longer exists. Log both so that is visible rather than
            // looking like the peer refusing us.
            Log.w(TAG, "L2CAP connect to " + address + " psm=" + psm
                    + " failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Connect, but give up after {@link #CONNECT_TIMEOUT_MS}.
     *
     * {@link BluetoothSocket#connect()} takes no timeout and, against an address that no
     * longer exists, blocks far longer than anyone will wait -- the UI just sits at
     * "starting" with no way to tell whether it is working. Closing the socket from
     * another thread is the only thing that unblocks the syscall.
     *
     * A rotated address is the common case here, not an exotic one: a stock peer changes
     * its advertised address every rotation, so a row that is thirty seconds old can
     * already be pointing at nothing.
     */
    private static void connectWithDeadline(BluetoothSocket socket) throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(CONNECT_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            if (!done.get()) {
                Log.w(TAG, "connect exceeded " + CONNECT_TIMEOUT_MS + "ms; closing to unblock");
                closeQuietly(socket);
            }
        }, "tarish-qs-connect-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            socket.connect();
        } finally {
            done.set(true);
            watchdog.interrupt();
        }
    }

    /** Open an RFCOMM socket to a peer. */
    private static BluetoothSocket connect(String mac) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is off");
            return null;
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(mac);
            // Cancel discovery first. Android is explicit that an in-flight discovery
            // slows RFCOMM connect dramatically, and we cannot know whether something
            // else started one. cancelDiscovery is a no-op when nothing is running.
            try {
                adapter.cancelDiscovery();
            } catch (Exception ignored) {
                // Not fatal; the connect below is what matters.
            }
            // INSECURE: secure RFCOMM requires pairing, and Quick Share peers are not
            // paired. Asking for a secure socket puts a pairing dialog in front of
            // someone who only wanted to receive a file.
            BluetoothSocket socket =
                    device.createInsecureRfcommSocketToServiceRecord(NEARBY_RFCOMM_UUID);
            connectWithDeadline(socket);
            Log.i(TAG, "RFCOMM connected to " + mac);
            return socket;
        } catch (Exception e) {
            // The common case is the peer not listening on this service, which SDP
            // reports as a plain failure. Log the address so it is clear WHICH peer
            // refused rather than just that something did.
            Log.w(TAG, "RFCOMM connect to " + mac + " failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Move bytes between the Bluetooth socket and the daemon's socket, in both
     * directions, until either side closes.
     */
    private static void pump(BluetoothSocket socket, ParcelFileDescriptor local) {
        // ONE descriptor, TWO plain streams -- not two AutoClose streams.
        //
        // AutoCloseInputStream and AutoCloseOutputStream built from the same
        // ParcelFileDescriptor share its fd, so whichever thread finished first closed it
        // under the other. That surfaced as "read interrupted by close() on another
        // thread" in the middle of a handshake, and made the whole transfer look flaky:
        // sometimes it died instantly, sometimes after the encrypted channel was up.
        //
        // Plain streams over the descriptor do not close it. The pair is closed once,
        // below, when both directions have finished.
        final java.io.FileInputStream fromDaemon =
                new java.io.FileInputStream(local.getFileDescriptor());
        final java.io.FileOutputStream toDaemon =
                new java.io.FileOutputStream(local.getFileDescriptor());

        // Closed only when BOTH directions are done, so neither can pull the socket out
        // from under the other.
        final java.util.concurrent.atomic.AtomicInteger running =
                new java.util.concurrent.atomic.AtomicInteger(2);
        final Runnable finished = () -> {
            if (running.decrementAndGet() == 0) {
                closeQuietly(socket);
                closeQuietly(local);
            }
        };

        new Thread(() -> {
            try {
                copy(socket.getInputStream(), toDaemon);
            } catch (Exception e) {
                Log.d(TAG, "peer -> daemon ended: " + e.getMessage());
            } finally {
                // HALF-CLOSE, so the daemon learns the peer is gone.
                //
                // Closing the pair outright here is what an earlier version did, and it
                // pulled the descriptor out from under the other pump thread in the
                // middle of a handshake. Waiting for BOTH threads fixed that and
                // introduced the opposite bug: when the peer hangs up, this thread
                // finishes while the other is still blocked reading from the daemon, so
                // the pair stays open and the daemon's read NEVER RETURNS. A send to a
                // peer that rejects us then hangs for the life of the process -- no
                // error, no failure callback, one leaked thread per attempt.
                //
                // That is exactly how an Android peer closing the RFCOMM connection
                // 209 ms after connecting presented: a transfer that logged "sending 1
                // file(s)" and then nothing at all, for four minutes, until the daemon
                // was restarted.
                //
                // shutdown(SHUT_WR) says "no more from me" without touching the
                // descriptor the other thread is reading. The daemon sees EOF, reports
                // the failure, closes its end, and the other thread unwinds on its own.
                shutdownQuietly(local, OsConstants.SHUT_WR);
                finished.run();
            }
        }, "tarish-qs-in").start();

        new Thread(() -> {
            try {
                copy(fromDaemon, socket.getOutputStream());
            } catch (Exception e) {
                Log.d(TAG, "daemon -> peer ended: " + e.getMessage());
            } finally {
                // The daemon has stopped talking. An RFCOMM socket cannot be half-closed
                // -- BluetoothSocket exposes no shutdown -- so the peer only learns this
                // when the pair is closed below. Dropping our read end is still worth
                // doing: it unblocks this side if the daemon vanished without closing.
                shutdownQuietly(local, OsConstants.SHUT_RD);
                finished.run();
            }
        }, "tarish-qs-out").start();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            // Flush every chunk. The protocol above this is request/response in places --
            // a handshake message sitting in a buffer waiting for more data is a transfer
            // that hangs with both sides healthy.
            out.flush();
        }
    }

    /**
     * Half-close one direction of the socket pair, best effort.
     *
     * <p>Distinct from closing it: the other pump thread still holds the same descriptor,
     * and closing it under that thread is the bug this replaced.
     */
    private static void shutdownQuietly(ParcelFileDescriptor pfd, int how) {
        try {
            Os.shutdown(pfd.getFileDescriptor(), how);
        } catch (Exception ignored) {
            // Already closed, or already shut down in this direction. Nothing to do.
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // Closing is best-effort; there is nothing useful to do if it fails.
        }
    }
}
