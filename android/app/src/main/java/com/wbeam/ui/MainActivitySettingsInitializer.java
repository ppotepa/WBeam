package com.wbeam.ui;

import android.widget.SeekBar;
import android.widget.Spinner;

import com.wbeam.input.CursorOverlayController;

public final class MainActivitySettingsInitializer {
    public interface Hooks {
        void enforceCursorOverlayPolicy(boolean persist);
        void refreshSettingsUi(boolean includeSimpleMenuButtons);
    }

    private MainActivitySettingsInitializer() {
    }

    public static void loadDefaults(
            Spinner profileSpinner,
            Spinner encoderSpinner,
            Spinner cursorSpinner,
            SeekBar resolutionSeek,
            SeekBar fpsSeek,
            SeekBar bitrateSeek,
            String[] profileOptions,
            String[] encoderOptions,
            String[] cursorOptions,
            String defaultProfile,
            String preferredVideo,
            String defaultCursorMode,
            int defaultResScale,
            int defaultFps,
            int defaultBitrateMbps,
            CursorOverlayController cursorOverlayController,
            MainActivitySimpleMenuCoordinator.State simpleMenuState,
            Hooks hooks
    ) {
        MainActivitySettingsPresenter.applyDefaultSettings(
                profileSpinner,
                encoderSpinner,
                cursorSpinner,
                resolutionSeek,
                fpsSeek,
                bitrateSeek,
                profileOptions,
                encoderOptions,
                cursorOptions,
                defaultProfile,
                preferredVideo,
                defaultCursorMode,
                defaultResScale,
                defaultFps,
                defaultBitrateMbps
        );
        if (cursorOverlayController != null) {
            cursorOverlayController.resetEnabledDefault();
        }
        hooks.enforceCursorOverlayPolicy(false);
        simpleMenuState.setMode(preferredVideo);
        simpleMenuState.setFps(defaultFps);
        simpleMenuState.setVisible(false);
        hooks.refreshSettingsUi(true);
    }
}
