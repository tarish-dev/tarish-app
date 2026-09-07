package dev.tarish.app;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import dev.tarish.ITarishService;

import java.util.ArrayList;
import java.util.List;

/**
 * Advertises the AirDrop BLE beacon and watches for other peers advertising it.
 *
 * <p><b>Started and stopped by {@link MainActivity}, not by boot.</b> BLE advertising is
 * what makes an Apple device ask for us at all, so it is the visibility switch: while
 * this service runs the device can be found, and while it does not it cannot. An earlier
 * version started at boot and never stopped, which meant the device announced itself to
 * every scanner in range forever.
 *
 * <p><b>Why BLE lives here rather than in the daemon.</b> {@code tarishd} is native and
 * holds {@code NET_ADMIN}/{@code NET_RAW} for the Wi-Fi side. BLE advertising goes
 * through the framework's {@link BluetoothLeAdvertiser}, which is Java API surface and
 * needs {@code BLUETOOTH_ADVERTISE}. Google draws the same line: {@code libmosey} is
 * pure AWDL and links no Bluetooth library at all, while their app carries
 * {@code BLUETOOTH_PRIVILEGED}.
 */
public final class TarishBleService extends Service {

    private static final String TAG = "TarishBle";

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;

    /**
     * Quick Share's assigned 16-bit service, in the 128-bit form a scan filter takes.
     */
    private static final android.os.ParcelUuid QUICK_SHARE_SERVICE =
            android.os.ParcelUuid.fromString("0000fe2c-0000-1000-8000-00805f9b34fb");

    /** Nearby Connections' service, where Quick Share advertises its endpoints. */
    private static final android.os.ParcelUuid NEARBY_SERVICE =
            android.os.ParcelUuid.fromString("0000fef3-0000-1000-8000-00805f9b34fb");

    /** kFastInitModelId — the magic that marks a pulse as Quick Share. */
    private static final byte[] QUICK_SHARE_MODEL_ID =
            new byte[] { (byte) 0xFC, (byte) 0x12, (byte) 0x8E };

    /** True between a successful startAdvertising and the matching stop. */
    private boolean advertising;

