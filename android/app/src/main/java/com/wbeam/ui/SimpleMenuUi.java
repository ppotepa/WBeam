package com.wbeam.ui;

import android.widget.Button;

/**
 * Helper methods for simple-mode menu state and button visuals.
 */
public final class SimpleMenuUi {
    private static final String RAW_PNG = RAW_PNG;

    private SimpleMenuUi() {}

    public static String modeFromEncoder(String selectedEncoder, String preferredVideo) {
        return RAW_PNG.equals(selectedEncoder) ? RAW_PNG : preferredVideo;
    }

    public static String encoderFromMode(String simpleMode, String preferredVideo) {
        return RAW_PNG.equals(simpleMode) ? RAW_PNG : preferredVideo;
    }

    public static int clampSimpleFps(int fps) {
        return Math.max(30, Math.min(144, fps));
    }

    public static void applyModeButtons(Button modePrimary, Button modeRaw, String preferredVideo, String simpleMode) {
        setSelected(modePrimary, preferredVideo.equals(simpleMode));
        setSelected(modeRaw, RAW_PNG.equals(simpleMode));
    }

    public static void applyFpsButtons(
            Button fps30,
            Button fps45,
            Button fps60,
            Button fps90,
            Button fps120,
            Button fps144,
            int simpleFps
    ) {
        setSelected(fps30, simpleFps == 30);
        setSelected(fps45, simpleFps == 45);
        setSelected(fps60, simpleFps == 60);
        setSelected(fps90, simpleFps == 90);
        setSelected(fps120, simpleFps == 120);
        setSelected(fps144, simpleFps == 144);
    }

    private static void setSelected(Button button, boolean selected) {
        if (button == null) {
            return;
        }
        button.setSelected(selected);
        button.setAlpha(selected ? 1f : 0.75f);
    }
}
