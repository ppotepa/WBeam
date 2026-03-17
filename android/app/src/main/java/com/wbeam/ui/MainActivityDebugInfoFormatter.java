package com.wbeam.ui;

import java.util.Locale;

public final class MainActivityDebugInfoFormatter {
    private MainActivityDebugInfoFormatter() {
    }

    @SuppressWarnings("java:S107")
    public static String buildDebugOverlayText(
            String lastUiState,
            String daemonHostName,
            String daemonStateUi,
            double latestTargetFps,
            double latestPresentFps,
            int selectedFps,
            String lastStatsLine,
            String lastHudCompactLine
    ) {
        String state = lastUiState == null ? "IDLE" : lastUiState.toUpperCase(Locale.US);
        String host = daemonHostName == null || daemonHostName.trim().isEmpty() ? "-" : daemonHostName;
        double safeTarget = latestTargetFps > 0.0 ? latestTargetFps : (double) selectedFps;
        double lossPct = Math.max(0.0, ((safeTarget - latestPresentFps) / safeTarget) * 100.0);
        StringBuilder text = new StringBuilder(224);
        text.append("DBG ")
                .append(state)
                .append(" | host:")
                .append(host)
                .append(" | daemon:")
                .append(daemonStateUi)
                .append('\n')
                .append("FPS ")
                .append(fmt0(safeTarget))
                .append('/')
                .append(fmt1(latestPresentFps))
                .append(" (loss ")
                .append(fmt0(lossPct))
                .append("%)  thresholds: green <=20% orange >20% red >55%")
                .append('\n')
                .append(lastStatsLine)
                .append('\n')
                .append(lastHudCompactLine);
        return text.toString();
    }

    private static String fmt0(double value) {
        return formatFixed(value, 0);
    }

    private static String fmt1(double value) {
        return formatFixed(value, 1);
    }

    private static String formatFixed(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        int safeDecimals = Math.max(0, Math.min(2, decimals));
        long factor = safeDecimals == 0 ? 1L : 10L;
        long rounded = Math.round(value * factor);
        long whole = Math.abs(rounded) / factor;
        long fraction = Math.abs(rounded) % factor;
        StringBuilder out = new StringBuilder();
        if (rounded < 0L) {
            out.append('-');
        }
        out.append(whole);
        if (safeDecimals == 0) {
            return out.toString();
        }
        out.append('.').append(fraction);
        return out.toString();
    }
}
