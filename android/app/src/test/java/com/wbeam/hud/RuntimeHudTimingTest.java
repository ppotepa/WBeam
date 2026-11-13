package com.wbeam.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeHudTimingTest {
    @Test
    public void unavailableLatencyClearsPreviousSessionHistory() {
        MetricSeriesBuffer fps = new MetricSeriesBuffer(10);
        MetricSeriesBuffer mbps = new MetricSeriesBuffer(10);
        MetricSeriesBuffer drops = new MetricSeriesBuffer(10);
        MetricSeriesBuffer latency = new MetricSeriesBuffer(10);
        MetricSeriesBuffer queue = new MetricSeriesBuffer(10);
        RuntimeHudTrendComposer.appendSamples(fps, mbps, drops, latency, queue,
                30, 5, 0, 12.0, 0, 0, 0);
        assertEquals(12.0, latency.latest(Double.NaN), 0.0);

        String html = RuntimeHudTrendComposer.appendSamplesAndBuildHtml(
                fps, mbps, drops, latency, queue,
                30, 5, 0, Double.NaN, 0, 0, 0, "ok", 30
        );
        assertTrue(latency.isEmpty());
        assertEquals(2, fps.toJsonFinite().length());
        assertTrue(html.contains("LAT p95"));
        assertTrue(html.contains("unavailable"));
    }

    @Test
    public void compactHudNeverFormatsMissingLatencyAsZero() {
        String line = RuntimeHudComputation.formatCompactHudLine(
                60, 59, Double.NaN, 2.5, 0.5, 0, 0, 0
        );
        assertTrue(line.contains("e2e unavailable"));
        assertFalse(line.contains("e2e 0"));
    }

    @Test
    public void webHudDefaultsToUnavailableAndShowsOnlyMeasuredTimings() {
        RuntimeHudWebPayloadBuilder.Input input = new RuntimeHudWebPayloadBuilder.Input();
        input.setTone("ok");
        String missing = RuntimeHudShellRenderer.buildHtml(
                RuntimeHudWebPayloadBuilder.build(input).getHtmlContent()
        );
        assertTrue(missing.contains("E2E p95"));
        assertTrue(missing.contains("unavailable"));
        assertFalse(missing.contains("NaN"));

        input.setE2eP95(12.3);
        input.setFrametimeP95(16.67);
        String measured = RuntimeHudShellRenderer.buildHtml(
                RuntimeHudWebPayloadBuilder.build(input).getHtmlContent()
        );
        assertTrue(measured.contains("12.3 ms"));
        assertTrue(measured.contains("16.67 ms"));
    }
}
