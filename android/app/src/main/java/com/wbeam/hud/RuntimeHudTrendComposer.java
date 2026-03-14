package com.wbeam.hud;

@SuppressWarnings("java:S2184") 
public final class RuntimeHudTrendComposer {
    private RuntimeHudTrendComposer() {
    }

    @SuppressWarnings("java:S107")
    @SuppressWarnings("java:S1905")
    @SuppressWarnings("java:S2184")
    public static String appendSamplesAndBuildHtml(
            MetricSeriesBuffer runtimePresentSeries,
            MetricSeriesBuffer runtimeMbpsSeries,
            MetricSeriesBuffer runtimeDropSeries,
            MetricSeriesBuffer runtimeLatencySeries,
            MetricSeriesBuffer runtimeQueueSeries,
            double presentFps,
            double bitrateMbps,
            double dropPerSec,
            double e2eP95,
            int qT,
            int qD,
            int qR,
            String tone,
            double fpsLowAnchor
    ) {
        runtimePresentSeries.addSample(Math.max(0.0, presentFps));
        runtimeMbpsSeries.addSample(Math.max(0.0, bitrateMbps));
        runtimeDropSeries.addSample(Math.max(0.0, dropPerSec));
        runtimeLatencySeries.addSample(Math.max(0.0, e2eP95));
        runtimeQueueSeries.addSample(Math.max(0.0, qT + qD + qR));
        return RuntimeTrendGridRenderer.buildMetricTrendRowsHtml(
                runtimePresentSeries.toJsonFinite(),
                runtimeMbpsSeries.toJsonFinite(),
                runtimeDropSeries.toJsonFinite(),
                runtimeLatencySeries.toJsonFinite(),
                runtimeQueueSeries.toJsonFinite(),
                tone,
                tone,
                tone,
                tone,
                tone,
                fpsLowAnchor
        );
    }
}
