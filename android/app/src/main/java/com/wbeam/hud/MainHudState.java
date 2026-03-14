package com.wbeam.hud;

public final class MainHudState {
    boolean trainerSessionActive = false;
    String compactLine = "hud: waiting for metrics";
    long lastTrainerPayloadAtMs = 0L;
    double latestTargetFps = 60.0;
    double latestPresentFps = 0.0;
    long latestStreamUptimeSec = 0L;
    long latestFrameOutHost = 0L;
    double latestStablePresentFps = 0.0;
    long latestStablePresentFpsAtMs = 0L;
    long lastPerfMetricsAtMs = 0L;
    long runtimeDropPrevCount = -1L;
    long runtimeDropPrevAtMs = 0L;

    public String getCompactLine() {
        return compactLine;
    }

    public double getLatestTargetFps() {
        return latestTargetFps;
    }

    public double getLatestPresentFps() {
        return latestPresentFps;
    }

    public long getLatestStreamUptimeSec() {
        return latestStreamUptimeSec;
    }

    public long getLatestFrameOutHost() {
        return latestFrameOutHost;
    }
}
