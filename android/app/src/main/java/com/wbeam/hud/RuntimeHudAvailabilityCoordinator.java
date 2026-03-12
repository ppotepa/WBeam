package com.wbeam.hud;

import org.json.JSONObject;

public final class RuntimeHudAvailabilityCoordinator {
    public interface Hooks {
        void updateRuntimeValues(double targetFps, double presentFps, long uptimeSec, long frameOutHost);
        void updateCompactLine(String line);
        void showTextOnly(String modeTag, String text, int color);
        void setPerfHudPanelAlpha(float alpha);
        void refreshDebugOverlay();
        void emitDebug(String message);
    }

    private RuntimeHudAvailabilityCoordinator() {
    }

    public static boolean shouldKeepLastMetrics(
            JSONObject metrics,
            boolean daemonReachable,
            long lastPerfMetricsAtMs,
            long nowMs,
            long staleGraceMs
    ) {
        if (metrics != null) {
            return false;
        }
        return daemonReachable
                && lastPerfMetricsAtMs > 0L
                && (nowMs - lastPerfMetricsAtMs) <= staleGraceMs;
    }

    public static void applyUnavailable(int selectedFps, int offlineHudColor, Hooks hooks) {
        hooks.updateRuntimeValues(selectedFps, 0.0, 0L, 0L);
        hooks.updateCompactLine("hud: offline | waiting metrics");
        hooks.showTextOnly("offline", "HUD OFFLINE\nwaiting for host metrics...", offlineHudColor);
        hooks.setPerfHudPanelAlpha(0.96f);
        hooks.refreshDebugOverlay();
        hooks.emitDebug("state=offline waiting_metrics=1");
    }
}
