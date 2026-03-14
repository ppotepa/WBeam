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

    @SuppressWarnings("java:S107")
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
                new MainActivitySettingsPresenter.DefaultSettingsInput()
                        .setProfileSpinner(profileSpinner)
                        .setEncoderSpinner(encoderSpinner)
                        .setCursorSpinner(cursorSpinner)
                        .setResolutionSeek(resolutionSeek)
                        .setFpsSeek(fpsSeek)
                        .setBitrateSeek(bitrateSeek)
                        .setProfileOptions(profileOptions)
                        .setEncoderOptions(encoderOptions)
                        .setCursorOptions(cursorOptions)
                        .setDefaultProfile(defaultProfile)
                        .setPreferredVideo(preferredVideo)
                        .setDefaultCursorMode(defaultCursorMode)
                        .setDefaultResScale(defaultResScale)
                        .setDefaultFps(defaultFps)
                        .setDefaultBitrateMbps(defaultBitrateMbps)
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
