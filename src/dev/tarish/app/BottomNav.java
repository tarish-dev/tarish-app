package dev.tarish.app;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Receive / Send, pinned to the bottom.
 *
 * <p>The two directions are modes of one screen rather than separate activities. The
 * identity, the service connection and the transfer state are shared, so switching
 * should not throw any of it away — and sending should feel like the same tool.
 *
 * <p>The active mode is marked by a pill around the ICON only, with the label beneath
 * it. A pill stretched across the whole tab, which is what this looked like first,
 * reads as a selected table row rather than a chosen mode.
 */
final class BottomNav extends LinearLayout {

    interface Listener {
        void onModeChosen(boolean send);
    }

    private final Tab receive;
    private final Tab send;

    BottomNav(Context c, Listener listener) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));

        receive = new Tab(c, Glyph.Kind.DOWNLOAD, "Receive");
        send = new Tab(c, Glyph.Kind.UPLOAD, "Send");
        receive.setOnClickListener(v -> listener.onModeChosen(false));
        send.setOnClickListener(v -> listener.onModeChosen(true));

        addView(receive, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(send, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Sit above the gesture bar rather than under it.
        setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8) + bars.bottom);
            return insets;
        });
    }

    void setMode(boolean sendMode) {
        send.setActive(sendMode);
        receive.setActive(!sendMode);
    }

    /** Icon in a pill, label beneath. */
    private static final class Tab extends LinearLayout {
        private final FrameLayout pill;
        private final Glyph glyph;
        private final TextView label;

        Tab(Context c, Glyph.Kind kind, String text) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);

            pill = new FrameLayout(c);
            int w = Ui.dp(c, 62), h = Ui.dp(c, 32);
            pill.setLayoutParams(new LayoutParams(w, h));
            glyph = new Glyph(c, kind, Ui.textMuted(c));
            pill.addView(glyph, new FrameLayout.LayoutParams(h, h, Gravity.CENTER));
            addView(pill);

            label = Ui.text(c, text, 12, Ui.textMuted(c), false);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Ui.dp(c, 4), 0, 0);
            addView(label);
        }

        void setActive(boolean active) {
            pill.setBackground(active
                    ? Ui.card(getContext(), Ui.accentFill(getContext()), Color.TRANSPARENT, 16)
                    : null);
            glyph.setColor(active ? Ui.onAccent(getContext()) : Ui.textMuted(getContext()));
            label.setTextColor(active ? Ui.textColor(getContext()) : Ui.textMuted(getContext()));
        }
    }
}
