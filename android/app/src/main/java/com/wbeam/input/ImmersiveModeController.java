package com.wbeam.input;

import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Controls fullscreen and immersive system-bars behavior.
 */
public final class ImmersiveModeController {
    private final AppCompatActivity activity;
    private final View rootLayout;
    private boolean fullscreen = false;

    public ImmersiveModeController(AppCompatActivity activity, View rootLayout) {
        this.activity = activity;
        this.rootLayout = rootLayout;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void setFullscreen(
            boolean enable,
            boolean debugBuild,
            View topBar,
            View statusPanel,
            Runnable onEnterFullscreen
    ) {
        fullscreen = enable;

        if (enable) {
            if (topBar != null) {
                topBar.setVisibility(View.GONE);
            }
            if (statusPanel != null) {
                statusPanel.setVisibility(View.GONE);
            }
            if (onEnterFullscreen != null) {
                onEnterFullscreen.run();
            }
            enforceImmersiveModeIfNeeded();
            return;
        }

        if (!debugBuild) {
            if (topBar != null) {
                topBar.setVisibility(View.VISIBLE);
            }
            if (statusPanel != null) {
                statusPanel.setVisibility(View.VISIBLE);
            }
        }

        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), true);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    public void enforceImmersiveModeIfNeeded() {
        if (!fullscreen) {
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        if (controller != null) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
            controller.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    public void setScreenAlwaysOn(boolean enable) {
        if (enable) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (rootLayout != null) {
            rootLayout.setKeepScreenOn(enable);
        }
    }
}
