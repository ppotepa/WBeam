package com.proto.demo.stats;

/**
 * Single responsibility: track frame arrival rate and emit periodic FPS reports.
 *
 * Thread-safe: {@link #onFrame()} may be called from any thread.
 */
public class FrameStats {

    /** Receives a stats report every {@value #REPORT_INTERVAL_MS} ms. */
    public interface Callback {
        void onStats(double fps, long totalFrames, String transport);
    }

    private static final long REPORT_INTERVAL_MS = 2000;

    private final Callback callback;
    private final String   transport;

    private long frameCount  = 0;
    private long totalFrames = 0;
    private long lastStatTime;

    public FrameStats(String transport, Callback callback) {
        this.transport    = transport;
        this.callback     = callback;
        this.lastStatTime = System.currentTimeMillis();
    }

    /** Call once per decoded frame. Fires the callback at the report interval. */
    public synchronized void onFrame() {
        frameCount++;
        totalFrames++;
        long now     = System.currentTimeMillis();
        long elapsed = now - lastStatTime;
        if (elapsed >= REPORT_INTERVAL_MS) {
            double fps = frameCount * 1000.0 / elapsed;
            callback.onStats(fps, totalFrames, transport);
            frameCount   = 0;
            lastStatTime = now;
        }
    }

    /** Reset counters (e.g. on transport reconnect). */
    public synchronized void reset() {
        frameCount   = 0;
        totalFrames  = 0;
        lastStatTime = System.currentTimeMillis();
    }
}
