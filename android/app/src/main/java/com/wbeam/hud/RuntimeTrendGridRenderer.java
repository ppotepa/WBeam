package com.wbeam.hud;

/**
 * Pure renderer for runtime trend cards used by runtime HUD web layout.
 */
@SuppressWarnings("java:S107")
public final class RuntimeTrendGridRenderer {
    private RuntimeTrendGridRenderer() {}

    public static String buildMetricTrendRowsHtml(
            MetricSeriesBuffer fps,
            MetricSeriesBuffer mbps,
            MetricSeriesBuffer drops,
            MetricSeriesBuffer latency,
            MetricSeriesBuffer queue,
            String fpsTone,
            String mbpsTone,
            String dropTone,
            String latTone,
            String queueTone,
            double fpsLowAnchor
    ) {
        return HudRenderSupport.buildTrendCardHtml(
                "FPS", fps, HudRenderSupport.hudToneClass(fpsTone), "", fpsLowAnchor
        ) + HudRenderSupport.buildTrendCardHtml(
                "MBPS", mbps, HudRenderSupport.hudToneClass(mbpsTone), "Mbps", fpsLowAnchor
        ) + HudRenderSupport.buildTrendCardHtml(
                "DROPS / SEC", drops, HudRenderSupport.hudToneClass(dropTone), "", fpsLowAnchor
        ) + HudRenderSupport.buildTrendCardHtml(
                "LAT p95", latency, HudRenderSupport.hudToneClass(latTone), "ms", fpsLowAnchor
        ) + HudRenderSupport.buildTrendCardHtml(
                "QUEUE DEPTH", queue, HudRenderSupport.hudToneClass(queueTone), "", fpsLowAnchor
        );
    }
}
