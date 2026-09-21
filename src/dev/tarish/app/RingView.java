package dev.tarish.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A circular progress ring — a track plus an arc — determinate or spinning.
 *
 * <p>The transfer stage draws this around the file's icon, the way AirDrop wraps a
 * progress ring around what is crossing. Determinate for real byte progress; indeterminate
 * (a sweeping arc) for the handshake before any bytes flow. Drawn rather than themed for
 * the same reason as the rest of this app's views: exact control, no resource directory.
 */
final class RingView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float fraction;
    private boolean indeterminate;
    private float spin;
    private ValueAnimator animator;

    RingView(Context c) {
        super(c);
        float w = Ui.dp(c, 4);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(w);
        track.setColor(Ui.surfaceEdge(c));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(w);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(Ui.accent(c));
    }

    void setColor(int color) {
        arc.setColor(color);
        invalidate();
    }

    void setFraction(float f) {
        if (indeterminate) {
            setIndeterminate(false);
        }
        float cl = Math.max(0f, Math.min(1f, f));
        if (Math.abs(cl - fraction) < 0.001f) {
            return;
        }
        fraction = cl;
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
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(1000L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            spin = (float) a.getAnimatedValue();
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
    protected void onDraw(Canvas c) {
        float pad = arc.getStrokeWidth();
        oval.set(pad, pad, getWidth() - pad, getHeight() - pad);
        c.drawOval(oval, track);
        if (indeterminate) {
            c.drawArc(oval, spin, 90f, false, arc);
        } else if (fraction > 0f) {
            c.drawArc(oval, -90f, fraction * 360f, false, arc);
        }
    }
}
