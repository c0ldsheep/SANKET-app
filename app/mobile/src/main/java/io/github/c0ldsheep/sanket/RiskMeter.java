package io.github.c0ldsheep.sanket;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The chance of losing signal as a bar, with a tick at 70%, where SANKET acts. The bar eases to
 * each new value; with animations turned off in the system settings it jumps there instead.
 */
public final class RiskMeter extends View {
    private static final float ACT_AT = 0.7f;
    private static final long EASE_MS = 300L;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float value = -1f;
    private float shown;
    private ValueAnimator animator;

    public RiskMeter(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        track.setColor(context.getColor(R.color.surface_alt));
        fill.setColor(context.getColor(R.color.accent));
        tick.setColor(context.getColor(R.color.text_2));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void setColors(int fillColor, int trackColor) {
        if (fill.getColor() == fillColor && track.getColor() == trackColor) return;
        fill.setColor(fillColor);
        track.setColor(trackColor);
        invalidate();
    }

    void setValue(float v) {
        float target = Math.max(0f, Math.min(1f, v));
        if (target == value) return;
        value = target;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(shown, target);
        animator.setDuration(EASE_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float bar = 8f * density;
        float top = (h - bar) / 2f;
        float r = bar / 2f;
        rect.set(0f, top, w, top + bar);
        canvas.drawRoundRect(rect, r, r, track);
        if (shown > 0f) {
            rect.set(0f, top, Math.max(bar, w * shown), top + bar);
            canvas.drawRoundRect(rect, r, r, fill);
        }
        float x = w * ACT_AT;
        canvas.drawRect(x - density, 0f, x + density, h, tick);
    }
}
