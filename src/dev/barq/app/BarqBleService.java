package dev.barq.app;

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
 * <p><b>Why BLE lives here rather than in the daemon.</b> {@code barqd} is native and
 * holds {@code NET_ADMIN}/{@code NET_RAW} for the Wi-Fi side. BLE advertising goes
 * through the framework's {@link BluetoothLeAdvertiser}, which is Java API surface and
 * needs {@code BLUETOOTH_ADVERTISE}. Google draws the same line: {@code libmosey} is
 * pure AWDL and links no Bluetooth library at all, while their app carries
 * {@code BLUETOOTH_PRIVILEGED}.
 */
public final class BarqBleService extends Service {

    private static final String TAG = "BarqBle";

    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;

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
     * a system event, the user -- leaves Barq invisible with no error.
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
    private void acquireAndStart() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is off — no beacon until it is enabled");
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

    private void startScanning() {
        if (scanner == null) {
            Log.w(TAG, "no LE scanner available");
            return;
        }
        // Filter in the controller rather than in Java: an unfiltered scan wakes this
        // process for every beacon in range, which on a phone is constant.
        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder()
                .setManufacturerData(AirDropBeacon.APPLE_COMPANY_ID,
                        new byte[] { AirDropBeacon.TYPE_AIRDROP },
                        new byte[] { (byte) 0xFF })
                .build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

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
