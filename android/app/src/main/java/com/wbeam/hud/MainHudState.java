package com.wbeam.hud;

@SuppressWarnings("java:S1104")
public final class MainHudState {
    public String compactLine = "hud: waiting for metrics";
    public double latestTargetFps = 60.0;
    public double latestPresentFps = 0.0;
    public long latestStreamUptimeSec = 0L;
    public long latestFrameOutHost = 0L;
    public double latestStablePresentFps = 0.0;
    public long latestStablePresentFpsAtMs = 0L;
    public long lastPerfMetricsAtMs = 0L;
    public long runtimeDropPrevCount = -1L;
    public long runtimeDropPrevAtMs = 0L;
}
