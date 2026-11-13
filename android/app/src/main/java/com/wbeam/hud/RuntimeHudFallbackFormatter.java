package com.wbeam.hud;

import java.util.Locale;

/**
 * Formats plain-text fallback payload for runtime HUD.
 */
public final class RuntimeHudFallbackFormatter {
    private RuntimeHudFallbackFormatter() {}

    @SuppressWarnings("java:S107")
    public static String buildText(
            boolean daemonReachable,
            double targetFps,
            double presentFps,
            double frametimeP95,
            double decodeP95,
            double renderP95,
            double e2eP95,
            int qT,
            int qD,
            int qR,
            int qTMax,
            int qDMax,
            int qRMax,
            int adaptiveLevel,
            String adaptiveAction,
            long drops,
            long bpHigh,
            long bpRecover,
            String reason
    ) {
        return String.format(
                Locale.US,
                "HUD %s%nfps %.0f/%.1f frame %s%ndec %s ren %s e2e %s%nq %d/%d/%d max %d/%d/%d%nadapt L%d %s%ndrops %d bp %d/%d%n%s",
                daemonReachable ? "LIVE" : "DEGRADED",
                targetFps,
                presentFps,
                TimingValueFormatter.milliseconds(frametimeP95, 2),
                TimingValueFormatter.milliseconds(decodeP95, 2),
                TimingValueFormatter.milliseconds(renderP95, 2),
                TimingValueFormatter.milliseconds(e2eP95, 2),
                qT,
                qD,
                qR,
                qTMax,
                qDMax,
                qRMax,
                adaptiveLevel,
                adaptiveAction,
                drops,
                bpHigh,
                bpRecover,
                reason == null || reason.isEmpty() ? "-" : reason
        );
    }
}
