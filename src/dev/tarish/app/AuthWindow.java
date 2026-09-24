package dev.tarish.app;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.util.Log;

import dev.tarish.ITarishService;

/**
 * The authenticated window during which the VPN lockdown exemption is permitted.
 *
 * WHAT IT ACTUALLY BUYS, because this is easy to overstate. Opening a window changes exactly
 * one thing: tarishd moves its routing rule from below Android's VPN kill-switch to above
 * it. It does not enable sharing, does not make the device discoverable, and grants no
 * network reach beyond the link-local route that rule already points at. On a device with no
 * always-on VPN in lockdown mode it changes nothing observable at all.
 *
 * WHY THE APP DOES THE AUTHENTICATING. BiometricPrompt is framework API a native service
 * cannot reach -- the same boundary that puts BLE and Wi-Fi Direct here rather than in the
 * daemon. So what crosses the binder is a REPORT, not a proof, and the trust boundary is the
 * caller's identity: ITarishService is reachable only from this app's SELinux domain, keyed
 * on package name AND platform signature (sepolicy/tarish_app.te, security review #28).
 *
 * The window is short and the daemon caps it regardless of what is asked for. Three separate
 * things close it -- this class, the daemon's own timer, and the daemon clearing it at
 * startup -- so a crash on either side ends with the exemption withdrawn rather than stuck
 * open. See ITarishService.setAuthenticated.
 *
 * NOT A KEYSTORE-BACKED PROOF, AND THAT IS A REASONABLE PLACE TO STOP. A stronger design
 * would tie the window to a Keystore key with setUserAuthenticationRequired so the daemon
 * could VERIFY authentication rather than trust a report. It is not built, for two reasons
 * and the second matters more than the first.
 *
 * Verifying an attestation means a parser in the process holding CAP_NET_ADMIN, which is
 * what this architecture spends most of its effort avoiding.
 *
 * And the threat it would address is thin here. This app ships IN the image and is platform
 * signed, so there is no substitution path — no sideload, no repackage, no third-party
 * build. "Compromised app" reduces to the platform key leaking, in which case the exemption
 * is nobody's biggest problem, or to a bug in this app being exploited. That second one is
 * real, and worth naming honestly because this app is deliberately the process that parses
 * hostile file content — that is why such parsing was kept out of tarishsharingd. But an
 * attacker who has that already holds this uid and can drive the ordinary share path, and
 * what the exemption adds is link-local on tlink0 against a table holding one route.
 *
 * So it is a stated residual bounded by the same scoping as everything else, not an
 * engineering debt. Revisit if the app ever stops being part of the image.
 */
final class AuthWindow {
    private static final String TAG = "TarishAuth";

    /** Must match, or be under, the daemon's own cap in setAuthenticated. */
    static final int WINDOW_SECONDS = 600;

    /**
     * When the window closes, on the monotonic clock.
     *
     * elapsedRealtime, not currentTimeMillis: a window must not be extended or cut short by
     * the wall clock moving, whether from NTP, a timezone change or a user setting the date.
     */
    private static long closesAtMs = 0L;

    private AuthWindow() {}

    static boolean isOpen() {
        return SystemClock.elapsedRealtime() < closesAtMs;
    }

    /** Milliseconds left, or 0. For the countdown in the UI. */
    static long remainingMs() {
        long left = closesAtMs - SystemClock.elapsedRealtime();
        return left > 0 ? left : 0L;
    }

