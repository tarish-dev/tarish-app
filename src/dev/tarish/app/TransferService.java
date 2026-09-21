package dev.tarish.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

import dev.tarish.TarishPeer;
import dev.tarish.ITarishCallback;
import dev.tarish.ITarishService;
import dev.tarish.TarishGroup;
import dev.tarish.TarishUpgrade;

/**
 * Keeps a transfer alive, and visible, once the person has stopped looking at the app.
 *
 * <p><b>Why this exists at all.</b> A Quick Share send runs partly in this process: the app
 * owns the Bluetooth socket and pumps bytes between it and the daemon, because framework
 * Bluetooth is unreachable from a native service. Threads survive an activity going away,
 * but the PROCESS does not — Android is free to kill a backgrounded app with nothing
 * holding it up, and killing it mid-transfer takes the socket with it. A foreground
 * service is the thing that says "not this one, not yet".
 *
 * <p>The notification is the other half of the same bargain: a process kept alive in the
 * background has to be accountable for it, and a progress bar is more honest than a
 * silent one anyway.
 *
 * <p><b>What this deliberately does NOT keep running.</b> Discovery, and being
 * discoverable. Those stay tied to the foreground, which is a privacy decision and not an
 * oversight: a device that keeps advertising itself after you have put the phone in your
 * pocket is announcing itself to a room you are no longer looking at. A transfer you
 * already started is a thing you asked for; discovery is a thing you were doing.
 *
 * <p>It registers its OWN callback with the daemon rather than being fed by the activity,
 * because the activity may be gone — which is the entire case this is for. The daemon
 * keeps a list of callbacks, so both can be registered at once and each sees every event.
 */
public final class TransferService extends Service {

    private static final String TAG = "TarishTransfer";
    private static final String SERVICE_NAME = "dev.tarish.ITarishService/default";

    private static final String CHANNEL = "transfers";
    /** The one ongoing-transfer notification. Reused, so progress replaces rather than stacks. */
    private static final int ONGOING_ID = 1;
    /** Outcomes get their own id so a result does not overwrite a transfer still running. */
    private static final int OUTCOME_ID = 2;

    static final String EXTRA_TRANSFER = "transfer";
    static final String EXTRA_LABEL = "label";
    static final String EXTRA_SENDING = "sending";

    // Mirrors ITarishCallback.onTransferFinished.
    private static final int STATUS_OK = 0;
    private static final int STATUS_FAILED = -1;
    private static final int STATUS_DECLINED = -2;

    private ITarishService service;
    private NotificationManager notifications;

    private long transfer;
    private String label = "";
    private boolean sending = true;

    // Progress-notification throttle: see onTransferProgress.
    private long lastNotifyMs = 0;
    private int lastPercent = -1;

