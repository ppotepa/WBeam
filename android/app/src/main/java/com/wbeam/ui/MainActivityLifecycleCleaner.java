package com.wbeam.ui;

import android.os.Handler;
import android.util.Log;

import com.wbeam.api.StatusPoller;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class MainActivityLifecycleCleaner {
    private static final String TAG = "WBeamLifecycle";
    /** Milliseconds to wait for each executor lane to drain on teardown. */
    private static final long EXECUTOR_DRAIN_MS = 1_500L;

    private MainActivityLifecycleCleaner() {
    }

    /**
     * Clean shutdown of all activity-level resources.
     *
     * Order contract:
     *   1. Stop polling — prevents new work from being submitted.
     *   2. Cancel in-flight UI runnables.
     *   3. Release video/stream resources (which joins the decode thread via H264TcpPlayer.stop()).
     *   4. Gracefully drain then force-stop executors.
     */
    public static void cleanup(
            StatusPoller statusPoller,
            Runnable stopPreflightPulse,
            Handler uiHandler,
            Runnable simpleMenuAutoHideTask,
            Runnable debugInfoFadeTask,
            Runnable debugGraphSampleTask,
            Runnable debugOverlayToggleTask,
            Runnable releaseVideoTest,
            Runnable stopLiveView,
            ExecutorService controlExecutor,
            ExecutorService telemetryExecutor,
            ExecutorService probeExecutor
    ) {
        // 1. Stop producers first so no new tasks are submitted.
        statusPoller.stop();
        stopPreflightPulse.run();

        // 2. Cancel pending UI callbacks.
        uiHandler.removeCallbacks(simpleMenuAutoHideTask);
        uiHandler.removeCallbacks(debugInfoFadeTask);
        uiHandler.removeCallbacks(debugGraphSampleTask);
        uiHandler.removeCallbacks(debugOverlayToggleTask);

        // 3. Stop media — H264TcpPlayer.stop() joins the decode thread.
        releaseVideoTest.run();
        stopLiveView.run();

        // 4. Drain executors (allow in-flight tasks to finish), then force shutdown.
        shutdownExecutor("control", controlExecutor);
        shutdownExecutor("telemetry", telemetryExecutor);
        shutdownExecutor("probe", probeExecutor);
    }

    private static void shutdownExecutor(String name, ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(EXECUTOR_DRAIN_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, name + " executor did not drain within " + EXECUTOR_DRAIN_MS + " ms; forcing");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
