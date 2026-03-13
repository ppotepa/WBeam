package com.wbeam.hud;

import org.json.JSONArray;

/**
 * Pure renderer for runtime trend cards used by runtime HUD web layout.
 */
public final class RuntimeTrendGridRenderer {
    private RuntimeTrendGridRenderer() {}

    @SuppressWarnings("java:S107")
    public static String buildMetricTrendRowsHtml(
            JSONArray fps,
            JSONArray mbps,
            JSONArray drops,
            JSONArray latency,
            JSONArray queue,
            String fpsTone,
            String mbpsTone,
            String dropTone,
            String latTone,
            String queueTone,
            double fpsLowAnchor
    ) {
        return buildTrendCardHtml("FPS", fps, HudRenderSupport.hudToneClass(fpsTone), "", fpsLowAnchor)
                + buildTrendCardHtml("MBPS", mbps, HudRenderSupport.hudToneClass(mbpsTone), "Mbps", fpsLowAnchor)
                + buildTrendCardHtml("DROPS / SEC", drops, HudRenderSupport.hudToneClass(dropTone), "", fpsLowAnchor)
                + buildTrendCardHtml("LAT p95", latency, HudRenderSupport.hudToneClass(latTone), "ms", fpsLowAnchor)
                + buildTrendCardHtml("QUEUE DEPTH", queue, HudRenderSupport.hudToneClass(queueTone), "", fpsLowAnchor);
    }

    private static String buildTrendCardHtml(
            String label,
            JSONArray series,
            String toneClass,
            String unitSuffix,
            double fpsLowAnchor
    ) {
        String bars = HudRenderSupport.buildTrendSparkChartFromJson(series, toneClass);
        String stats = HudRenderSupport.buildSeriesStats(series, unitSuffix);
        String meta = HudRenderSupport.buildSeriesMetaHtml(label, series, unitSuffix, fpsLowAnchor);
        return "<div class='trend-card'><div class='trend-head'><span class='trend-label'>"
                + HudRenderSupport.escapeHtml(label)
                + "</span><span class='trend-range "
                + HudRenderSupport.escapeHtml(toneClass == null ? "" : toneClass)
                + "'>"
                + HudRenderSupport.escapeHtml(stats)
                + "</span></div><div class='spark'>"
                + bars
                + "</div>"
                + meta
                + "</div>";
    }
}
