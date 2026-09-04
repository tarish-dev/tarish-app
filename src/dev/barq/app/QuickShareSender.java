package dev.barq.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import dev.barq.BarqPeer;
import dev.barq.IBarqService;

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

    private static final String TAG = "BarqQS";

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

    private QuickShareSender() {}

    /**
     * Connect to {@code peer} and start a transfer through the daemon.
     *
     * @return the transfer id, or 0 if it could not be started.
     */
    static long send(IBarqService service, BarqPeer peer,
                     ParcelFileDescriptor[] files, String[] names) {
        if (peer.bluetoothMac == null || peer.bluetoothMac.isEmpty()) {
            // Discoverable but not reachable: the peer advertised no address. Saying so
            // is better than a connection attempt that cannot succeed.
            Log.w(TAG, "peer " + peer.id + " published no Bluetooth address");
            return 0;
        }

        BluetoothSocket socket = connect(peer.bluetoothMac);
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
            id = service.sendFilesOnSocket(peer.id, pair[0], files, names);
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
            socket.connect();
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
        ParcelFileDescriptor.AutoCloseInputStream fromDaemon =
                new ParcelFileDescriptor.AutoCloseInputStream(local);
        ParcelFileDescriptor.AutoCloseOutputStream toDaemon =
                new ParcelFileDescriptor.AutoCloseOutputStream(local);

        new Thread(() -> {
            try {
                copy(socket.getInputStream(), toDaemon);
            } catch (Exception e) {
                Log.d(TAG, "peer -> daemon ended: " + e.getMessage());
            } finally {
                closeQuietly(socket);
            }
        }, "barq-qs-in").start();

        new Thread(() -> {
            try {
                copy(fromDaemon, socket.getOutputStream());
            } catch (Exception e) {
                Log.d(TAG, "daemon -> peer ended: " + e.getMessage());
            } finally {
                closeQuietly(socket);
            }
        }, "barq-qs-out").start();
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
