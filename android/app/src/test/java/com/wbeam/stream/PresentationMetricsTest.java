package com.wbeam.stream;

import org.junit.Test;
import static org.junit.Assert.*;

public class PresentationMetricsTest {
    @Test public void percentileIsAnObservedRankRatherThanMeanOrMaximum() {
        long[] samples = new long[100];
        for (int i = 0; i < samples.length; i++) samples[i] = (100L - i) * 1_000_000L;
        long[] scratch = new long[100];
        assertEquals(50.0, StreamBufferMath.percentileMs(samples, 100, 0.50, scratch), 0.0);
        assertEquals(95.0, StreamBufferMath.percentileMs(samples, 100, 0.95, scratch), 0.0);
        assertEquals(100_000_000L, samples[0]); // selection must not mutate samples
    }

    @Test public void renderP95DoesNotBecomeTheWorstSingleReleaseCall() {
        PresentationMetrics metrics = new PresentationMetrics();
        for (int i = 1; i <= 100; i++) {
            long finished = i * 200_000_000L;
            metrics.record(finished - i * 1_000_000L, finished);
        }
        assertEquals(95.0, metrics.renderP95Ms(), 0.0);
        assertEquals(200.0, metrics.frameTimeP95Ms(), 0.0);
    }

    @Test public void absentIntervalsRemainUnavailableAndWindowResetKeepsCadence() {
        PresentationMetrics metrics = new PresentationMetrics();
        assertNull(metrics.frameTimeP95Ms());
        metrics.record(9_000_000L, 10_000_000L);
        assertNull(metrics.frameTimeP95Ms());
        metrics.clearWindow();
        assertNull(metrics.frameTimeP95Ms());
        metrics.record(24_000_000L, 26_000_000L);
        assertEquals(16.0, metrics.frameTimeP95Ms(), 0.0);
        assertEquals(2.0, metrics.renderP95Ms(), 0.0);
    }

    @Test public void boundedWindowDropsOldOutliersAndInvalidClockSamples() {
        PresentationMetrics metrics = new PresentationMetrics();
        metrics.record(0L, 100_000_000L);
        for (int i = 1; i <= 200; i++) {
            long finished = 100_000_000L + i * 16_000_000L;
            metrics.record(finished - 1_000_000L, finished);
        }
        metrics.record(50L, 20L);
        metrics.record(-1L, 20L);
        metrics.record(0L, 50L);
        assertEquals(1.0, metrics.renderP95Ms(), 0.0);
        assertEquals(16.0, metrics.frameTimeP95Ms(), 0.0);
    }
}
