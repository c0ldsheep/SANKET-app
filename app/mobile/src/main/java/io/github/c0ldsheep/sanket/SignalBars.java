package io.github.c0ldsheep.sanket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Four signal bars, like the status bar's, drawn to match the 24 dp icons beside them. */
public final class SignalBars extends View {
    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int bars;

    public SignalBars(Context context, AttributeSet attrs) {
        super(context, attrs);
        on.setColor(context.getColor(R.color.text));
        off.setColor(context.getColor(R.color.outline));
    }

    void setBars(int n) {
        int v = Math.max(0, Math.min(4, n));
        if (v == bars) return;
        bars = v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float inset = w * 0.125f;
        float gap = w * 0.07f;
        float barW = (w - 2f * inset - 3f * gap) / 4f;
        float corner = barW * 0.3f;
        float tallest = h - 2f * inset;
        for (int i = 0; i < 4; i++) {
            float left = inset + i * (barW + gap);
            float height = tallest * (i + 1) / 4f;
            rect.set(left, h - inset - height, left + barW, h - inset);
            canvas.drawRoundRect(rect, corner, corner, i < bars ? on : off);
        }
    }
}
