package dev.barq.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A rounded determinate progress bar.
 *
 * <p>The framework's own ProgressBar carries a theme that does not match this screen and
 * cannot be restyled without a resource directory, which this app deliberately does not
 * have. Drawing it is a dozen lines and gives exact control over the corner radius and
 * the track colour.
 */
final class ProgressBarView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** 0..1, clamped. Indeterminate transfers sit at 0 rather than lying about progress. */
    private float fraction;

    ProgressBarView(Context c) {
        super(c);
        track.setColor(Ui.SURFACE_EDGE);
        fill.setColor(Ui.ACCENT);
    }

    void setFraction(float f) {
        float clamped = Math.max(0f, Math.min(1f, f));
        if (Math.abs(clamped - fraction) < 0.001f) {
            return;   // avoid redrawing on every byte
        }
        fraction = clamped;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float r = h / 2f;
        rect.set(0, 0, getWidth(), h);
        canvas.drawRoundRect(rect, r, r, track);
        if (fraction <= 0f) {
            return;
        }
        // Never narrower than the cap diameter, or a tiny percentage renders as a
        // lopsided sliver rather than a small bar.
        float w = Math.max(h, getWidth() * fraction);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, fill);
    }
}
