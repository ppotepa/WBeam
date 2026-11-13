package com.wbeam.hud;

public final class RuntimeHudTrendComposer {
    private RuntimeHudTrendComposer() {
    }

    @SuppressWarnings("java:S107")
    public static void appendSamples(
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
            int qR
    ) {
        runtimePresentSeries.addSample(Math.max(0.0, presentFps));
        runtimeMbpsSeries.addSample(Math.max(0.0, bitrateMbps));
        runtimeDropSeries.addSample(Math.max(0.0, dropPerSec));
        if (TimingValueFormatter.isAvailable(e2eP95)) {
            runtimeLatencySeries.addSample(e2eP95);
        } else {
            // Do not present a previous session's measurements as current.
            runtimeLatencySeries.clear();
        }
        double queueDepth = (double) qT + qD + qR;
        runtimeQueueSeries.addSample(Math.max(0.0, queueDepth));
    }

    @SuppressWarnings("java:S107")
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
        appendSamples(
                runtimePresentSeries,
                runtimeMbpsSeries,
                runtimeDropSeries,
                runtimeLatencySeries,
                runtimeQueueSeries,
                presentFps,
                bitrateMbps,
                dropPerSec,
                e2eP95,
                qT,
                qD,
                qR
        );
        return RuntimeTrendGridRenderer.buildMetricTrendRowsHtml(
                runtimePresentSeries,
                runtimeMbpsSeries,
                runtimeDropSeries,
                runtimeLatencySeries,
                runtimeQueueSeries,
                tone,
                tone,
                tone,
                tone,
                tone,
                fpsLowAnchor
        );
    }
}
