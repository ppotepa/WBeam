package com.wbeam.telemetry;

import android.os.SystemClock;
import android.util.Log;

import com.wbeam.ClientMetricsSample;
import com.wbeam.api.HostApiClient;

import java.util.concurrent.ExecutorService;

/**
 * Rate-limited poster of client-side streaming metrics to the host daemon.
 * Posts to POST /v1/client-metrics no more often than CLIENT_METRICS_INTERVAL_MS.
 */
public final class ClientMetricsReporter {

    private static final String TAG                        = "WBeamMetrics";
    // 2 Hz client-metrics cadence — balances HUD telemetry refresh with battery.
    private static final long   CLIENT_METRICS_INTERVAL_MS = 500L;

    private final ExecutorService ioExecutor;
    private final WarnLogger      warnLogger;

    private long lastPostAt = 0L;
    private boolean postInFlight = false;
    private ClientMetricsSample pendingMetrics;

    /** Callback to surface warning messages to the UI live-log. */
    public interface WarnLogger {
        void appendWarn(String msg);
    }

    public ClientMetricsReporter(ExecutorService ioExecutor, WarnLogger warnLogger) {
        this.ioExecutor = ioExecutor;
        this.warnLogger = warnLogger;
    }

    /**
     * Post metrics sample asynchronously (rate-limited, fire-and-forget).
     * Safe to call from any thread. Silently drops the sample if the executor
     * has already been shut down during activity teardown.
     */
    public void push(ClientMetricsSample metrics) {
        if (metrics == null) {
            return;
        }
        ClientMetricsSample metricsToPost;
        synchronized (this) {
            pendingMetrics = metrics;
            long now = SystemClock.elapsedRealtime();
            if (postInFlight || now - lastPostAt < CLIENT_METRICS_INTERVAL_MS) {
                return;
            }
            metricsToPost = pendingMetrics;
            pendingMetrics = null;
            postInFlight = true;
            lastPostAt = now;
        }

        if (metricsToPost == null) {
            return;
        }

        try {
            ioExecutor.execute(() -> postMetrics(metricsToPost));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            synchronized (this) {
                postInFlight = false;
            }
            // Executor is shutting down — keep dropping stale telemetry, don't crash.
        }
    }

    private void postMetrics(ClientMetricsSample metrics) {
        try {
            HostApiClient.apiRequestWithRetry("POST", "/v1/client-metrics", metrics.toJson(), 1);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) {
                msg = e.getClass().getSimpleName();
            }
            Log.w(TAG, "client-metrics post failed: " + msg);
            warnLogger.appendWarn("client-metrics post failed: " + msg);
        } finally {
            synchronized (this) {
                postInFlight = false;
            }
        }
    }
}
