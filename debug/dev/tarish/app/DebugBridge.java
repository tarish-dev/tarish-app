package dev.tarish.app;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import dev.tarish.ITarishService;
import dev.tarish.TarishPeer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A shell control channel into the APP, for tests that the daemon cannot drive.
 *
 * <h2>Why this exists</h2>
 *
 * {@code tarishctl} reaches the daemon, and the daemon owns AirDrop and Quick Share over
 * the Wi-Fi LAN. It does <b>not</b> own the other Quick Share transports:
 * {@code sendFilesOnSocket} takes a Bluetooth socket and {@code provideWifiDirectGroup}
 * takes a P2P group, and both are framework API a native daemon cannot reach. Only the app
 * can create them.
 *
 * <p>The consequence was that Bluetooth and Wi-Fi Direct were reported as SKIP in every
 * end-to-end run — honestly, but permanently. Two whole transports had never been exercised
 * against real hardware on current code, which is the largest kind of blind spot a test
 * suite can have: the one it tells you about and you stop reading.
 *
 * <p>So the harness needs a way in, and this is the smallest one: a local socket that
 * accepts one line and performs the same call the share sheet would.
 *
 * <h2>Why it is safe</h2>
 *
 * It is gated <b>twice</b>, the same way {@code tarishctl} is, and for the same reason —
 * either guard alone is one edit away from being lost:
 *
 * <ul>
 *   <li><b>Build.</b> Android.bp excludes this file unless the build is debuggable, so a
 *       user build does not contain the class at all. {@code MainActivity} starts it
 *       reflectively so it still compiles when the class is absent.</li>
 *   <li><b>Runtime.</b> {@link Build#IS_DEBUGGABLE} is checked again here, so even a class
 *       that somehow shipped refuses to listen.</li>
 * </ul>
 *
 * <p>SELinux is the third: reaching this socket needs a rule that lives inside
 * {@code userdebug_or_eng()}.
 *
 * <p>It performs no action a person at the share sheet could not perform. It does not
 * bypass policy — the daemon still refuses a send that policy forbids — and it holds no
 * state of its own.
 */
final class DebugBridge {

    private static final String TAG = "TarishDebug";

    /**
     * Abstract-namespace socket name. Abstract rather than filesystem-backed so there is
     * no path to leave behind, no directory to label, and nothing to clean up if the app
     * dies mid-transfer.
     */
    private static final String SOCKET = "tarish-debug";

    private DebugBridge() {
    }

    /**
     * Start listening, once. Called reflectively from MainActivity so that the caller
     * compiles on a build where this class does not exist.
     */
    public static void start() {
        if (!Build.IS_DEBUGGABLE) {
            // Belt and braces: the build should already have excluded this file.
            Log.w(TAG, "refusing to listen on a non-debuggable build");
            return;
        }
        Thread t = new Thread(DebugBridge::serve, "tarish-debug");
        t.setDaemon(true);
        t.start();
    }

    private static void serve() {
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(SOCKET);
        } catch (Exception e) {
            // Already bound means a previous instance of the app still holds it, which is
            // normal across an activity restart. Not worth a crash.
            Log.w(TAG, "cannot listen on @" + SOCKET, e);
            return;
        }
        Log.i(TAG, "listening on @" + SOCKET);
        while (true) {
            try (LocalSocket s = server.accept()) {
                handle(s);
            } catch (Exception e) {
                Log.w(TAG, "connection failed", e);
            }
        }
    }

    private static void handle(LocalSocket s) throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        OutputStream out = s.getOutputStream();
        String reply;
        try {
            reply = run(line == null ? "" : line.trim());
        } catch (Exception e) {
            // The caller is a test harness. An exception it cannot see is a test that
            // reports the wrong thing, so every failure comes back as text.
            reply = "error " + e;
        }
        out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * The application context, without holding a reference to any Activity.
     *
     * TransferService.watch needs one, and this class is started reflectively with no
     * arguments so it has none. ActivityThread.currentApplication() is the standard way in
     * from a system app and cannot go stale the way a cached Activity would.
     */
    private static android.content.Context context() {
        return android.app.ActivityThread.currentApplication();
    }

    private static String run(String line) throws Exception {
        String[] a = line.split("\\s+", 3);
        String cmd = a.length > 0 ? a[0] : "";
        ITarishService svc = Daemon.get();
        if (svc == null && !"ping".equals(cmd)) {
            return "error daemon not published";
        }
        switch (cmd) {
            case "ping":
                return "ok";

            // Peers AS THE APP SEES THEM, which is not the same list tarishctl prints:
            // psm and bleAddress decide whether an off-network send is even possible, and
            // a harness that cannot see them cannot tell "no peer" from "peer with no
            // Bluetooth address".
            case "peers": {
                StringBuilder sb = new StringBuilder();
                for (TarishPeer p : svc.getPeers()) {
                    sb.append(p.id).append('|')
                      .append(p.protocol).append('|')
                      .append(p.name == null ? "" : p.name).append('|')
                      .append(p.psm).append('|')
                      .append(p.bleAddress == null ? "" : p.bleAddress).append('|')
                      .append(p.bluetoothMac == null ? "" : p.bluetoothMac).append(';');
                }
                return sb.length() == 0 ? "none" : sb.toString();
            }

            // The whole point. QuickShareSender.send() is exactly what the share sheet
            // calls: it tries the LAN, and off-network falls through to Bluetooth, after
            // which the daemon may ask to upgrade to Wi-Fi Direct and the app answers.
            // One command therefore exercises the Bluetooth bootstrap AND the upgrade,
            // which is the pair that has never been driven from a harness.
            case "send": {
                if (a.length < 3) {
                    return "usage: send <peer-id> <path>";
                }
                TarishPeer peer = null;
                for (TarishPeer p : svc.getPeers()) {
                    if (a[1].equals(p.id)) {
                        peer = p;
                        break;
                    }
                }
                if (peer == null) {
                    return "error peer " + a[1] + " not found";
                }
                File f = new File(a[2]);
                if (!f.isFile()) {
                    return "error no such file: " + a[2];
                }
                ParcelFileDescriptor pfd =
                        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                long id = QuickShareSender.send(svc,
                        peer,
                        new ParcelFileDescriptor[]{pfd},
                        new String[]{f.getName()});
                if (id == 0) {
                    return "error send refused";
                }
                // START THE SERVICE, AS THE SHARE SHEET DOES.
                //
                // Not decoration. TransferService owns onUpgradeNeeded — MainActivity
                // deliberately leaves it empty, because the service is the half that
                // survives the activity going away and joining a group twice would tear
                // down the first one. So without this, nobody answers when the peer offers
                // a Wi-Fi Direct group: the daemon waits out its timeout and the transfer
                // crawls on Bluetooth.
                //
                // Measured before this line existed: the receiver hosted a group, the
                // sender logged "asking the app to join", and thirty seconds later
                // "peer accepted, sending" — a timeout, not an answer. 2 MB then took
                // 171s at 12 KB/s.
                //
                // A harness that drives a different code path from the product measures
                // the harness. This makes the two agree.
                TransferService.watch(context(), id, "debug bridge", true);
                return "id " + id;
            }

            default:
                return "usage: ping | peers | send <peer-id> <path>";
        }
    }
}
