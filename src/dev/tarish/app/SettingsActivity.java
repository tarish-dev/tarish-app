package dev.tarish.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import dev.tarish.TarishPolicy;
import dev.tarish.ITarishService;

/**
 * Settings: the device name, and what this device may do per protocol and per direction.
 *
 * <p>This screen and the MDM control surface are the same policy seen from two sides. A
 * field an administrator has pinned is shown DISABLED with a note saying so, rather than
 * hidden — a person who cannot find a control assumes the app is broken, and a person who
 * taps a control that does nothing assumes the same. Showing it greyed with a reason is
 * the only version that is honest.
 *
 * <p>Every change is written to preferences and pushed to the daemon immediately. There
 * is no Save button: a settings screen with unsaved state is a screen people leave in the
 * wrong state.
 */
public final class SettingsActivity extends Activity {

    private static final String TAG = "TarishSettings";

    /** The daemon publishes itself here; it is not a bound service. */
    private static final String SERVICE_NAME = "dev.tarish.ITarishService/default";

    /** What is in the name field right now, committed on focus loss or onPause. */
    private String typedName;

    private PolicyStore store;
    private ITarishService service;
    private TarishPolicy policy;

    private LinearLayout content;
    private EditText nameField;

    /** Look the daemon up fresh; it may have restarted since the last screen. */
    private void connect() {
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        service = binder == null ? null : ITarishService.Stub.asInterface(binder);
        if (service == null) {
            Log.w(TAG, "daemon did not publish " + SERVICE_NAME);
        }
    }

