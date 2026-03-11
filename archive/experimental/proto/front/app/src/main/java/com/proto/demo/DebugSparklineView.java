package com.proto.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class DebugSparklineView extends View {
    private static final int HISTORY = 60;

    private final float[] values = new float[HISTORY];
    private final float[] secondaryValues = new float[HISTORY];
    private int count = 0;
    private int head = 0;
    private float fixedMax = 0f;
    private float adaptiveMax = 1f;
    private boolean hasSecondary = false;

    private final Paint bgPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint secondaryLinePaint = new Paint();
    private final Paint axisPaint = new Paint();

    public DebugSparklineView(Context context) {
        super(context);
        init();
    }

    public DebugSparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DebugSparklineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        bgPaint.setColor(Color.argb(80, 0, 0, 0));
        bgPaint.setStyle(Paint.Style.FILL);

        axisPaint.setColor(Color.argb(120, 255, 255, 255));
        axisPaint.setStrokeWidth(1f);

        linePaint.setColor(Color.argb(255, 102, 255, 102));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f);
        linePaint.setAntiAlias(true);

        secondaryLinePaint.setColor(Color.argb(255, 255, 153, 102));
        secondaryLinePaint.setStyle(Paint.Style.STROKE);
        secondaryLinePaint.setStrokeWidth(2f);
        secondaryLinePaint.setAntiAlias(true);
    }

    public void setLineColor(int color) {
        linePaint.setColor(color);
        invalidate();
    }

    public void setFixedMax(float max) {
        fixedMax = Math.max(0f, max);
    }

    public void setSecondaryLineColor(int color) {
        secondaryLinePaint.setColor(color);
        invalidate();
    }

    public void addValue(float value) {
        addPair(value, Float.NaN);
    }

    public void addPair(float primary, float secondary) {
        float p = sanitize(primary);
        float s = sanitize(secondary);
        boolean secondaryValid = !Float.isNaN(secondary) && !Float.isInfinite(secondary);

        values[head] = p;
        secondaryValues[head] = s;
        hasSecondary = hasSecondary || secondaryValid;

        head = (head + 1) % HISTORY;
        if (count < HISTORY) {
            count++;
        }
        invalidate();
    }

    private static float sanitize(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0f;
        }
        return Math.max(0f, value);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 2 || h <= 2) {
            return;
        }

        canvas.drawRect(0, 0, w, h, bgPaint);
        canvas.drawLine(0, h - 1, w, h - 1, axisPaint);

        if (count < 2) {
            return;
        }

        float max = fixedMax;
        if (max <= 0f) {
            float observedMax = 0f;
            for (int i = 0; i < count; i++) {
                int idx = (head - count + i + HISTORY) % HISTORY;
                if (values[idx] > observedMax) {
                    observedMax = values[idx];
                }
                if (hasSecondary && secondaryValues[idx] > observedMax) {
                    observedMax = secondaryValues[idx];
                }
            }
            if (observedMax < 1f) {
                observedMax = 1f;
            }
            if (observedMax > adaptiveMax) {
                adaptiveMax = observedMax;
            } else {
                float decayed = adaptiveMax * 0.96f;
                float floor = observedMax * 1.10f;
                adaptiveMax = Math.max(1f, Math.max(decayed, floor));
            }
            max = adaptiveMax;
        }

        float dx = (float) (w - 1) / (float) (count - 1);
        float prevX = 0f;
        float prevY = valueToY(values[(head - count + HISTORY) % HISTORY], h, max);

        for (int i = 1; i < count; i++) {
            int idx = (head - count + i + HISTORY) % HISTORY;
            float x = i * dx;
            float y = valueToY(values[idx], h, max);
            canvas.drawLine(prevX, prevY, x, y, linePaint);
            prevX = x;
            prevY = y;
        }

        if (!hasSecondary) {
            return;
        }

        float prev2X = 0f;
        float prev2Y = valueToY(secondaryValues[(head - count + HISTORY) % HISTORY], h, max);
        for (int i = 1; i < count; i++) {
            int idx = (head - count + i + HISTORY) % HISTORY;
            float x = i * dx;
            float y = valueToY(secondaryValues[idx], h, max);
            canvas.drawLine(prev2X, prev2Y, x, y, secondaryLinePaint);
            prev2X = x;
            prev2Y = y;
        }
    }

    private static float valueToY(float value, int h, float max) {
        float norm = value / max;
        if (norm < 0f) norm = 0f;
        if (norm > 1f) norm = 1f;
        return (h - 1) - norm * (h - 2);
    }
}
