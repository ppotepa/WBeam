package com.wbeam.input;

import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;

/**
 * Controls local cursor overlay behavior and its UI toggle button.
 */
public final class CursorOverlayController {
    private final View overlayView;
    private final Button toggleButton;
    private boolean overlayEnabled = true;

    public CursorOverlayController(View overlayView, Button toggleButton) {
        this.overlayView = overlayView;
        this.toggleButton = toggleButton;
    }

    public boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    public void resetEnabledDefault() {
        overlayEnabled = true;
    }

    public void toggleForCursorMode(String cursorMode) {
        if (!"hidden".equals(cursorMode)) {
            overlayEnabled = false;
            hideOverlay();
            return;
        }
        overlayEnabled = !overlayEnabled;
        if (!overlayEnabled) {
            hideOverlay();
        }
    }

    public void applyPolicy(String cursorMode) {
        boolean allowLocalOverlay = "hidden".equals(cursorMode);
        if (!allowLocalOverlay && overlayEnabled) {
            overlayEnabled = false;
            hideOverlay();
        }
        if (toggleButton != null) {
            toggleButton.setEnabled(allowLocalOverlay);
            toggleButton.setAlpha(allowLocalOverlay ? 1.0f : 0.45f);
            if (allowLocalOverlay) {
                toggleButton.setText(overlayEnabled ? "Local Cursor Overlay ON" : "Local Cursor Overlay OFF");
            } else {
                toggleButton.setText("Local Cursor Overlay N/A (cursor hidden required)");
            }
        }
    }

    public void updateOverlay(float x, float y, int action) {
        if (overlayView == null || !overlayEnabled) {
            return;
        }
        overlayView.setX(x - (overlayView.getWidth() / 2f));
        overlayView.setY(y - (overlayView.getHeight() / 2f));
        overlayView.setVisibility(View.VISIBLE);

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            overlayView.removeCallbacks(this::hideOverlay);
            overlayView.postDelayed(this::hideOverlay, 400);
        }
    }

    public void hideOverlay() {
        if (overlayView != null) {
            overlayView.setVisibility(View.GONE);
        }
    }
}
