package com.wbeam.ui;

import android.widget.TextView;

public final class MainActivitySettingsPresenter {
    private MainActivitySettingsPresenter() {
    }

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
