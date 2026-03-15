package com.wbeam.ui;

import android.widget.SeekBar;
import android.widget.Spinner;

public final class SettingsSelectionReader {
    private SettingsSelectionReader() {}

    public static int resolutionScalePercent(SeekBar resolutionSeek) {
        return 50 + safeProgress(resolutionSeek);
    }

    public static int selectedFps(SeekBar fpsSeek) {
        return 24 + safeProgress(fpsSeek);
    }

    public static int selectedBitrateMbps(SeekBar bitrateSeek) {
        return 5 + safeProgress(bitrateSeek);
    }

    public static String selectedItem(Spinner spinner, String fallback) {
        if (spinner == null || spinner.getSelectedItem() == null) {
            return fallback;
        }
        return String.valueOf(spinner.getSelectedItem());
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int safeProgress(SeekBar seekBar) {
        return seekBar == null ? 0 : seekBar.getProgress();
    }
}
