package com.wbeam.hud;

import android.os.SystemClock;

/**
 * Tracks lightweight local resource proxies used in HUD (CPU/MEM/GPU*).
 * Contract:
 * - call {@link #sample(double, double)} on each telemetry refresh,
 * - call {@link #buildRowsHtml()} to render current compact resource rows.
 */
@SuppressWarnings({"java:S3358", "java:S1192"})
public final class ResourceUsageTracker {
    private static final long SAMPLE_INTERVAL_MS = 500L;
    private static final String STATE_RISK = "state-risk";
    private static final String STATE_WARN = "state-warn";
    private static final String STATE_OK = "state-ok";
    private static final String ROW_SPARK_OPEN = "</span><div class='spark'>";
    private static final String ROW_CLOSE = "</div></div>";

    private long usageSampleLastRealtimeMs = 0L;
    private long usageSampleLastCpuMs = 0L;
    private double usageCpuPct = 0.0;
    private double usageMemMb = 0.0;
    private double usageGpuPct = 0.0;
    private final MetricSeriesBuffer usageCpuSeries;
    private final MetricSeriesBuffer usageMemSeries;
    private final MetricSeriesBuffer usageGpuSeries;
    private boolean rowsHtmlDirty = true;
    private String cachedRowsHtml = "";

    public ResourceUsageTracker(int seriesMax) {
        int cap = Math.max(16, seriesMax);
        usageCpuSeries = new MetricSeriesBuffer(cap);
        usageMemSeries = new MetricSeriesBuffer(cap);
        usageGpuSeries = new MetricSeriesBuffer(cap);
    }

    public void sample(double targetFps, double renderP95Ms) {
        long nowMs = SystemClock.elapsedRealtime();
        if (usageSampleLastRealtimeMs > 0L && (nowMs - usageSampleLastRealtimeMs) < SAMPLE_INTERVAL_MS) {
            return;
        }
        long procCpuNow = android.os.Process.getElapsedCpuTime();
        if (usageSampleLastRealtimeMs > 0L && usageSampleLastCpuMs > 0L) {
            long dWall = Math.max(1L, nowMs - usageSampleLastRealtimeMs);
            long dCpu = Math.max(0L, procCpuNow - usageSampleLastCpuMs);
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            usageCpuPct = clampDouble((dCpu * 100.0) / (dWall * cores), 0.0, 100.0);
        }
        usageSampleLastRealtimeMs = nowMs;
        usageSampleLastCpuMs = procCpuNow;

        Runtime rt = Runtime.getRuntime();
        double usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        usageMemMb = Math.max(0.0, usedMb);
        double maxMb = Math.max(1.0, rt.maxMemory() / (1024.0 * 1024.0));
        double memPct = clampDouble((usageMemMb / maxMb) * 100.0, 0.0, 100.0);

        double frameBudgetMs = targetFps > 1.0 ? (1000.0 / targetFps) : 16.67;
        usageGpuPct = clampDouble((Math.max(0.0, renderP95Ms) / Math.max(1.0, frameBudgetMs)) * 100.0, 0.0, 100.0);

        usageCpuSeries.addSample(usageCpuPct);
        usageMemSeries.addSample(memPct);
        usageGpuSeries.addSample(usageGpuPct);
        rowsHtmlDirty = true;
    }

    public String buildRowsHtml() {
        if (!rowsHtmlDirty) {
            return cachedRowsHtml;
        }
        String cpuTone = resolveTone(usageCpuPct, 85.0, 65.0);
        double memPct = usageMemSeries.latest(0.0);
        String memTone = resolveTone(memPct, 88.0, 70.0);
        String gpuTone = resolveTone(usageGpuPct, 90.0, 70.0);

        StringBuilder html = new StringBuilder(2048);
        html.append("<div class='res-row'><span class='rk'>CPU</span><span class='rv ")
                .append(cpuTone)
                .append("'>")
                .append(fmt0(usageCpuPct))
                .append('%')
                .append(ROW_SPARK_OPEN)
                .append(buildSparkBarsHtml(usageCpuSeries, cpuTone))
                .append(ROW_CLOSE);
        html.append("<div class='res-row'><span class='rk'>MEM</span><span class='rv ")
                .append(memTone)
                .append("'>")
                .append(fmt0(usageMemMb))
                .append(" MB")
                .append(ROW_SPARK_OPEN)
                .append(buildSparkBarsHtml(usageMemSeries, memTone))
                .append(ROW_CLOSE);
        html.append("<div class='res-row'><span class='rk'>GPU*</span><span class='rv ")
                .append(gpuTone)
                .append("'>")
                .append(fmt0(usageGpuPct))
                .append('%')
                .append(ROW_SPARK_OPEN)
                .append(buildSparkBarsHtml(usageGpuSeries, gpuTone))
                .append(ROW_CLOSE);
        cachedRowsHtml = html.toString();
        rowsHtmlDirty = false;
        return cachedRowsHtml;
    }

    private static String resolveTone(double value, double riskThreshold, double warnThreshold) {
        if (value > riskThreshold) {
            return STATE_RISK;
        }
        if (value > warnThreshold) {
            return STATE_WARN;
        }
        return STATE_OK;
    }

    private static String buildSparkBarsHtml(MetricSeriesBuffer series, String toneClass) {
        if (series == null || series.isEmpty()) {
            return HudRenderSupport.buildSparkPlaceholderBars(toneClass, 18);
        }
        String escapedTone = HudRenderSupport.escapeHtml(toneClass);
        StringBuilder bars = new StringBuilder();
        for (Double sample : series) {
            double value = sample == null ? 0.0 : sample;
            int height = (int) Math.round(clampDouble(value, 0.0, 100.0));
            if (height < 8) {
                height = 8;
            }
            bars.append("<span class='spark-bar ")
                    .append(escapedTone)
                    .append("' style='height:")
                    .append(height)
                    .append("%'></span>");
        }
        return bars.toString();
    }

    private static double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String fmt0(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return String.valueOf(Math.round(value));
    }
}
