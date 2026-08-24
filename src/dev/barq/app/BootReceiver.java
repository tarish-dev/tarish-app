package dev.barq.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the BLE service at boot, so being discoverable never depends on someone
 * opening the app. This is the app-side counterpart to barq.rc starting the daemons.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startService(new Intent(context, BarqBleService.class));
        }
    }
}
