package com.wbeam.startup;

import android.os.Handler;
import android.view.View;

/**
 * Controls startup/preflight overlay visibility and animation pulse.
 */
public final class StartupOverlayController {
    private static final long PULSE_INTERVAL_MS = 400L;
    private static final long FADE_OUT_MS = 180L;

    public interface TickListener {
        void onTick(int animTick);
    }

    private final Handler uiHandler;
    private final View overlayView;
    private TickListener tickListener;
    private int animTick = 0;

    private final Runnable pulseTask = new Runnable() {
        @Override
        public void run() {
            animTick = (animTick + 1) % 4;
            if (tickListener != null) {
                tickListener.onTick(animTick);
            }
            uiHandler.postDelayed(this, PULSE_INTERVAL_MS);
        }
    };

    public StartupOverlayController(Handler uiHandler, View overlayView) {
        this.uiHandler = uiHandler;
        this.overlayView = overlayView;
    }

    public void setTickListener(TickListener listener) {
        this.tickListener = listener;
    }

    public void startPulse() {
        uiHandler.removeCallbacks(pulseTask);
        uiHandler.post(pulseTask);
    }

    public void stopPulse() {
        uiHandler.removeCallbacks(pulseTask);
    }

    public void setVisible(boolean visible) {
        if (overlayView == null) {
            return;
        }
        if (visible) {
            overlayView.setVisibility(View.VISIBLE);
            overlayView.setAlpha(1f);
            return;
        }
        overlayView.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .withEndAction(() -> {
                    overlayView.setVisibility(View.GONE);
                    overlayView.setAlpha(1f);
                })
                .start();
    }
}
