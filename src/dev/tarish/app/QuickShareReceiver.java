package dev.tarish.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.ParcelFileDescriptor;
import android.os.ParcelUuid;
import android.util.Log;

import dev.tarish.ITarishService;

import java.util.UUID;

/**
 * Makes this device findable and connectable by a Quick Share sender with NO shared network.
 *
 * The mirror of {@link QuickShareSender}, and the same division of labour: the app owns the
 * radio, the daemon owns the protocol. Here that means advertising over BLE so a sender lists
 * us, accepting the Bluetooth connection it then opens, and handing the socket to
 * tarishsharingd, which runs the whole exchange on it.
 *
 * <p>ON THE SAME NETWORK NONE OF THIS IS USED. A peer on our subnet finds us by mDNS and
 * connects to the port the daemon is already listening on, which is both simpler and about
 * a hundred times faster. This exists for the case where there is no network at all.
 *
 * <h2>Why the daemon builds the advertisement</h2>
 *
 * The 0xFEF3 layout lives in libtarish_protocol with test vectors captured from a real Pixel
 * and a real Windows machine. Encoding it again in Java would be a second implementation of
 * a format we already got right once, free to drift from the decoder that reads other
 * devices' advertisements. So {@code quickShareAdvertisement()} returns the bytes and this
 * only puts them on the air.
 *
 * <h2>RFCOMM, and why no PSM is advertised</h2>
 *
 * We listen on RFCOMM and publish no L2CAP PSM, and the two facts have to agree. That field
 * decides which socket a peer opens and it is not a preference: a peer that sees a PSM opens
 * an L2CAP channel and REFUSES RFCOMM — accepted and closed inside 200 ms with no frame
 * either way, which reads exactly like a device that is asleep. Measured from the sending
 * side, against a Pixel and a Windows machine.
 */
final class QuickShareReceiver {
    private static final String TAG = "TarishQsReceiver";

    /**
     * The service record a Quick Share sender looks for.
     *
     * A type-3 name-based UUID over "NearbySharing" — the same constant QuickShareSender
     * dials outbound, kept in both places rather than shared because they are opposite ends
     * of the same agreement and a mismatch should be visible at each.
     */
    private static final UUID NEARBY_RFCOMM_UUID =
            UUID.fromString("a82efa21-ae5c-3dde-9bbc-f16da7b16c5a");

    /** Nearby Connections' endpoint advertisement, distinct from the FastInitiation beacon. */
    private static final ParcelUuid NEARBY_SERVICE =
            ParcelUuid.fromString("0000fef3-0000-1000-8000-00805f9b34fb");

    private BluetoothLeAdvertiser advertiser;
    private AdvertisingSetCallback setCallback;
    private BluetoothServerSocket server;
    private Thread acceptor;
    private volatile boolean running;

    /**
     * Start advertising and listening. Safe to call when already started.
     *
     * Returns false when we could not become reachable — no Bluetooth, no address, or the
     * daemon declined to describe us. Saying so matters: advertising without a working
     * listener puts a device in someone's list that cannot be connected to, which is worse
     * than not appearing at all.
     */
    boolean start(BluetoothAdapter adapter, ITarishService service) {
        if (running) {
            return true;
        }
        if (adapter == null || !adapter.isEnabled() || service == null) {
            Log.w(TAG, "Bluetooth is off or the daemon is absent; not advertising");
            return false;
        }

        // THE ADDRESS GOES INSIDE THE ADVERTISEMENT. Without it a sender can see us and has
        // nothing to dial. getAddress() is redacted to 02:00:00:00:00:00 for ordinary apps;
        // this one holds BLUETOOTH_PRIVILEGED, which is what makes it the real one.
        String mac = adapter.getAddress();
        final byte[] advert;
        try {
            advert = service.quickShareAdvertisement(mac);
        } catch (Exception e) {
            Log.w(TAG, "the daemon could not describe us", e);
            return false;
        }
        if (advert == null || advert.length == 0) {
            Log.w(TAG, "no advertisement to publish (address was " + mac + ")");
            return false;
        }

        // THE LISTENER FIRST, THEN THE ADVERTISEMENT. In that order a sender can never see
        // us before there is something to accept its connection; the other way round leaves
        // a window where we are listed and refuse to connect, which a peer remembers.
        try {
            server = adapter.listenUsingInsecureRfcommWithServiceRecord(
                    "NearbySharing", NEARBY_RFCOMM_UUID);
        } catch (Exception e) {
            Log.w(TAG, "could not listen on RFCOMM", e);
            return false;
        }

        running = true;
        acceptor = new Thread(() -> accept(service), "tarish-qs-accept");
        acceptor.start();

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.w(TAG, "no LE advertiser; listening but undiscoverable");
            return false;
        }
        AdvertiseData data = new AdvertiseData.Builder()
                // No name field: the endpoint info inside the service data already carries
                // the name, and repeating it only costs bytes.
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(NEARBY_SERVICE, advert)
                .build();

