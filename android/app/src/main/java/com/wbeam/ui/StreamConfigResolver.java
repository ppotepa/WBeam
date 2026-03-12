package com.wbeam.ui;

/**
 * Resolves outgoing stream dimensions and limits from UI selections.
 */
public final class StreamConfigResolver {
    private StreamConfigResolver() {}

    public static final class Resolved {
        public final int width;
        public final int height;
        public final int fps;
        public final int bitrateMbps;

        public Resolved(int width, int height, int fps, int bitrateMbps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrateMbps = bitrateMbps;
        }
    }

    public static int[] computeScaledSize(String profile, int resScalePercent) {
        int baseW = "ultra".equals(profile) ? 2560 : 1920;
        int baseH = "ultra".equals(profile) ? 1440 : 1080;
        int w = Math.max(640, (baseW * resScalePercent / 100) & ~1);
        int h = Math.max(360, (baseH * resScalePercent / 100) & ~1);
        return new int[]{w, h};
    }

    public static Resolved resolve(
            String profile,
            int resScalePercent,
            int selectedFps,
            int selectedBitrateMbps,
            boolean legacyAndroidDevice
    ) {
        int[] size = computeScaledSize(profile, resScalePercent);
        int width = size[0];
        int height = size[1];
        int fps = selectedFps;
        int bitrateMbps = selectedBitrateMbps;

        if (legacyAndroidDevice) {
            width = 640;
            height = 360;
            fps = Math.min(fps, 24);
            bitrateMbps = Math.min(bitrateMbps, 2);
        }
        return new Resolved(width, height, fps, bitrateMbps);
    }
}
