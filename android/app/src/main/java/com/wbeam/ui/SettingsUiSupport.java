package com.wbeam.ui;

import android.widget.Spinner;

/**
 * Shared helpers for settings form widgets and value labels.
 */
public final class SettingsUiSupport {
    private SettingsUiSupport() {}

    public static void setSpinnerSelection(Spinner spinner, String[] options, String value) {
        if (spinner == null || options == null || options.length == 0) {
            return;
        }
        int idx = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(value)) {
                idx = i;
                break;
            }
        }
        spinner.setSelection(idx, false);
    }

    public static String resolutionValueLabel(int scalePercent, int width, int height) {
        return scalePercent + "% (" + width + "x" + height + ")";
    }

    public static String fpsValueLabel(int fps) {
        return String.valueOf(fps);
    }

    public static String bitrateValueLabel(int bitrateMbps) {
        return bitrateMbps + " Mbps";
    }
}
