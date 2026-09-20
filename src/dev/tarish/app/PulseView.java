package dev.tarish.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A "listening" indicator: a solid core dot with rings that expand and fade out of it,
 * the way a radar or a broadcast is drawn everywhere else.
 *
 * <p>The identity strip used a static {@link Ui#dot} that turned jade when discoverable.
 * A phone that is <em>waiting to receive</em> is doing something — holding the radio up,
 * advertising, keeping the screen awake — and a motionless dot said the opposite. The
 * rings are the one honest way to show "this is live and reaching outward" without a
 * label, and they run only while live so an idle device is visibly still.
 *
 * <p>Drawn rather than themed for the same reason as {@link ProgressBarView}: this app
 * carries no resource directory, and a dozen lines of canvas give exact control over the
 * core size, the ring count and the colour that the framework's own views do not.
 */
final class PulseView extends View {

    /** Two rings, half a cycle apart, so there is always one mid-flight — no dead beat. */
    private static final int RINGS = 2;
    private static final long PERIOD_MS = 1800L;

    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean live;
    private int color = Color.GRAY;
    /** 0..1, the phase of the first ring; the second trails it by half. */
    private float phase;
    private ValueAnimator animator;

    PulseView(Context c) {
        super(c);
        ringPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Turn the pulse on or off and set its colour.
     *
     * @param on    whether to animate rings (waiting to receive) or sit as a still dot
     * @param color core and ring colour — jade when live, faint otherwise
     */
    void setLive(boolean on, int color) {
        this.color = color;
        if (on == live) {
            invalidate();   // colour may still have changed
            return;
        }
        live = on;
        if (live) {
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
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(PERIOD_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        phase = 0f;
    }

    // The animator holds this view; let it go when the view does, and pick it back up if
    // the view returns while still live (a strip rebuild detaches and reattaches).
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (live) {
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
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxR = Math.min(cx, cy);
        // Core diameter ~8dp regardless of the view box the rings need to breathe in.
        float coreR = Math.min(maxR, Ui.dp(getContext(), 4));

        if (live) {
            int base = Color.alpha(color);
            for (int i = 0; i < RINGS; i++) {
                float p = (phase + i / (float) RINGS) % 1f;
                float r = coreR + p * (maxR - coreR);
                int alpha = (int) (base * (1f - p) * 0.5f);
                ringPaint.setColor(Color.argb(alpha,
                        Color.red(color), Color.green(color), Color.blue(color)));
                canvas.drawCircle(cx, cy, r, ringPaint);
            }
        }

        corePaint.setColor(color);
        canvas.drawCircle(cx, cy, coreR, corePaint);
    }
}
