package com.wbeam.ui;

import java.util.Locale;

public final class MainActivityDebugInfoFormatter {
    private MainActivityDebugInfoFormatter() {
    }

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
        return String.format(
                Locale.US,
                "DBG %s | host:%s | daemon:%s\nFPS %.0f/%.1f (loss %.0f%%)  thresholds: green <=20%% orange >20%% red >55%%\n%s\n%s",
                state,
                host,
                daemonStateUi,
                safeTarget,
                latestPresentFps,
                lossPct,
                lastStatsLine,
                lastHudCompactLine
        );
    }
}
