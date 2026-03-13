package com.wbeam.ui;

import android.os.Handler;

import com.wbeam.api.StatusPoller;

import java.util.concurrent.ExecutorService;

public final class MainActivityLifecycleCleaner {
    private MainActivityLifecycleCleaner() {
    }

    @SuppressWarnings("java:S107")
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
            ExecutorService ioExecutor
    ) {
        statusPoller.stop();
        stopPreflightPulse.run();
        uiHandler.removeCallbacks(simpleMenuAutoHideTask);
        uiHandler.removeCallbacks(debugInfoFadeTask);
        uiHandler.removeCallbacks(debugGraphSampleTask);
        uiHandler.removeCallbacks(debugOverlayToggleTask);
        releaseVideoTest.run();
        stopLiveView.run();
        ioExecutor.shutdownNow();
    }
}
