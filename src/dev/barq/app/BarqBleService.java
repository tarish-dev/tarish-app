package dev.barq.app;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
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
 * <p><b>Why this is a service and not a screen.</b> Being discoverable must not require
 * anyone to open an app. The daemons ({@code barqd}, {@code barqsharingd}) already run
 * from boot and hold the AWDL session; this completes the picture by supplying the BLE
 * trigger that makes a peer start asking over mDNS in the first place. The user-facing
 * app is a separate concern and can be absent entirely without stopping discovery.
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

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "advertising AirDrop beacon, mode="
                    + settingsInEffect.getMode() + " txPower=" + settingsInEffect.getTxPowerLevel());
        }

        @Override
        public void onStartFailure(int errorCode) {
            // Worth naming: ADVERTISE_FAILED_FEATURE_UNSUPPORTED here means the radio
            // cannot advertise at all, which is a hardware fact and not something a
            // retry will fix. The others usually are transient.
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
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            // Not fatal and not worth crashing over: Bluetooth can be turned on later,
            // and the receiver restarts us. Say so plainly rather than failing silently.
            Log.w(TAG, "Bluetooth is off — no beacon until it is enabled");
            return;
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
        // Sticky: if the process is killed for memory, discovery should come back
        // without anyone opening anything.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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
