package com.wbeam.ui;

import android.view.View;
import android.widget.TextView;

import java.util.Locale;

public final class MainActivityRuntimeStateView {
    private MainActivityRuntimeStateView() {
    }

    public static void applyDebugOverlayVisibility(
            boolean debugBuild,
            boolean visible,
            View debugInfoPanel,
            View perfHudPanel
    ) {
        if (!debugBuild) {
            return;
        }
        if (debugInfoPanel != null) {
            debugInfoPanel.setVisibility(View.GONE);
        }
        if (perfHudPanel != null) {
            perfHudPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) {
                perfHudPanel.setAlpha(0.96f);
            }
        }
    }

    @SuppressWarnings("java:S107")
    public static void refreshDebugOverlayText(
            boolean debugBuild,
            TextView debugInfoText,
            View debugInfoPanel,
            String lastUiState,
            String daemonHostName,
            String daemonStateUi,
            double latestTargetFps,
            double latestPresentFps,
            int selectedFps,
            String lastStatsLine,
            String lastHudCompactLine
    ) {
        if (!debugBuild || debugInfoText == null || debugInfoPanel == null) {
            return;
        }
        debugInfoText.setText(MainActivityDebugInfoFormatter.buildDebugOverlayText(
                lastUiState,
                daemonHostName,
                daemonStateUi,
                latestTargetFps,
                latestPresentFps,
                selectedFps,
                lastStatsLine,
                lastHudCompactLine
        ));
    }

    @SuppressWarnings("java:S107")
    public static String effectiveDaemonState(
            String rawState,
            double presentFps,
            long streamUptimeSec,
            long frameOutHost
    ) {
        String normalized = rawState == null ? "IDLE" : rawState.toUpperCase(Locale.US);
        if (!"STREAMING".equals(normalized)) {
            return normalized;
        }
        boolean flowing = presentFps >= 1.0 || streamUptimeSec > 0 || frameOutHost > 0;
        return flowing ? "STREAMING" : "RECONNECTING";
    }
}