    /**
     * Re-arms the beacon when Bluetooth comes back.
     *
     * onCreate used to say "the receiver restarts us" while no receiver existed. The
     * consequence, measured: cycle the adapter and the beacon never returns. Bluetooth
     * reports enabled, the service record is still there, and an explicit startService
     * logs nothing -- because advertising was only ever started from onCreate, and the
     * advertiser handle held across a cycle is stale. Silent, and it breaks receiving
     * as well as sending, so anything ordinary that toggles Bluetooth -- airplane mode,
     * a system event, the user -- leaves Tarish invisible with no error.
     */
    private final BroadcastReceiver adapterState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(i.getAction())) {
                return;
            }
            int state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                Log.i(TAG, "Bluetooth came back — re-arming the beacon");
                acquireAndStart();
            } else if (state == BluetoothAdapter.STATE_OFF
                    || state == BluetoothAdapter.STATE_TURNING_OFF) {
                // The handles do not survive the adapter going down. Drop them rather
                // than calling into them later and getting silence.
                advertising = false;
                advertiser = null;
                scanner = null;
            }
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            advertising = true;
            Log.i(TAG, "advertising AirDrop beacon, mode="
                    + settingsInEffect.getMode() + " txPower=" + settingsInEffect.getTxPowerLevel());
        }

        @Override
        public void onStartFailure(int errorCode) {
            // Worth naming: ADVERTISE_FAILED_FEATURE_UNSUPPORTED here means the radio
            // cannot advertise at all, which is a hardware fact and not something a
            // retry will fix. The others usually are transient.
            advertising = false;
            Log.e(TAG, "advertising failed: " + describeAdvertiseError(errorCode));
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getScanRecord() == null) {
                return;
            }
            byte[] data = result.getScanRecord()
                    .getManufacturerSpecificData(AirDropBeacon.APPLE_COMPANY_ID);
            if (AirDropBeacon.isAirDrop(data)) {
                Log.i(TAG, "AirDrop beacon from " + result.getDevice().getAddress()
                        + " rssi=" + result.getRssi());
            }

            if (android.os.SystemProperties.getBoolean("persist.tarish.ble_debug", false)) {
                android.bluetooth.le.ScanRecord rec = result.getScanRecord();
                StringBuilder svc = new StringBuilder();
                if (rec.getServiceData() != null) {
                    for (android.os.ParcelUuid u : rec.getServiceData().keySet()) {
                        byte[] v = rec.getServiceData().get(u);
                        svc.append(' ').append(u.getUuid().toString().substring(4, 8))
                           .append('=');
                        // The WHOLE payload, not a prefix. These advertisements are
                        // ~20 bytes and the identifying fields are not all at the front:
                        // a Nearby Connections advertisement carries its service-id hash
                        // after the version byte, and the endpoint info after that.
                        // Logging three bytes told us something was there and nothing
                        // about what.
                        if (v == null) {
                            svc.append("null");
                        } else {
                            for (byte b : v) {
                                svc.append(String.format("%02x", b));
                            }
                        }
                    }
                }
                Log.i(TAG, "BLE " + result.getDevice().getAddress()
                        + " rssi=" + result.getRssi()
                        + " name=" + rec.getDeviceName()
                        + " svc:" + (svc.length() == 0 ? " none" : svc));
            }

            // Hand any Nearby advertisement to the daemon. It decides whether it is
            // Quick Share and whether it is a peer -- the decoder lives there, built
            // against captured traffic, and reimplementing it here would be a second
            // thing to get wrong.
            byte[] nearby = result.getScanRecord().getServiceData(NEARBY_SERVICE);
            if (nearby != null && nearby.length > 8) {
                reportBlePeer(result.getDevice().getAddress(), result.getRssi(), nearby);
            }

            // A Quick Share share-intent pulse. Logged in full, because these bytes are
            // the only way to check our own encoder against a real stock sender -- the
            // metadata byte and the secret_id_hash both fail silently when wrong, and a
            // capture from a device that is not ours is the only thing that settles it.
            byte[] qs = result.getScanRecord().getServiceData(QUICK_SHARE_SERVICE);
            if (qs != null && qs.length >= 14) {
                StringBuilder hex = new StringBuilder(qs.length * 2);
                for (byte b : qs) {
                    hex.append(String.format("%02x", b));
                }
                Log.i(TAG, "QuickShare pulse from " + result.getDevice().getAddress()
                        + " rssi=" + result.getRssi()
                        + " len=" + qs.length
                        + " metadata=0x" + String.format("%02x", qs[3])
                        + " txpower=0x" + String.format("%02x", qs[4])
                        + " data=" + hex);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "scan failed: " + errorCode);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Registered BEFORE the first attempt, so a cycle that happens while we are
        // starting is still seen.
        registerReceiver(adapterState,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            // Not fatal and not worth crashing over: Bluetooth can be turned on later,
            // and the receiver restarts us. Say so plainly rather than failing silently.
            Log.w(TAG, "Bluetooth is off — no beacon until it is enabled");
            return;
        }
        acquireAndStart();
    }

    /**
     * Take fresh handles from the adapter and start. Safe to call repeatedly.
     *
     * The handles are re-fetched every time on purpose: one held across an adapter
     * cycle is stale, and using it fails quietly rather than throwing.
     */
    /**
     * Bring the radio up, and NEVER throw doing it.
     *
     * Every caller is a lifecycle callback -- onCreate, onStartCommand, the adapter-state
     * broadcast -- and an exception escaping any of them kills the process. The permission
     * check above is the expected path; this catch is for the gap between checking and
     * calling, which a runtime revoke can land in, and for anything the framework decides
     * to throw that we did not anticipate. A dead beacon is a degraded app. A dead process
     * is no app.
     */
    private void acquireAndStart() {
        try {
            acquireAndStartOrThrow();
        } catch (SecurityException e) {
            Log.w(TAG, "radio permission refused at the call — no beacon", e);
        } catch (RuntimeException e) {
            Log.e(TAG, "could not bring the radio up", e);
        }
    }

    private void acquireAndStartOrThrow() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is off — no beacon until it is enabled");
            return;
        }
        String missing = missingRadioPermission();
        if (missing != null) {
            // EXACTLY as survivable as Bluetooth being off, and it used to kill the app.
            //
            // startAdvertising() throws SecurityException when the permission is not held,
            // and this runs from onCreate, so the throw escaped handleCreateService and
            // took down the process -- repeatedly, because the service restarts. The user
            // sees "Tarish keeps stopping" and no part of the app is reachable, including
            // the screen that would explain why.
            //
            // Tarish is a system app and these permissions are PRE-GRANTED -- by
            // system_ext/etc/default-permissions/default-permissions-dev.tarish.app.xml.
            // It should never prompt, and this is not a fallback for a missing prompt.
            //
            // It is a guard against the pre-grant not having run, which is a real state:
            // PackageManagerService applies default grants only when it decides the device
            // upgraded, and that decision is
            //     mIsUpgrade = !partitionsFingerprint.equals(ver.fingerprint)
            // so an image built with a STALE BUILD_NUMBER carries the fingerprint already
            // on the phone, is judged "not an upgrade", and its newly added system packages
            // get nothing granted. Seen on hardware; BUILD-NOTES 30 and 55.
            Log.w(TAG, "no " + missing + " — no beacon until it is granted");
            return;
        }
        if (advertising && advertiser != null) {
            // Starting twice fails with ALREADY_STARTED and leaves the first one
            // running, so the second call looks like a failure and changes nothing.
            advertiser.stopAdvertising(advertiseCallback);
            advertising = false;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        scanner = adapter.getBluetoothLeScanner();
        startAdvertising();
        startScanning();
        // Quick Share receiving rides the same lifecycle: it wants the radio for exactly
        // as long as the beacon does, and re-acquiring after an adapter cycle is the same
        // problem with the same answer.
        // ONLY IF IT IS NOT ALREADY UP. This runs twice at boot -- once from onCreate and
        // once from the adapter-state broadcast -- and restarting the listener each time
        // left a second RFCOMM service record for the same UUID, which a sender can resolve
        // to and then fail against. Nothing about a live listener needs replacing.
        if (!receiver.isRunning() && !receiver.start(adapter, service())) {
            Log.w(TAG, "not reachable over Bluetooth for Quick Share");
        }
    }

    /**
     * The first radio permission we need and do not hold, or null when all are present.
     *
     * Checked rather than caught so the log names the missing one: "permission denied" with
     * no name sends you looking through three candidates.
     */
    private String missingRadioPermission() {
        String[] needed = {
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        };
        for (String p : needed) {
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return p.substring(p.lastIndexOf('.') + 1);
            }
        }
        return null;
    }

    /** Off-network Quick Share receiving: the BLE endpoint advertisement and RFCOMM. */
    private final QuickShareReceiver receiver = new QuickShareReceiver();

    /**
     * The daemon, or null.
     *
     * Fetched rather than held: this runs after an adapter cycle, and a proxy taken before
     * a daemon restart is dead in a way that fails silently -- the same trap TransferService
     * hit, where callbacks simply stopped arriving.
     */
    private ITarishService service() {
        return Daemon.get();
    }

    private void startAdvertising() {
        if (advertiser == null) {
            Log.w(TAG, "no LE advertiser — radio does not support advertising");
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)          // 0 = advertise until told to stop
                .build();

        // No device name in the payload. The 31-byte advertisement has no room to
        // spare once Apple's manufacturer data is in it, and AirDrop carries the
        // display name later in the protocol rather than here.
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(AirDropBeacon.APPLE_COMPANY_ID, AirDropBeacon.everyone())
                .build();

        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    /** The daemon's service name; the same one MainActivity uses. */
    private static final String SERVICE_NAME = "dev.tarish.ITarishService/default";

    /**
     * Do not report the same peer more often than this.
     *
     * Peers advertise several times a second and there can be a dozen of them, so
     * forwarding every sighting would be hundreds of binder calls a minute to say
     * nothing new. The daemon expires a peer after 20s, so re-reporting every 5 keeps it
     * present with a wide margin.
     */
    private static final long REPORT_EVERY_MS = 5000;

    private final java.util.Map<String, Long> lastReported = new java.util.HashMap<>();
    private dev.tarish.ITarishService tarishService;

    /** Hand one Nearby advertisement to the daemon, which owns the decoder. */
    private void reportBlePeer(String address, int rssi, byte[] serviceData) {
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = lastReported.get(address);
        if (last != null && now - last < REPORT_EVERY_MS) {
            return;
        }
        try {
            if (tarishService == null) {
                android.os.IBinder b = android.os.ServiceManager.getService(SERVICE_NAME);
                tarishService = b == null ? null : dev.tarish.ITarishService.Stub.asInterface(b);
            }
            if (tarishService == null) {
                // Was a silent return, which made a missing daemon indistinguishable
                // from a peer that was never seen -- the peer list stayed empty and
                // nothing anywhere said why. Rate-limited by the same clock as the
                // reports so a dead daemon does not become its own flood.
                lastReported.put(address, now);
                Log.w(TAG, "cannot report peers: " + SERVICE_NAME + " is not published");
                return;
            }
            tarishService.reportBlePeer(address, rssi, serviceData);
            lastReported.put(address, now);
        } catch (Exception e) {
            // A dead proxy after a daemon restart. Drop it so the next sighting looks it
            // up again rather than failing forever against a binder that has gone.
            Log.d(TAG, "reportBlePeer failed, will rebind: " + e.getMessage());
            tarishService = null;
        }
    }

    private void startScanning() {
        if (scanner == null) {
            Log.w(TAG, "no LE scanner available");
            return;
        }
        // STOP FIRST. onStartCommand re-asserts on every start, and startScan against an
        // already-running scan fails with SCAN_FAILED_ALREADY_STARTED -- leaving the
        // PREVIOUS filter set in place. That is silent: scanning carries on, so nothing
        // looks broken, but a filter added since the first start never takes effect.
        // Found exactly that way, adding the Quick Share filter and seeing no pulses.
        try {
            scanner.stopScan(scanCallback);
        } catch (Exception e) {
            // Not started, or Bluetooth went away underneath us. Either way the start
            // below is what matters.
            Log.d(TAG, "stopScan before restart: " + e.getMessage());
        }
        // Filter in the controller rather than in Java: an unfiltered scan wakes this
        // process for every beacon in range, which on a phone is constant.
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setManufacturerData(AirDropBeacon.APPLE_COMPANY_ID,
                        new byte[] { AirDropBeacon.TYPE_AIRDROP },
                        new byte[] { (byte) 0xFF })
                .build());

        // Quick Share's FastInitiation pulse. Matched on the three-byte model id rather
        // than on the service UUID alone: the UUID appears in plenty of advertisements
        // that are not a share intent, and this scan runs whenever the app is open.
        filters.add(new ScanFilter.Builder()
                .setServiceData(QUICK_SHARE_SERVICE,
                        QUICK_SHARE_MODEL_ID,
                        new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF })
                .build());

        // Quick Share ENDPOINT advertisements, on Nearby Connections' service.
        //
        // setServiceDATA, not setServiceUuid. They sound interchangeable and are not:
        // setServiceUuid matches the Service UUID AD types (0x02..0x07), while Quick
        // Share advertises service DATA (0x16). ScanRecord.getServiceUuids() does not
        // parse service data, so a UUID filter never matches and the callback simply
        // never fires -- no error, no hint, an empty peer list. It looked exactly like a
        // peer that was not advertising, while an unfiltered debug scan saw it 1,638
        // times in 25 seconds.
        //
        // An empty pattern and mask match any service data for the UUID. Which of those
        // advertisements is actually Quick Share is the daemon's decision: it has the
        // decoder and its captured test vectors.
        filters.add(new ScanFilter.Builder()
                .setServiceData(NEARBY_SERVICE, new byte[0], new byte[0])
                .build());

        // SCAN EXTENDED ADVERTISEMENTS TOO, not just legacy.
        //
        // setLegacy defaults to TRUE, which reports only the old 31-byte advertising
        // PDUs and silently drops everything sent with BLE 5 extended advertising. A
        // device using it is then completely invisible -- no error, no callback, nothing
        // to notice -- while being plainly discoverable to any other scanner.
        //
        // Found because a Windows machine that a stock Android phone could see did not
        // appear here at all, while its idle Nearby chatter did. Idle beacons are legacy;
        // the endpoint advertisement is not.
        ScanSettings.Builder builder = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isLeExtendedAdvertisingSupported()) {
            builder.setLegacy(false)
                   .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED);
            Log.i(TAG, "scanning legacy + extended advertisements");
        } else {
            // Older controllers reject setLegacy(false) outright, so only ask where the
            // hardware says it can.
            Log.i(TAG, "controller is legacy-only; scanning legacy advertisements");
        }
        ScanSettings settings = builder.build();

        // DIAGNOSTIC MODE: setprop persist.tarish.ble_debug 1
        //
        // Scans unfiltered and logs every advertisement. Off by default because an
        // unfiltered scan wakes this process for every beacon in range, which on a phone
        // is constant and expensive.
        //
        // It exists because a filtered scan that finds nothing is AMBIGUOUS: the filter
        // could be wrong, the peer could be silent, or the scan could not be running at
        // all, and those look identical from the log. This tells them apart.
        if (android.os.SystemProperties.getBoolean("persist.tarish.ble_debug", false)) {
            Log.w(TAG, "BLE DEBUG: scanning unfiltered");
            scanner.startScan(new ArrayList<>(), settings, scanCallback);
            return;
        }
        scanner.startScan(filters, settings, scanCallback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Re-assert on every start, not just the first. An already-running service
        // otherwise ignores startService entirely, so there was no way to recover a
        // dead beacon short of killing the app.
        acquireAndStart();
        // NOT sticky. Visibility belongs to the user through MainActivity, so a service
        // that resurrected itself after being killed would advertise with nothing open
        // -- exactly the behaviour this design exists to prevent.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(adapterState);
        } catch (IllegalArgumentException e) {
            // Never registered, which happens if onCreate bailed early.
        }
        advertising = false;
        if (advertiser != null) {
            advertiser.stopAdvertising(advertiseCallback);
        }
        if (scanner != null) {
            scanner.stopScan(scanCallback);
        }
        // Releases the RFCOMM listener too. Left running it would hold the service record
        // after the app stopped being discoverable, so a sender could still connect to a
        // device that is no longer offering to receive.
        receiver.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;    // not a bound service; the daemon owns the IPC surface
    }

    private static String describeAdvertiseError(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "already started";
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "data too large for a 31-byte advertisement";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "radio cannot advertise";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "internal error";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "too many advertisers";
            default:
                return "code " + code;
        }
    }
}
