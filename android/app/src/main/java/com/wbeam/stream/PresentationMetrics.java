package com.wbeam.stream;

/** Observed output-release timings on the client, not physical display latency. */
final class PresentationMetrics {
    private static final int CAPACITY = 128;
    private final long[] renderNs = new long[CAPACITY];
    private final long[] frameIntervalNs = new long[CAPACITY];
    private final long[] scratch = new long[CAPACITY];
    private int renders;
    private int intervals;
    private int renderIndex;
    private int intervalIndex;
    private long previousPresentationNs = -1L;

    void record(long renderStartNs, long renderFinishedNs) {
        if (renderStartNs < 0L || renderFinishedNs < renderStartNs
                || renderFinishedNs < previousPresentationNs) {
            return;
        }
        renderNs[renderIndex] = renderFinishedNs - renderStartNs;
        renderIndex = (renderIndex + 1) % CAPACITY;
        renders = Math.min(CAPACITY, renders + 1);
        if (previousPresentationNs >= 0L && renderFinishedNs > previousPresentationNs) {
            frameIntervalNs[intervalIndex] = renderFinishedNs - previousPresentationNs;
            intervalIndex = (intervalIndex + 1) % CAPACITY;
            intervals = Math.min(CAPACITY, intervals + 1);
        }
        previousPresentationNs = renderFinishedNs;
    }

    double renderP95Ms() {
        return StreamBufferMath.percentileMs(renderNs, renders, 0.95, scratch);
    }

    Double frameTimeP95Ms() {
        return intervals == 0 ? null
                : StreamBufferMath.percentileMs(frameIntervalNs, intervals, 0.95, scratch);
    }

    void clearWindow() {
        renders = 0;
        intervals = 0;
        renderIndex = 0;
        intervalIndex = 0;
        // Keep the previous release time to observe intervals across windows.
    }
}