    /**
     * Start watching a transfer, or update the one being watched.
     *
     * <p>Safe to call repeatedly for the same transfer; the service is a singleton and a
     * second start just refreshes what it is showing.
     */
    static void watch(Context context, long transfer, String label, boolean sending) {
        if (transfer == 0) {
            return;
        }
        Intent i = new Intent(context, TransferService.class)
                .putExtra(EXTRA_TRANSFER, transfer)
                .putExtra(EXTRA_LABEL, label == null ? "" : label)
                .putExtra(EXTRA_SENDING, sending);
        try {
            context.startForegroundService(i);
        } catch (Exception e) {
            // Starting a foreground service is refused in some states (during a device
            // policy lockdown, for one). The transfer still runs; it just loses its
            // protection from being killed, which is worth a line rather than a crash.
            Log.w(TAG, "could not start the transfer service", e);
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, TransferService.class));
        } catch (Exception ignored) {
            // Stopping something already stopped is not a failure.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress of files being sent or received.");
        // LOW, and no sound: this appears for something the person just started and is
        // already aware of. A transfer that chimes on every update is a transfer people
        // turn off.
        channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            transfer = intent.getLongExtra(EXTRA_TRANSFER, 0);
            label = intent.getStringExtra(EXTRA_LABEL);
            sending = intent.getBooleanExtra(EXTRA_SENDING, true);
        }
        if (label == null) {
            label = "";
        }

        // Immediately, before anything else can fail: the system gives a service a few
        // seconds to post its notification and kills it if it does not.
        startForeground(ONGOING_ID, ongoing(0, 0), foregroundType());

        if (transfer == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        bind();

        // NOT sticky. A transfer cannot survive its own process being killed -- the
        // socket goes with it -- so being restarted later with no transfer to watch would
        // only put a stale notification on screen.
        return START_NOT_STICKY;
    }

    private int foregroundType() {
        // The work is a Bluetooth link to another device, which is what this type is for.
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
    }

    private void bind() {
        // A LIVE BINDER, NOT MERELY A NON-NULL ONE.
        //
        // The daemon restarts -- every deploy does it, and it can crash -- and this held a
        // proxy to the dead one. `service != null` returned early, registerCallback was
        // never redone, and the service went silently DEAF: no onUpgradeNeeded, so a
        // Wi-Fi Direct offer was never joined, and no onTransferFinished, so the previous
        // transfer's group was never released. Nothing logged, because nothing failed --
        // the callbacks simply stopped arriving.
        //
        // Normally invisible because a finished transfer calls stopSelf() and the next one
        // binds fresh. It bites when a transfer does NOT finish: the instance survives, and
        // every transfer after it is watched through a dead proxy.
        if (service != null && !service.asBinder().pingBinder()) {
            Log.w(TAG, "the daemon restarted under us; rebinding");
            service = null;
        }
        if (service != null) {
            return;
        }
        try {
            service = Daemon.get();
            if (service == null) {
                Log.w(TAG, "the daemon is not published; no progress to show");
                return;
            }
            // Held here, unlike everywhere else, because the CALLBACK registration is what
            // matters and re-registering per use would be wrong. The liveness check above is
            // what keeps it honest.
            service.registerCallback(callback);
        } catch (Exception e) {
            Log.w(TAG, "could not follow the transfer", e);
            service = null;
        }
    }

    /** Hosts a Wi-Fi Direct group when a sender asks for one. */
    private final WifiDirectHost host = new WifiDirectHost();

    private final ITarishCallback.Stub callback = new ITarishCallback.Stub() {
        @Override public void onPeerFound(TarishPeer peer) {}
        @Override public void onPeerLost(String peerId) {}
        @Override public void onTransferOffered(long id, String peerId, String[] names,
                long totalBytes, int protocol) {}
        @Override public void onTransferPinRequired(long id) {}
        // The PIN is shown by MainActivity (a background service has no screen); no-op here.
        @Override public void onTransferPinDisplay(long id, String pin) {}

        /**
         * THE SERVICE OWNS THIS, not the activity.
         *
         * Joining a Wi-Fi Direct group takes seconds and the transfer is parked for all of
         * them, which is exactly when someone puts the phone down and the activity goes
         * away. This component is running precisely because the transfer must survive
         * that, so the join belongs here; MainActivity stubs it out so the two do not both
         * try to join the same group.
         *
         * Off the binder thread: the whole point is that it blocks.
         */
        @Override
        public void onUpgradeNeeded(long id, TarishUpgrade upgrade) {
            if (id != transfer) {
                return;
            }
            ITarishService svc = service;
            if (svc == null) {
                return;
            }
            new Thread(() -> WifiDirectJoiner.join(TransferService.this, svc, id, upgrade),
                    "tarish-wifi-direct").start();
        }

        @Override
        public void onTransferProgress(long id, long done, long total) {
            if (id != transfer) {
                return;
            }
            // THROTTLE. Every notify() posts a Notification (which carries a Binder token) to
            // system_server, and a slow transport delivers thousands of progress ticks -- the
            // receiver was killed mid-transfer for "too many Binders sent to uid 1000". The
            // daemon now throttles too, but this is the last line of defence: at most one post
            // per ~400 ms or per 1%, and always the final 100%.
            long now = android.os.SystemClock.uptimeMillis();
            int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
            boolean done100 = total > 0 && done >= total;
            if (!done100 && percent == lastPercent && now - lastNotifyMs < 400L) {
                return;
            }
            lastNotifyMs = now;
            lastPercent = percent;
            notifications.notify(ONGOING_ID, ongoing(done, total));
        }

        /**
         * A sender wants a faster network. Stand one up and hand it back.
         *
         * THE RECEIVING MIRROR of onUpgradeNeeded, and owned here for the same reason:
         * forming a group takes seconds, and this component is the half that survives the
         * activity going away.
         */
        @Override
        public void onGroupNeeded(long id) {
            if (id != transfer) {
                return;
            }
            ITarishService svc = service;
            if (svc == null) {
                return;
            }
            new Thread(() -> {
                TarishGroup group = host.create(TransferService.this);
                try {
                    svc.provideWifiDirectGroup(id, group);
                } catch (Exception e) {
                    Log.w(TAG, "could not answer the group request", e);
                    // Release it: nothing is coming to use a group the daemon never heard
                    // about, and it would hold the radio until the process dies.
                    host.remove();
                }
            }, "tarish-wifi-direct-host").start();
        }

        @Override
        public void onTransferFinished(long id, int status) {
            if (id != transfer) {
                return;
            }
            notifications.notify(OUTCOME_ID, outcome(status));
            // Give the radio back. The group was kept up for the whole transfer on purpose,
            // so this is the only place that can end it -- and without it the device stays
            // associated to a one-off network after the files have gone.
            WifiDirectJoiner.release(id);
            // And the group we may have hosted for an inbound transfer. Same reasoning as
            // the joiner's: the radio is held until someone gives it back.
            host.remove();
            transfer = 0;
            // Drops the ongoing notification with it, which is what should happen: the
            // outcome is a separate, dismissible one.
            stopSelf();
        }
    };

    private Notification ongoing(long done, long total) {
        String title = sending ? "Sending" : "Receiving";
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setContentTitle(label.isEmpty() ? title : title + " " + label)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp());

        if (total > 0) {
            int percent = (int) Math.min(100, done * 100 / total);
            b.setProgress(100, percent, false);
            b.setContentText(Ui.size(done) + " of " + Ui.size(total));
        } else {
            // No total yet, or a stream whose size the peer never declared. An
            // indeterminate bar is honest; a bar stuck at zero is not.
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private Notification outcome(int status) {
        String title;
        String text;
        if (status == STATUS_OK) {
            title = sending ? "Sent" : "Received";
            text = label.isEmpty() ? "" : label;
        } else if (status == STATUS_DECLINED) {
            title = "Declined";
            text = "The other device turned it down.";
        } else {
            title = sending ? "Could not send" : "Could not receive";
            text = "The transfer did not complete.";
        }
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(status == STATUS_FAILED
                        ? android.R.drawable.stat_notify_error
                        : android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onDestroy() {
        if (service != null) {
            try {
                service.unregisterCallback(callback);
            } catch (Exception ignored) {
                // The daemon may already be gone; nothing to clean up if so.
            }
            service = null;
        }
        // Return the Wi-Fi Direct channel's binder to system_server rather than leaking it
        // for the life of the process.
        host.dispose();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Started, not bound. Binding would tie its lifetime to a client, which is the
        // opposite of the point.
        return null;
    }
}
