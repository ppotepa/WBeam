package com.wbeam.hud;

import android.os.SystemClock;

import java.util.Locale;

/**
 * Tracks lightweight local resource proxies used in HUD (CPU/MEM/GPU*).
 * Contract:
 * - call {@link #sample(double, double)} on each telemetry refresh,
 * - call {@link #buildRowsHtml()} to render current compact resource rows.
 */
public final class ResourceUsageTracker {
    private static final String STATE_RISK = "state-risk";
    private static final String STATE_WARN = "state-warn";
    private static final String STATE_OK = "state-ok";
    private static final String SPARK_CLOSE = "</span><div class='spark'>";
    private static final String ROW_CLOSE = "</div></div>";
    
    private long usageSampleLastRealtimeMs = 0L;
    private long usageSampleLastCpuMs = 0L;
    private double usageCpuPct = 0.0;
    private double usageMemMb = 0.0;
    private double usageGpuPct = 0.0;
    private final MetricSeriesBuffer usageCpuSeries;
    private final MetricSeriesBuffer usageMemSeries;
    private final MetricSeriesBuffer usageGpuSeries;

    public ResourceUsageTracker(int seriesMax) {
        int cap = Math.max(16, seriesMax);
        usageCpuSeries = new MetricSeriesBuffer(cap);
        usageMemSeries = new MetricSeriesBuffer(cap);
        usageGpuSeries = new MetricSeriesBuffer(cap);
    }

    public void sample(double targetFps, double renderP95Ms) {
        long nowMs = SystemClock.elapsedRealtime();
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
    }

    public String buildRowsHtml() {
        double memPct = usageMemSeries.latest(0.0);
        
        String cpuTone;
        if (usageCpuPct > 85.0) {
            cpuTone = STATE_RISK;
        } else if (usageCpuPct > 65.0) {
            cpuTone = STATE_WARN;
        } else {
            cpuTone = STATE_OK;
        }
        
        String memTone;
        if (memPct > 88.0) {
            memTone = STATE_RISK;
        } else if (memPct > 70.0) {
            memTone = STATE_WARN;
        } else {
            memTone = STATE_OK;
        }
        
        String gpuTone;
        if (usageGpuPct > 90.0) {
            gpuTone = STATE_RISK;
        } else if (usageGpuPct > 70.0) {
            gpuTone = STATE_WARN;
        } else {
            gpuTone = STATE_OK;
        }

        StringBuilder html = new StringBuilder();
        html.append("<div class='res-row'><span class='rk'>CPU</span><span class='rv ")
                .append(cpuTone)
                .append("'>")
                .append(String.format(Locale.US, "%.0f%%", usageCpuPct))
                .append(SPARK_CLOSE)
                .append(buildSparkBarsHtml(usageCpuSeries, cpuTone))
                .append(ROW_CLOSE);
        html.append("<div class='res-row'><span class='rk'>MEM</span><span class='rv ")
                .append(memTone)
                .append("'>")
                .append(String.format(Locale.US, "%.0f MB", usageMemMb))
                .append(SPARK_CLOSE)
                .append(buildSparkBarsHtml(usageMemSeries, memTone))
                .append(ROW_CLOSE);
        html.append("<div class='res-row'><span class='rk'>GPU*</span><span class='rv ")
                .append(gpuTone)
                .append("'>")
                .append(String.format(Locale.US, "%.0f%%", usageGpuPct))
                .append(SPARK_CLOSE)
                .append(buildSparkBarsHtml(usageGpuSeries, gpuTone))
                .append(ROW_CLOSE);
        return html.toString();
    }

    private static String buildSparkBarsHtml(MetricSeriesBuffer series, String toneClass) {
        if (series == null || series.isEmpty()) {
            return HudRenderSupport.buildSparkPlaceholderBars(toneClass, 18);
        }
        StringBuilder bars = new StringBuilder();
        for (Double sample : series) {
            double value = sample == null ? 0.0 : sample;
            int height = (int) Math.round(clampDouble(value, 0.0, 100.0));
            if (height < 8) {
                height = 8;
            }
            bars.append("<span class='spark-bar ")
                    .append(HudRenderSupport.escapeHtml(toneClass))
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
}
