package dev.barq.app;

import android.app.Activity;
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

import dev.barq.BarqPolicy;
import dev.barq.IBarqService;

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

    private static final String TAG = "BarqSettings";

    /** The daemon publishes itself here; it is not a bound service. */
    private static final String SERVICE_NAME = "dev.barq.IBarqService/default";

    /** What is in the name field right now, committed on focus loss or onPause. */
    private String typedName;

    private PolicyStore store;
    private IBarqService service;
    private BarqPolicy policy;

    private LinearLayout content;
    private EditText nameField;

    /** Look the daemon up fresh; it may have restarted since the last screen. */
    private void connect() {
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        service = binder == null ? null : IBarqService.Stub.asInterface(binder);
        if (service == null) {
            Log.w(TAG, "daemon did not publish " + SERVICE_NAME);
        }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new PolicyStore(this);
        policy = store.effective();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
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

        content.addView(Ui.text(this, "Settings", 26, Ui.TEXT, true));
        space(6);

        if (PolicyStore.anyManaged(policy)) {
            TextView note = Ui.text(this,
                    "Some settings are managed by your organization and cannot be changed here.",
                    12, Ui.ACCENT, false);
            note.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            content.addView(note);
        }
        space(10);

        // ---- device name -------------------------------------------------------
        content.addView(Ui.sectionLabel(this, "DEVICE NAME"));
        LinearLayout nameCard = Ui.cardBox(this);
        nameField = new EditText(this);
        nameField.setText(policy.deviceName);
        // The HINT is the name actually in use, so an empty field means "using the
        // device model" instead of looking like the setting is broken. Asking the daemon
        // rather than guessing: it resolves persist.barq.name, then ro.product.model,
        // then a constant, and only it knows which one won.
        nameField.setHint(effectiveName());
        nameField.setSingleLine(true);
        nameField.setTextColor(Ui.TEXT);
        nameField.setHintTextColor(Ui.TEXT_FAINT);
        nameField.setBackgroundColor(Color.TRANSPARENT);
        nameField.setEnabled(!policy.deviceNameManaged);
        if (policy.deviceNameManaged) {
            nameField.setTextColor(Ui.TEXT_MUTED);
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
        space(24);
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

        TextView t = Ui.text(this, label, 15, enabled ? Ui.TEXT : Ui.TEXT_MUTED, false);
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
        TextView t = Ui.text(this, s, 11, Ui.TEXT_FAINT, false);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), 0, 0);
        content.addView(t);
    }

    private void space(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, dp)));
        content.addView(v);
    }
}
