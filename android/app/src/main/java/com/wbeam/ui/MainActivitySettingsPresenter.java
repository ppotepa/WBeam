package com.wbeam.ui;

import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

public final class MainActivitySettingsPresenter {
    private MainActivitySettingsPresenter() {
    }

    @SuppressWarnings("java:S107")
    public static void applySettingValueLabels(
            TextView resValueText,
            TextView fpsValueText,
            TextView bitrateValueText,
            int resolutionScalePercent,
            int fps,
            int bitrateMbps,
            int width,
            int height
    ) {
        resValueText.setText(SettingsUiSupport.resolutionValueLabel(
                resolutionScalePercent, width, height));
        fpsValueText.setText(SettingsUiSupport.fpsValueLabel(fps));
        bitrateValueText.setText(SettingsUiSupport.bitrateValueLabel(bitrateMbps));
    }

    @SuppressWarnings("java:S107")
    public static void applyDefaultSettings(
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
            int defaultBitrateMbps
    ) {
        SettingsUiSupport.setSpinnerSelection(profileSpinner, profileOptions, defaultProfile);
        SettingsUiSupport.setSpinnerSelection(encoderSpinner, encoderOptions, preferredVideo);
        SettingsUiSupport.setSpinnerSelection(cursorSpinner, cursorOptions, defaultCursorMode);
        resolutionSeek.setProgress(SettingsSelectionReader.clamp(defaultResScale, 50, 100) - 50);
        fpsSeek.setProgress(SettingsSelectionReader.clamp(defaultFps, 24, 144) - 24);
        bitrateSeek.setProgress(SettingsSelectionReader.clamp(defaultBitrateMbps, 5, 300) - 5);
    }

    public static void applySimpleMenuSettings(
            Spinner encoderSpinner,
            SeekBar fpsSeek,
            String[] encoderOptions,
            String preferredVideo,
            String simpleMode,
            int simpleFps
    ) {
        String selectedEncoder = SimpleMenuUi.encoderFromMode(simpleMode, preferredVideo);
        int selectedFps = SimpleMenuUi.clampSimpleFps(simpleFps);
        SettingsUiSupport.setSpinnerSelection(encoderSpinner, encoderOptions, selectedEncoder);
        fpsSeek.setProgress(SettingsSelectionReader.clamp(selectedFps, 24, 144) - 24);
    }

    public static String simpleMenuModeFromSelection(String selectedEncoder, String preferredVideo) {
        return SimpleMenuUi.modeFromEncoder(selectedEncoder, preferredVideo);
    }

    public static int simpleMenuFpsFromSelection(int selectedFps) {
        return SimpleMenuUi.clampSimpleFps(selectedFps);
    }

    @SuppressWarnings("java:S107")
    public static void applySimpleMenuButtons(
            Button simpleModePreferredButton,
            Button simpleModeRawButton,
            String preferredVideo,
            String simpleMode,
            Button simpleFps30Button,
            Button simpleFps45Button,
            Button simpleFps60Button,
            Button simpleFps90Button,
            Button simpleFps120Button,
            Button simpleFps144Button,
            int simpleFps
    ) {
        SimpleMenuUi.applyModeButtons(
                simpleModePreferredButton,
                simpleModeRawButton,
                preferredVideo,
                simpleMode
        );
        SimpleMenuUi.applyFpsButtons(
                simpleFps30Button,
                simpleFps45Button,
                simpleFps60Button,
                simpleFps90Button,
                simpleFps120Button,
                simpleFps144Button,
                simpleFps
        );
    }

    @SuppressWarnings("java:S107")
    public static String buildHostHint(
            boolean daemonReachable,
            String apiBase,
            String daemonHostName,
            String daemonStateUi,
            String daemonService,
            String selectedProfile,
            int width,
            int height,
            int fps,
            int bitrateMbps,
            String selectedEncoder,
            boolean intraOnlyEnabled,
            String cursorMode
    ) {
        return StatusTextFormatter.buildHostHintText(
                daemonReachable,
                apiBase,
                daemonHostName,
                daemonStateUi,
                daemonService,
                selectedProfile,
                width,
                height,
                fps,
                bitrateMbps,
                selectedEncoder,
                intraOnlyEnabled,
                cursorMode
        );
    }
}