    /**
     * Is there any way for this person to authenticate at all?
     *
     * THIS RUNS FROM onCreate AND MUST NOT THROW. It shipped once without USE_BIOMETRIC in
     * the manifest and took the whole app down before it drew a frame:
     *
     *   SecurityException: Must have USE_BIOMETRIC permission
     *     at AuthWindow.canAuthenticate -> MainActivity.render -> MainActivity.onCreate
     *
     * The permission is there now, so the catch is a net rather than the fix. It is worth
     * having anyway: the same rule the rest of this app follows is that a lifecycle callback
     * which throws kills the process, and a security CHECK that bricks the app is a worse
     * outcome than the thing it was checking for.
     *
     * ON ERROR IT RETURNS TRUE, which looks like the wrong direction and is not. `true` here
     * means only "do not put up the blocking screen" -- it does not grant anything. The
     * window still cannot open without a real prompt succeeding, so an exception costs the
     * BLOCK, never the exemption. Failing the other way would make an unrelated framework
     * fault indistinguishable from "you have no PIN" and leave the app permanently unusable
     * with no way for the person to act on it.
     */
    static boolean canAuthenticate(Activity a) {
        try {
            BiometricManager bm = a.getSystemService(BiometricManager.class);
            if (bm == null) {
                return false;
            }
            int r = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            return r == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (RuntimeException e) {
            Log.e(TAG, "cannot determine whether authentication is available — "
                    + "not blocking the app, but a window will still need a real prompt", e);
            return true;
        }
    }

    /**
     * Ask the person to authenticate, and open the window if they do.
     *
     * DEVICE_CREDENTIAL is allowed alongside biometrics deliberately. A PIN or passphrase is
     * the thing an enterprise policy actually mandates; refusing to accept it would lock out
     * anyone who has not enrolled a fingerprint, on a device where the credential is the
     * stronger factor anyway.
     *
     * `onResult` runs on the main thread with whether the window is now open.
     */
    static void request(Activity a, ITarishService service, java.util.function.Consumer<Boolean> onResult) {
        if (service == null) {
            Log.w(TAG, "no service — cannot open an authenticated window");
            onResult.accept(false);
            return;
        }
        BiometricPrompt prompt = new BiometricPrompt.Builder(a)
                // NOT "VPN lockdown is active" -- that was the title and it is a claim this
                // app cannot make. always_on_vpn_lockdown reads null while lockdown is in
                // force, so on a device with no VPN at all the old title simply lied. The
                // subtitle says what unlocking DOES, which is true either way.
                .setTitle("Unlock Tarish")
                .setSubtitle("Authenticate to share, and to allow the direct link under a "
                        + "VPN kill-switch")
                .setDescription(
                        "For the next " + (WINDOW_SECONDS / 60) + " minutes, Tarish may reach "
                        + "devices on the direct link only. Nothing else is exempted from the "
                        + "VPN, and no other app is affected.")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        prompt.authenticate(new CancellationSignal(), a.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        // Set the local deadline only AFTER the daemon has accepted, so the
                        // UI can never show a window the daemon does not believe in.
                        try {
                            service.setAuthenticated(true, WINDOW_SECONDS);
                            closesAtMs = SystemClock.elapsedRealtime()
                                    + WINDOW_SECONDS * 1000L;
                            Log.i(TAG, "authenticated window open for " + WINDOW_SECONDS + "s");
                            onResult.accept(true);
                        } catch (Exception e) {
                            Log.w(TAG, "daemon refused the authenticated window", e);
                            closesAtMs = 0L;
                            onResult.accept(false);
                        }
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        // Cancelling is an ordinary outcome, not a fault: the window simply
                        // does not open and sharing stays blocked under lockdown.
                        Log.i(TAG, "authentication not completed (" + code + "): " + msg);
                        onResult.accept(false);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // One rejected attempt. The prompt stays up for another try, so
                        // there is nothing to report yet.
                    }
                });
    }

    /**
     * Close the window now.
     *
     * Called when the app leaves the foreground, because the agreed shape is that the
     * exemption exists only while someone is present and looking at it. Best effort: the
     * daemon's own timer and its startup clear both close it anyway, so a failure here
     * shortens nothing and leaves nothing open indefinitely.
     */
    static void close(ITarishService service) {
        boolean wasOpen = isOpen();
        closesAtMs = 0L;
        if (service == null) {
            return;
        }
        try {
            service.setAuthenticated(false, 0);
            if (wasOpen) {
                Log.i(TAG, "authenticated window closed");
            }
        } catch (Exception e) {
            Log.w(TAG, "could not close the authenticated window", e);
        }
    }
}