        // EXTENDED ADVERTISING, NOT THE LEGACY 31 BYTES.
        //
        // A Quick Share endpoint advertisement does not fit in a legacy advertisement and is
        // not meant to: the one captured from a real Pixel is 52 bytes, the same size as
        // ours. Legacy caps the whole payload at 31, so startAdvertising() refuses with
        // ADVERTISE_FAILED_DATA_TOO_LARGE (error 1) and the device is simply never seen.
        //
        // BLE 5 extended advertising carries up to 255 bytes, which is how stock does it.
        // The size was never the problem; the mode was.
        if (!adapter.isLeExtendedAdvertisingSupported()) {
            // Nothing to fall back TO: a legacy advertisement cannot hold this, so saying so
            // is more use than an error 1 the caller has to decode.
            Log.w(TAG, "this radio has no extended advertising; a Quick Share endpoint "
                    + "advertisement (" + advert.length + " bytes) cannot fit in 31");
            return false;
        }
        AdvertisingSetParameters params = new AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                // Connectable but NOT scannable: an extended advertisement may be one or the
                // other, never both, and connectable is the one that matters -- a sender
                // opens a GATT connection to some peers before falling back to Classic.
                .setConnectable(true)
                .setScannable(false)
                .build();
        setCallback = new AdvertisingSetCallback() {
            @Override
            public void onAdvertisingSetStarted(AdvertisingSet set, int txPower, int status) {
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    Log.i(TAG, "advertising as a Quick Share endpoint ("
                            + advert.length + " bytes, txPower=" + txPower + ")");
                } else {
                    Log.w(TAG, "could not advertise, status=" + status);
                }
            }
        };
        advertiser.startAdvertisingSet(params, data, null, null, null, setCallback);
        return true;
    }

    /** Accept connections until stopped. One transfer at a time, as the daemon expects. */
    private void accept(ITarishService service) {
        while (running) {
            BluetoothSocket socket;
            try {
                socket = server.accept();
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "RFCOMM accept ended", e);
                }
                return;
            }
            Log.i(TAG, "a sender connected over RFCOMM");
            ParcelFileDescriptor[] pair = null;
            try {
                pair = ParcelFileDescriptor.createSocketPair();
                long id = service.receiveOnSocket(pair[0]);
                // The daemon duplicated its end, so ours is surplus. Closing it here is what
                // lets the daemon see EOF when the peer goes away.
                pair[0].close();
                if (id == 0) {
                    Log.w(TAG, "the daemon would not take the connection");
                    pair[1].close();
                    socket.close();
                    continue;
                }
                // PUMP RETURNS IMMEDIATELY. It starts a thread per direction and hands
                // ownership of both the Bluetooth socket and our end of the pair to them;
                // they close everything once both directions have finished.
                //
                // So there is NOTHING to close here. Closing in a finally block after this
                // call tore the connection down five milliseconds after accept, before the
                // sender had written a byte, and the daemon reported "peer closed the
                // connection" -- blaming the peer for a hang-up that was ours. The send path
                // gets this right by accident: it calls pump last and simply returns.
                QuickShareSender.pump(socket, pair[1]);
            } catch (Exception e) {
                // Only on the way IN. Once the pump has the descriptors, failures are its
                // to clean up.
                Log.w(TAG, "an inbound transfer failed", e);
                closeQuietly(pair == null ? null : pair[1]);
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // Best effort; we are already handling a failure.
                }
            }
        }
    }

    /** Stop advertising and listening. Safe to call when not started. */
    void stop() {
        running = false;
        if (advertiser != null && setCallback != null) {
            try {
                advertiser.stopAdvertisingSet(setCallback);
            } catch (Exception ignored) {
                // The adapter may already be off, which stops it for us.
            }
        }
        advertiser = null;
        setCallback = null;
        // Closing the server socket is what breaks accept() out of its block.
        if (server != null) {
            try {
                server.close();
            } catch (Exception ignored) {
                // Nothing to do; the thread exits either way.
            }
            server = null;
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return;
        }
        try {
            pfd.close();
        } catch (Exception ignored) {
            // Best effort on a teardown path.
        }
    }
}
