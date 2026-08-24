package dev.barq.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The pulse that says "this device is findable".
 *
 * <p>Three rings expand outward on a staggered loop and fade as they go, with a filled
 * disc at the centre standing for this device. It is the one moving thing on the screen,
 * so it carries the whole message: pulsing means discoverable, still means not.
 *
 * <p>Drawn rather than assembled from drawables because the app has no resource
 * directory and no AndroidX — the framework canvas gives a better result here than a
 * stack of shape XML would, and costs nothing to ship.
 */
final class RadarView extends View {

    /** How long one ring takes to travel from the centre to the edge. */
    private static final long PERIOD_MS = 2600;

    private static final int RINGS = 3;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator animator;
    private float phase;
    private boolean active;

    private int accent = Color.parseColor("#7FB2FF");

    RadarView(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        core.setStyle(Paint.Style.FILL);
        bolt.setStyle(Paint.Style.FILL);
        bolt.setColor(Color.parseColor("#0B0D12"));
    }

    /** Pulsing while discoverable, still and dimmed while not. */
    void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        if (active) {
            start();
        } else {
            stop();
        }
        invalidate();
    }

    void setAccent(int color) {
        this.accent = color;
        invalidate();
    }

    private void start() {
        stop();
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
    }

    @Override
    protected void onDetachedFromWindow() {
        // A ValueAnimator left running holds the view and redraws forever. This screen
        // is the visibility control, so leaking an animation would also mean the phone
        // looks discoverable when it is not.
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxR = Math.min(cx, cy);
        float coreR = maxR * 0.20f;

        if (active) {
            for (int i = 0; i < RINGS; i++) {
                // Stagger the rings evenly through one period so the pulse is
                // continuous rather than arriving in bursts.
                float p = (phase + (float) i / RINGS) % 1f;
                float r = coreR + p * (maxR - coreR);
                // Fade out toward the edge, and ease the very start so a ring does not
                // pop into existence at full strength on top of the core.
                int alpha = (int) (150 * (1f - p) * Math.min(1f, p * 6f));
                ring.setColor(accent);
                ring.setAlpha(alpha);
                ring.setStrokeWidth(dp(1.5f) * (1f - p * 0.4f));
                canvas.drawCircle(cx, cy, r, ring);
            }
        }

        // Soft halo under the core so it sits in the pulse rather than on top of it.
        glow.setShader(new RadialGradient(
                cx, cy, coreR * 2.4f,
                withAlpha(accent, active ? 60 : 18), Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, coreR * 2.4f, glow);

        core.setColor(active ? accent : Color.parseColor("#3A3F4A"));
        canvas.drawCircle(cx, cy, coreR, core);
        drawBolt(canvas, cx, cy, coreR);
    }

    /**
     * A lightning bolt in the core — Barq is برق, lightning.
     *
     * Drawn from proportions of the core radius so it scales with the view instead of
     * being pinned to one density.
     */
    private void drawBolt(Canvas canvas, float cx, float cy, float r) {
        android.graphics.Path p = new android.graphics.Path();
        float w = r * 0.44f;
        float h = r * 0.86f;
        p.moveTo(cx + w * 0.30f, cy - h);
        p.lineTo(cx - w, cy + h * 0.12f);
        p.lineTo(cx - w * 0.10f, cy + h * 0.12f);
        p.lineTo(cx - w * 0.30f, cy + h);
        p.lineTo(cx + w, cy - h * 0.16f);
        p.lineTo(cx + w * 0.08f, cy - h * 0.16f);
        p.close();
        canvas.drawPath(p, bolt);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
