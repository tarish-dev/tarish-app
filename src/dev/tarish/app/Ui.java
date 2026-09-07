package dev.tarish.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

    // Tarish's own look, not a copy of the reference it was measured against.
    //
    // That reference is soft: lavender, pill shapes, 26dp corners, everything floating
    // in its own rounded card. Tarish is طارش, the one you send with a message - a courier -
    // and the brand reads as infrastructure that works: basalt, Signal amber, tight corners,
    // hairline rules instead of cards, uppercase micro-labels. It should read like an
    // instrument rather than a settings page.
    //
    // An earlier version of this note said "Tarish is برق, lightning". That is barq, the
    // name this app used to have; the sentence survived the rename and the mark did not.

    // COLOURS COME FROM RESOURCES, NOT FROM CONSTANTS.
    //
    // These were `static final int` values parsed from hex, which is exactly what a light
    // theme cannot survive: a constant is fixed at class-load and knows nothing about the
    // configuration. Reading them through Context.getColor is what lets one call site render
    // correctly in both themes, because the resource system picks res/values or
    // res/values-night for us.
    //
    // The values themselves live in res/values*/colors.xml and come from the brand kit
    // unchanged; this class only asks for them by role. Every method takes a Context because
    // every caller already had one -- the Ui helpers are all Context-first.

    /** Page background. */
    static int bg(Context c) {
        return c.getColor(R.color.t_bg);
    }

    /** A raised block. Separation comes from rules rather than shadow. */
    static int surface(Context c) {
        return c.getColor(R.color.t_surface);
    }

    static int surfaceEdge(Context c) {
        return c.getColor(R.color.t_surface_edge);
    }

    static int surfaceSunk(Context c) {
        return c.getColor(R.color.t_surface_sunk);
    }

    /**
     * Colour of the hairline between rows.
     *
     * `ruleColor`, not `rule`: {@link #rule(Context)} already returns the divider VIEW, and
     * two methods with one signature do not compile. The pair reads better named apart
     * anyway -- one is a colour, the other is a widget.
     */
    static int ruleColor(Context c) {
        return c.getColor(R.color.t_rule);
    }

    /** Signal amber: the brand, the send action, active transfers. */
    static int accent(Context c) {
        return c.getColor(R.color.t_accent);
    }

    static int accentDim(Context c) {
        return c.getColor(R.color.t_accent_dim);
    }

    static int accentFill(Context c) {
        return c.getColor(R.color.t_accent_fill);
    }

    /** Text and glyphs sitting ON the amber fill. */
    static int onAccent(Context c) {
        return c.getColor(R.color.t_on_accent);
    }

    /** Jade: received and complete. The counterpart to amber's sending, never the brand. */
    static int live(Context c) {
        return c.getColor(R.color.t_live);
    }

    /** Ember: failures and destructive actions only. */
    static int error(Context c) {
        return c.getColor(R.color.t_error);
    }

    /** Primary text colour. Named apart from {@link #text(Context, String, float, int,
     * boolean)}, which builds a TextView. */
    static int textColor(Context c) {
        return c.getColor(R.color.t_text);
    }

    static int textMuted(Context c) {
        return c.getColor(R.color.t_text_muted);
    }

    static int textFaint(Context c) {
        return c.getColor(R.color.t_text_faint);
    }

    private Ui() {}

    /** A drawn glyph centred on a filled circle — the one motif the whole screen uses. */
    static FrameLayout glyphInCircle(Context c, Glyph.Kind kind, int ink, int fill, int sizeDp) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(circle(fill));
        int d = dp(c, sizeDp);
        f.setLayoutParams(new LinearLayout.LayoutParams(d, d));
        Glyph g = new Glyph(c, kind, ink);
        f.addView(g, new FrameLayout.LayoutParams(d, d));
        return f;
    }

    // ------------------------------------------------------- building blocks ---
    //
    // Quick Share's structure, which is what makes it read as a utility: a small
    // coloured section label, then a card holding the content. Everything lives in a
    // card; the page itself is nearly empty. Grouping is what makes it scannable, and
    // the flat full-bleed layout this replaced had none.

    /**
     * An uppercase micro-label: INBOX, NEARBY.
     *
     * Letterspaced and small rather than coloured and sentence-case. It marks a region
     * without competing with the content, which is what a heading in a utility should do.
     */
    static TextView sectionLabel(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 11, textFaint(c), true);
        t.setLetterSpacing(0.18f);
        t.setPadding(dp(c, 2), dp(c, 26), 0, dp(c, 10));
        return t;
    }

    /** A hairline. Used instead of giving every row its own card. */
    static View rule(Context c) {
        View v = new View(c);
        v.setBackgroundColor(ruleColor(c));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f))));
        return v;
    }

    /** A small filled dot, for "live" states. */
    static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        v.setBackground(circle(color));
        int d = dp(c, sizeDp);
        v.setLayoutParams(new LinearLayout.LayoutParams(d, d));
        return v;
    }

    /** A raised block. Tight corners, hairline edge — an instrument panel, not a pill. */
    static LinearLayout cardBox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(card(c, surface(c), surfaceEdge(c), 12));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    /**
     * An identity row: glyph, then a bold name over a state line.
     *
     * Compact on purpose. The previous design made this a centred hero block, which
     * gave the device's own name more visual weight than anything the user came to do.
     */
    static LinearLayout identityRow(Context c, Glyph.Kind kind, String name, String state) {
        LinearLayout row = cardBox(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(glyphInCircle(c, kind, onAccent(c), accentFill(c), 46));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(c, 16);
        col.setLayoutParams(lp);
        col.addView(text(c, name, 16, textColor(c), true));
        col.addView(text(c, state, 14, textMuted(c), false));
        row.addView(col);
        return row;
    }

    /** A status card: one glyph in a sunk circle, with a line under it. */
    static LinearLayout statusCard(Context c, Glyph.Kind kind, String label) {
        LinearLayout box = cardBox(c);
        box.setGravity(Gravity.CENTER);
        int p = dp(c, 26);
        box.setPadding(p, p, p, p);

        box.addView(glyphInCircle(c, kind, accent(c), surfaceSunk(c), 76));

        TextView t = text(c, label, 15, textColor(c), false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(c, 16), 0, 0);
        box.addView(t);
        return box;
    }

    /**
     * A device tile: a large circular glyph with the name and kind beneath.
     *
     * Quick Share shows devices as tiles rather than list rows, which is why a peer
     * there reads as a thing you tap and a list row reads as a setting.
     */
    static LinearLayout deviceTile(Context c, Glyph.Kind glyph, String name, String kind) {
        LinearLayout tile = new LinearLayout(c);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = dp(c, 8);
        tile.setPadding(p, p, p, p);

        tile.addView(glyphInCircle(c, glyph, onAccent(c), accentFill(c), 68));

        TextView n = text(c, name, 14, textColor(c), false);
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, dp(c, 10), 0, 0);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(n);

        // A BADGE, not a caption. Which protocol reached a device is not decoration:
        // an Apple device found over AirDrop cannot be sent to over Quick Share, so the
        // protocol is part of what the person is choosing when they tap a tile. Faint
        // grey text read as a subtitle nobody had to look at.
        LinearLayout badgeRow = new LinearLayout(c);
        badgeRow.setGravity(Gravity.CENTER);
        badgeRow.setPadding(0, dp(c, 4), 0, 0);
        badgeRow.addView(badge(c, kind));
        tile.addView(badgeRow);
        return tile;
    }

    /** A small pill naming a protocol. */
    static TextView badge(Context c, String label) {
        TextView b = text(c, label, 10, textMuted(c), true);
        b.setAllCaps(true);
        b.setLetterSpacing(0.06f);
        int px = dp(c, 7), py = dp(c, 2);
        b.setPadding(px, py, px, py);
        b.setBackground(card(c, surfaceSunk(c), ruleColor(c), 999));
        return b;
    }

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
