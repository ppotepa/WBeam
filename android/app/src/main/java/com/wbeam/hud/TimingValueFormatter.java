package com.wbeam.hud;

import java.util.Locale;

/** Presentation for measured timings; NaN denotes an unavailable measurement. */
public final class TimingValueFormatter {
    private TimingValueFormatter() {}

    public static boolean isAvailable(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0.0;
    }

    public static String milliseconds(double value, int decimals) {
        if (!isAvailable(value)) {
            return "unavailable";
        }
        return String.format(Locale.US, "%1$." + Math.max(0, Math.min(3, decimals)) + "f ms", value);
    }
}
