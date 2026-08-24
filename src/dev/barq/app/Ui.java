package dev.barq.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * One place for the palette and the small view helpers.
 *
 * <p>The app has no resource directory: it is a system app built in-tree with no
 * AndroidX, so a values/colors.xml would buy indirection and nothing else. Keeping the
 * palette here means one file to change and no chance of a hex code drifting between
 * screens.
 */
final class Ui {

    /** Page background. Near-black, not black, so the sheet above it reads as raised. */
    static final int BG = Color.parseColor("#0B0D12");
    /** Raised surfaces: the received-files card. */
    static final int SURFACE = Color.parseColor("#171A21");
    static final int SURFACE_EDGE = Color.parseColor("#242833");

    /** Barq is برق, lightning. An electric blue rather than a system accent. */
    static final int ACCENT = Color.parseColor("#7FB2FF");
    static final int ACCENT_DIM = Color.parseColor("#3D5A8C");

    static final int TEXT = Color.parseColor("#F2F4F8");
    static final int TEXT_MUTED = Color.parseColor("#8E96A6");
    static final int TEXT_FAINT = Color.parseColor("#5C6474");

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        // Letter spacing at display sizes stops large text looking cramped.
        if (sp >= 20) {
            t.setLetterSpacing(-0.01f);
        }
        return t;
    }

    /** A rounded surface with a hairline edge, used for cards and chips. */
    static GradientDrawable card(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != Color.TRANSPARENT) {
            d.setStroke(Math.max(1, dp(c, 0.5f)), stroke);
        }
        return d;
    }

    static GradientDrawable circle(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    /** Human file size. Binary units, because that is what a file manager shows. */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] unit = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < unit.length - 1) {
            v /= 1024;
            i++;
        }
        return (v >= 10 ? String.format("%.0f %s", v, unit[i])
                        : String.format("%.1f %s", v, unit[i]));
    }
}
