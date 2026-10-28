package com.wbeam.ui;

/** Simple UI presets; actual stream values are sent by StreamSessionController. */
public final class SettingsPresets {
    private SettingsPresets() {}

    public static final class Preset {
        public final int resolutionScalePercent;
        public final int fps; // <=0 preserves current FPS
        public final int bitrateMbps;
        public final boolean forceH264;

        private Preset(int resolutionScalePercent, int fps, int bitrateMbps, boolean forceH264) {
            this.resolutionScalePercent = resolutionScalePercent;
            this.fps = fps;
            this.bitrateMbps = bitrateMbps;
            this.forceH264 = forceH264;
        }
    }

    public static final Preset LOW_LATENCY = new Preset(75, 60, 10, true);
    public static final Preset SMOOTH = new Preset(100, 60, 25, true);
    public static final Preset QUALITY_LOW = new Preset(65, 0, 5, false);
    public static final Preset QUALITY_MEDIUM = new Preset(85, 0, 15, false);
    public static final Preset QUALITY_HIGH = new Preset(100, 0, 35, false);
}
