package dev.tarish.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A rounded progress bar, determinate or indeterminate.
 *
 * <p>The framework's own ProgressBar carries a theme that does not match this screen and
 * cannot be restyled without a resource directory, which this app deliberately does not
 * have. Drawing it is a couple dozen lines and gives exact control over the corner radius
 * and the track colour.
 *
 * <p>Indeterminate mode is for the phase before any bytes flow — the AirDrop rendezvous
 * and handshake. Sitting a determinate bar at 0 there read as stuck; a segment sliding
 * across says "working on it" without claiming progress that has not happened.
 */
final class ProgressBarView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** 0..1, clamped. */
    private float fraction;

    /** While true, ignore {@link #fraction} and slide a segment across the track. */
    private boolean indeterminate;
    /** 0..1 sweep position of the indeterminate segment's leading edge. */
    private float sweep;
    private ValueAnimator animator;

    ProgressBarView(Context c) {
        super(c);
        track.setColor(Ui.surfaceEdge(getContext()));
        fill.setColor(Ui.accent(getContext()));
    }

    void setFraction(float f) {
        if (indeterminate) {
            setIndeterminate(false);
        }
        float clamped = Math.max(0f, Math.min(1f, f));
        if (Math.abs(clamped - fraction) < 0.001f) {
            return;   // avoid redrawing on every byte
        }
        fraction = clamped;
        invalidate();
    }

    void setIndeterminate(boolean on) {
        if (on == indeterminate) {
            return;
        }
        indeterminate = on;
        if (on) {
            fraction = 0f;
            start();
        } else {
            stop();
        }
        invalidate();
    }

    private void start() {
        if (animator != null) {
            return;
        }
        // A little past 1 so the segment fully exits the right edge before it wraps.
        animator = ValueAnimator.ofFloat(0f, 1.35f);
        animator.setDuration(1100L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            sweep = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (indeterminate) {
            start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float r = h / 2f;
        float w = getWidth();
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, track);

        if (indeterminate) {
            float seg = w * 0.32f;               // segment width
            float lead = sweep * (w + seg);      // leading edge travels off the right
            float left = Math.max(0f, lead - seg);
            float right = Math.min(w, lead);
            if (right - left > 0.5f) {
                rect.set(left, 0, right, h);
                canvas.drawRoundRect(rect, r, r, fill);
            }
            return;
        }

        if (fraction <= 0f) {
            return;
        }
        // Never narrower than the cap diameter, or a tiny percentage renders as a
        // lopsided sliver rather than a small bar.
        float fw = Math.max(h, w * fraction);
        rect.set(0, 0, fw, h);
        canvas.drawRoundRect(rect, r, r, fill);
    }
}
