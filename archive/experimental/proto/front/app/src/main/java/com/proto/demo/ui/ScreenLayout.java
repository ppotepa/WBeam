package com.proto.demo.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Single responsibility: construct and own the fullscreen SurfaceView layout
 * with a small status text overlay.
 *
 * Usage: pass {@link #root} to {@code Activity.setContentView()}.
 */
public class ScreenLayout {

    public final FrameLayout root;
    public final SurfaceView surfaceView;
    public final TextView    statusView;

    public ScreenLayout(Context ctx) {
        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(ctx);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(ctx);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(11f);
        statusView.setPadding(10, 6, 10, 6);
        statusView.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        statusView.setBackgroundColor(0x88000000);
        root.addView(statusView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT));
    }
}
