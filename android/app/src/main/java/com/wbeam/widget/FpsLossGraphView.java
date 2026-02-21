package com.wbeam.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class FpsLossGraphView extends View {
    private static final int COLOR_GREEN = Color.parseColor("#22C55E");
    private static final int COLOR_ORANGE = Color.parseColor("#F59E0B");
    private static final int COLOR_RED = Color.parseColor("#EF4444");
    private static final int COLOR_GRID = Color.parseColor("#2E3A4F");
    private static final int COLOR_BG = Color.parseColor("#3A0F172A");

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] levelHistory = new float[120];
    private float[] lossHistory = new float[120];
    private int sampleCount = 0;
    private int writeIndex = 0;

    public FpsLossGraphView(Context context) {
        super(context);
        init();
    }

    public FpsLossGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FpsLossGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(COLOR_GRID);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(COLOR_BG);

        barPaint.setStyle(Paint.Style.FILL);
    }

    public void setCapacity(int capacity) {
        int clamped = Math.max(16, capacity);
        if (clamped == levelHistory.length) {
            return;
        }
        levelHistory = new float[clamped];
        lossHistory = new float[clamped];
        sampleCount = 0;
        writeIndex = 0;
        invalidate();
    }

    public void addSample(double targetFps, double presentFps) {
        if (!Double.isFinite(targetFps) || targetFps <= 0.0 || !Double.isFinite(presentFps)) {
            return;
        }

        float levelRatio = (float) Math.max(0.0, Math.min(1.0, presentFps / targetFps));
        float lossPct = (float) Math.max(0.0, ((targetFps - presentFps) / targetFps) * 100.0);

        levelHistory[writeIndex] = levelRatio;
        lossHistory[writeIndex] = lossPct;
        writeIndex = (writeIndex + 1) % levelHistory.length;
        if (sampleCount < levelHistory.length) {
            sampleCount++;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        canvas.drawRect(0, 0, width, height, bgPaint);

        float y50 = height * 0.5f;
        float y80 = height * 0.2f;
        canvas.drawLine(0, y50, width, y50, gridPaint);
        canvas.drawLine(0, y80, width, y80, gridPaint);

        if (sampleCount <= 0) {
            return;
        }

        float barWidth = (float) width / levelHistory.length;
        int start = sampleCount == levelHistory.length ? writeIndex : 0;

        for (int i = 0; i < sampleCount; i++) {
            int idx = (start + i) % levelHistory.length;
            float level = levelHistory[idx];
            float loss = lossHistory[idx];

            if (loss > 50.0f) {
                barPaint.setColor(COLOR_RED);
            } else if (loss > 10.0f) {
                barPaint.setColor(COLOR_ORANGE);
            } else {
                barPaint.setColor(COLOR_GREEN);
            }

            float left = i * barWidth;
            float right = Math.max(left + 1f, left + barWidth - 1f);
            float top = height - (level * height);
            canvas.drawRect(left, top, right, height, barPaint);
        }
    }
}