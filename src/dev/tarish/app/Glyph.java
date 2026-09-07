package dev.tarish.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The icon set, drawn rather than typed.
 *
 * <p>An earlier version used Unicode characters — ⚡ ⇄ ⤓ — and they betrayed the whole
 * screen: the bolt rendered as a yellow emoji while the arrows came out as hairlines,
 * so nothing shared a weight or a colour. The font decides how those look, not us.
 *
 * <p>These are a handful of paths. Real vector drawables would need a resource
 * directory this app deliberately does not have, and for six icons a canvas is less
 * machinery than an XML pipeline.
 */
final class Glyph extends View {

    enum Kind { SWAP, DOWNLOAD, UPLOAD, LAPTOP, CHECK, GEAR }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Kind kind;

    Glyph(Context c, Kind kind, int color) {
        super(c);
        this.kind = kind;
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    void setColor(int color) {
        fill.setColor(color);
        stroke.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Everything is expressed as a fraction of the shorter side, so one drawing
        // works at any size and density.
        float s = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        stroke.setStrokeWidth(s * 0.085f);

        switch (kind) {
            case SWAP: swap(canvas, cx, cy, s); break;
            case DOWNLOAD: arrow(canvas, cx, cy, s, true); break;
            case UPLOAD: arrow(canvas, cx, cy, s, false); break;
            case LAPTOP: laptop(canvas, cx, cy, s); break;
            case CHECK: check(canvas, cx, cy, s); break;
            case GEAR: gear(canvas, cx, cy, s); break;
        }
    }

    /**
     * A ring with eight teeth and a hollow centre.
     *
     * Teeth are drawn as short radial strokes rather than as a toothed path: at the
     * ~22dp this is shown at, a filled cog turns into a blob, whereas strokes stay
     * legible and match the weight of every other glyph here.
     */
    private void gear(Canvas c, float cx, float cy, float s) {
        float r = s * 0.20f;
        c.drawCircle(cx, cy, r, stroke);
        float inner = r * 1.15f, outer = r * 1.55f;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4.0;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            c.drawLine(cx + dx * inner, cy + dy * inner,
                       cx + dx * outer, cy + dy * outer, stroke);
        }
    }

    /** Two arrows passing each other: the transfer mark. */
    private void swap(Canvas c, float cx, float cy, float s) {
        float w = s * 0.26f, gap = s * 0.10f, head = s * 0.075f;
        c.drawLine(cx - w, cy - gap, cx + w, cy - gap, stroke);
        c.drawLine(cx + w - head, cy - gap - head, cx + w, cy - gap, stroke);
        c.drawLine(cx + w - head, cy - gap + head, cx + w, cy - gap, stroke);
        c.drawLine(cx + w, cy + gap, cx - w, cy + gap, stroke);
        c.drawLine(cx - w + head, cy + gap - head, cx - w, cy + gap, stroke);
        c.drawLine(cx - w + head, cy + gap + head, cx - w, cy + gap, stroke);
    }

    /** A stem with a head, plus the tray line that reads as "to/from this device". */
    private void arrow(Canvas c, float cx, float cy, float s, boolean down) {
        float h = s * 0.24f, head = s * 0.11f;
        float top = cy - h, bottom = cy + h * 0.55f;
        c.drawLine(cx, top, cx, bottom, stroke);
        if (down) {
            c.drawLine(cx - head, bottom - head, cx, bottom, stroke);
            c.drawLine(cx + head, bottom - head, cx, bottom, stroke);
        } else {
            c.drawLine(cx - head, top + head, cx, top, stroke);
            c.drawLine(cx + head, top + head, cx, top, stroke);
        }
        float tray = s * 0.26f;
        c.drawLine(cx - tray, cy + h * 0.95f, cx + tray, cy + h * 0.95f, stroke);
    }

    private void laptop(Canvas c, float cx, float cy, float s) {
        float w = s * 0.24f, h = s * 0.17f;
        c.drawRoundRect(cx - w, cy - h * 1.35f, cx + w, cy + h * 0.45f,
                        s * 0.03f, s * 0.03f, stroke);
        float base = s * 0.33f;
        c.drawLine(cx - base, cy + h * 0.95f, cx + base, cy + h * 0.95f, stroke);
    }

    private void check(Canvas c, float cx, float cy, float s) {
        float w = s * 0.18f;
        c.drawLine(cx - w, cy, cx - w * 0.2f, cy + w * 0.8f, stroke);
        c.drawLine(cx - w * 0.2f, cy + w * 0.8f, cx + w, cy - w * 0.7f, stroke);
    }
}