    // Force the chosen Light/Dark (or leave the system's) before any resource resolves.
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Theme.wrap(base));
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new PolicyStore(this);
        policy = store.effective();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.bg(this));
        scroll.setFitsSystemWindows(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        setContentView(scroll);

        render();
    }

    /** The name peers actually see, as the daemon resolves it. */
    private String effectiveName() {
        if (service != null) {
            try {
                String n = service.getDeviceName();
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            } catch (Exception e) {
                Log.w(TAG, "could not read the device name", e);
            }
        }
        return "This device";
    }

    /** Write the typed name, if it changed. Safe to call repeatedly. */
    private void commitName() {
        if (typedName == null || policy.deviceNameManaged) {
            return;
        }
        String wanted = typedName.trim();
        if (wanted.equals(policy.deviceName)) {
            return;
        }
        policy.deviceName = wanted;
        store.setUserDeviceName(wanted);
        push();
        // Re-read: clearing the field falls back to the model, and the hint should say
        // so rather than keep showing the name that was just removed.
        if (nameField != null) {
            nameField.setHint(effectiveName());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        commitName();
    }

    @Override
    protected void onResume() {
        super.onResume();
        connect();
        // Managed configuration can change while this screen is open, and the daemon
        // may have restarted -- push what we hold so it is never running on less than
        // what this screen is showing.
        policy = store.effective();
        push();
        render();
    }

    private void render() {
        if (content == null) {
            return;
        }
        content.removeAllViews();

        content.addView(Ui.text(this, "Settings", 26, Ui.textColor(this), true));
        space(6);

        if (PolicyStore.anyManaged(policy)) {
            TextView note = Ui.text(this,
                    "Some settings are managed by your organization and cannot be changed here.",
                    12, Ui.accent(this), false);
            note.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            content.addView(note);
        }
        space(10);

        // ---- appearance --------------------------------------------------------
        // First: it is the setting most people come here to change, and it is the one
        // control that is never managed, so it should not sit below the policy fields.
        content.addView(Ui.sectionLabel(this, "APPEARANCE"));
        appearanceControl();
        caption("System follows your device's light or dark setting.");

        space(16);

        // ---- device name -------------------------------------------------------
        content.addView(Ui.sectionLabel(this, "DEVICE NAME"));
        LinearLayout nameCard = Ui.cardBox(this);
        nameField = new EditText(this);
        nameField.setText(policy.deviceName);
        // The HINT is the name actually in use, so an empty field means "using the
        // device model" instead of looking like the setting is broken. Asking the daemon
        // rather than guessing: it resolves persist.tarish.name, then ro.product.model,
        // then a constant, and only it knows which one won.
        nameField.setHint(effectiveName());
        nameField.setSingleLine(true);
        nameField.setTextColor(Ui.textColor(this));
        nameField.setHintTextColor(Ui.textFaint(this));
        nameField.setBackgroundColor(Color.TRANSPARENT);
        nameField.setEnabled(!policy.deviceNameManaged);
        if (policy.deviceNameManaged) {
            nameField.setTextColor(Ui.textMuted(this));
        }
        // NOT on every keystroke. The name lives in a `persist.` property, which is
        // written to disk, so pushing per character meant a disk write per character and
        // an mDNS identity that changed under a peer mid-word. Typing is tracked in
        // memory and committed when the field loses focus or the screen goes away.
        nameField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable e) {
                typedName = e.toString();
            }
        });
        nameField.setOnFocusChangeListener((v, focused) -> {
            if (!focused) {
                commitName();
            }
        });
        nameCard.addView(nameField);
        content.addView(nameCard);
        caption(policy.deviceNameManaged
                ? "Set by your organization."
                : "What other devices see. Leave empty to use the device model.");

        // ---- protocols ---------------------------------------------------------
        space(16);
        content.addView(Ui.sectionLabel(this, "AIRDROP  ·  APPLE DEVICES"));
        protocolCard(true);

        space(16);
        content.addView(Ui.sectionLabel(this, "QUICK SHARE  ·  ANDROID AND WINDOWS"));
        protocolCard(false);

        // Quick Share only, and it governs SENDING -- which is why it sits here under
        // Quick Share rather than with the incoming-transfer prompt below. AirDrop has no
        // equivalent: its confirmation is on the receiving device, a different question.
        LinearLayout pinCard = Ui.cardBox(this);
        pinCard.addView(toggle("Require PIN confirmation for sending",
                policy.requirePin,
                !policy.requirePinManaged,
                on -> {
                    store.setUserRequirePin(on);
                    policy.requirePin = on;
                    push();
                }));
        content.addView(pinCard);
        caption(policy.requirePinManaged
                ? "Set by your organization."
                : "When on, you type the PIN shown on the other device before anything is"
                        + " sent. When off, files are sent as soon as the other device"
                        + " accepts.");

        // ---- confirmation ------------------------------------------------------
        space(16);
        content.addView(Ui.sectionLabel(this, "INCOMING TRANSFERS"));
        LinearLayout confirmCard = Ui.cardBox(this);
        confirmCard.addView(toggle("Ask before accepting",
                policy.requireConfirmation,
                !policy.requireConfirmationManaged,
                on -> {
                    store.setUserConfirmation(on);
                    policy.requireConfirmation = on;
                    push();
                }));
        content.addView(confirmCard);
        caption(policy.requireConfirmationManaged
                ? "Set by your organization."
                : "When off, files are accepted without asking.");

        // ---- AirDrop identity --------------------------------------------------
        // A privacy control, not a transport toggle: Apple peers remember this device by a
        // persisted random handle (NOT the MAC, which the radio randomises each acquire), so
        // it shows up as ONE device across sessions. Resetting it makes the phone unlinkable
        // to peers that had it saved -- see ITarishService.resetIdentity.
        space(16);
        content.addView(Ui.sectionLabel(this, "AIRDROP IDENTITY"));
        LinearLayout idCard = Ui.cardBox(this);
        TextView reset = Ui.text(this, "Reset identity", 15, Ui.error(this), true);
        int rp = Ui.dp(this, 12);
        reset.setPadding(rp, rp, rp, rp);
        reset.setOnClickListener(v -> confirmResetIdentity());
        idCard.addView(reset);
        content.addView(idCard);
        caption("Apple devices remember this phone by a random handle — not your name and"
                + " not its address. Reset it to appear as a brand-new device. Good for"
                + " privacy; devices that saved you will no longer recognise you.");

        // ---- about -------------------------------------------------------------
        // Three components, three versions: the app (this package), the daemon (over
        // AIDL), and the AWDL stack "link" (the daemon reports the shim it loaded).
        space(16);
        content.addView(Ui.sectionLabel(this, "ABOUT"));
        LinearLayout about = Ui.cardBox(this);
        about.addView(versionRow("App", appVersion()));
        about.addView(Ui.rule(this));
        about.addView(versionRow("Daemon", daemonVersion()));
        about.addView(Ui.rule(this));
        about.addView(versionRow("Link", linkVersion()));
        content.addView(about);
        caption("Tarish — open AirDrop and Quick Share, no Google account.");

        space(24);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private String daemonVersion() {
        if (service == null) {
            connect();
        }
        if (service == null) {
            return "unavailable";
        }
        try {
            return service.getDaemonVersion();
        } catch (Exception e) {
            Log.w(TAG, "getDaemonVersion failed", e);
            return "?";
        }
    }

    private String linkVersion() {
        if (service == null) {
            connect();
        }
        if (service == null) {
            return "unavailable";
        }
        try {
            return service.getLinkVersion();
        } catch (Exception e) {
            Log.w(TAG, "getLinkVersion failed", e);
            return "?";
        }
    }

    /** A left-aligned label with the version on the right, matching the toggle rows. */
    private LinearLayout versionRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 12);
        row.setPadding(p, p, p, p);
        row.addView(Ui.text(this, label, 15, Ui.textColor(this), false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.text(this, value == null ? "?" : value, 14, Ui.textFaint(this), false));
        return row;
    }

    /** A three-way segmented control: System / Light / Dark. */
    private void appearanceControl() {
        int current = Theme.mode(this);
        int[] modes = { Theme.SYSTEM, Theme.LIGHT, Theme.DARK };
        String[] labels = { "System", "Light", "Dark" };

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < modes.length; i++) {
            int mode = modes[i];
            boolean selected = mode == current;

            TextView seg = Ui.text(this, labels[i], 14,
                    selected ? Ui.bg(this) : Ui.textColor(this), selected);
            seg.setGravity(Gravity.CENTER);
            int vp = Ui.dp(this, 10);
            seg.setPadding(vp, vp, vp, vp);
            if (selected) {
                android.graphics.drawable.GradientDrawable g =
                        new android.graphics.drawable.GradientDrawable();
                g.setColor(Ui.accent(this));
                g.setCornerRadius(Ui.dp(this, 8));
                seg.setBackground(g);
            }
            seg.setOnClickListener(v -> {
                if (mode == Theme.mode(this)) {
                    return;
                }
                Theme.setMode(this, mode);
                // Rebuild this screen under the new appearance; MainActivity re-themes
                // itself in onResume when it sees the choice changed.
                recreate();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            int gap = Ui.dp(this, 4);
            lp.setMargins(i == 0 ? 0 : gap, 0, i == modes.length - 1 ? 0 : gap, 0);
            row.addView(seg, lp);
        }

        LinearLayout card = Ui.cardBox(this);
        card.addView(row);
        content.addView(card);
    }

    /** Two-step, because the trade is real: continuity for privacy, and it cannot be undone. */
    private void confirmResetIdentity() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Reset AirDrop identity?")
                .setMessage("This phone will appear as a brand-new device to everyone nearby."
                        + " Macs and iPhones that had saved it will no longer recognise it,"
                        + " and reconnecting to them becomes less seamless. This cannot be"
                        + " undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (d, w) -> doResetIdentity())
                .show();
    }

    private void doResetIdentity() {
        if (service == null) {
            connect();
        }
        if (service == null) {
            toast("Tarish service is not running.");
            return;
        }
        try {
            service.resetIdentity();
            toast("New AirDrop identity generated.");
        } catch (Exception e) {
            Log.w(TAG, "resetIdentity failed", e);
            toast("Could not reset the identity.");
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Two switches — send and receive — over one mode value. */
    private void protocolCard(boolean airdrop) {
        int mode = airdrop ? policy.airdrop : policy.quickshare;
        boolean managed = airdrop ? policy.airdropManaged : policy.quickshareManaged;
        String key = airdrop ? PolicyStore.keyAirdrop() : PolicyStore.keyQuickshare();

        LinearLayout card = Ui.cardBox(this);
        card.addView(toggle("Receive", PolicyStore.allowsReceive(mode), !managed, on -> {
            int updated = PolicyStore.withReceive(currentMode(airdrop), on);
            apply(airdrop, key, updated);
        }));
        card.addView(Ui.rule(this));
        card.addView(toggle("Send", PolicyStore.allowsSend(mode), !managed, on -> {
            int updated = PolicyStore.withSend(currentMode(airdrop), on);
            apply(airdrop, key, updated);
        }));
        content.addView(card);

        if (managed) {
            caption("Set by your organization.");
        } else if (!airdrop) {
            // Said plainly rather than letting a switch imply a capability that is not
            // finished. Quick Share discovery works; the transfer stack does not yet.
            caption("Quick Share is still in development on this build.");
        }
    }

    private int currentMode(boolean airdrop) {
        return airdrop ? policy.airdrop : policy.quickshare;
    }

    private void apply(boolean airdrop, String key, int mode) {
        if (airdrop) {
            policy.airdrop = mode;
        } else {
            policy.quickshare = mode;
        }
        store.setUserMode(key, mode);
        push();
    }

    /** Send the whole policy to the daemon, which is what actually enforces it. */
    private void push() {
        if (service == null) {
            return;
        }
        try {
            service.setPolicy(policy);
            if (!policy.deviceNameManaged) {
                service.setDeviceName(policy.deviceName);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not push policy", e);
        }
    }

    private interface OnToggle {
        void changed(boolean on);
    }

    private LinearLayout toggle(String label, boolean on, boolean enabled, OnToggle cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 12);
        row.setPadding(p, p, p, p);

        TextView t = Ui.text(this, label, 15, enabled ? Ui.textColor(this) : Ui.textMuted(this), false);
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setEnabled(enabled);
        // Set the listener AFTER the initial state so building the row does not fire it
        // and write a preference nobody touched.
        sw.setOnCheckedChangeListener((v, checked) -> cb.changed(checked));
        row.addView(sw);
        return row;
    }

    private void caption(String s) {
        TextView t = Ui.text(this, s, 11, Ui.textFaint(this), false);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), 0, 0);
        content.addView(t);
    }

    private void space(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, dp)));
        content.addView(v);
    }
}
