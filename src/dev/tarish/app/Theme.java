package dev.tarish.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * The user's light/dark choice, and the one trick that makes a plain Activity honour it.
 *
 * <p>Until now the app only ever followed the system: the resource system picks
 * res/values or res/values-night from the device's night mode and nothing here got a
 * say. This adds a three-way choice — System, Light, Dark — the way every other app
 * offers it.
 *
 * <p><b>Why not the framework's own switch.</b> This app carries no AndroidX, so there is
 * no {@code AppCompatDelegate.setDefaultNightMode}. The framework's per-app override
 * ({@code UiModeManager.setApplicationNightMode}) exists on this API level, but it has no
 * value that means "go back to following the system" once you have set it, so a control
 * that includes a System option cannot be built on it cleanly. The reliable,
 * self-contained way is the classic one: override the base context's {@code uiMode}
 * before the Activity resolves any resource, and do <em>nothing</em> for SYSTEM so the
 * old behaviour — follow the device — is exactly what still happens.
 */
final class Theme {

    static final int SYSTEM = 0;
    static final int LIGHT = 1;
    static final int DARK = 2;

    private static final String PREFS = "tarish_ui";
    private static final String K_MODE = "theme_mode";

    private Theme() { }

    // Device-protected storage: attachBaseContext runs before first unlock on a
    // directBootAware app, and reading credential-encrypted preferences then throws.
    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int mode(Context c) {
        return prefs(c).getInt(K_MODE, SYSTEM);
    }

    static void setMode(Context c, int mode) {
        prefs(c).edit().putInt(K_MODE, mode).apply();
    }

    /**
     * Wrap an Activity's base context so a forced Light/Dark choice overrides the system
     * {@code uiMode}. SYSTEM returns the context untouched, so the app follows the device
     * exactly as it did before this control existed. Call it from
     * {@code attachBaseContext}.
     */
    static Context wrap(Context base) {
        int mode = mode(base);
        if (mode == SYSTEM) {
            return base;
        }
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int night = mode == DARK
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = night | (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK);
        return base.createConfigurationContext(cfg);
    }
}
