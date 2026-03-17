package com.wbeam.ui;

import android.view.View;

import com.wbeam.widget.FpsLossGraphView;

@SuppressWarnings("java:S107")
public final class BuildVariantUiCoordinator {
    @FunctionalInterface
    public interface Action {
        void run();
    }

    @FunctionalInterface
    public interface BoolAction {
        void run(boolean value);
    }

    private BuildVariantUiCoordinator() {
    }

    public static void apply(
            boolean debugBuild,
            boolean debugOverlayVisible,
            FpsLossGraphView debugFpsGraphView,
            int debugFpsGraphPoints,
            View debugInfoPanel,
            Action setFullscreen,
            BoolAction setDebugOverlayVisible,
            Action startDebugGraphSampling,
            Action refreshDebugInfoOverlay,
            Action stopDebugGraphSampling
    ) {
        setFullscreen.run();
        if (debugBuild) {
            if (debugFpsGraphView != null) {
                debugFpsGraphView.setCapacity(debugFpsGraphPoints);
            }
            setDebugOverlayVisible.run(debugOverlayVisible);
            startDebugGraphSampling.run();
            refreshDebugInfoOverlay.run();
            return;
        }
        MainActivityUiBinder.applyVisibility(View.GONE, debugInfoPanel);
        stopDebugGraphSampling.run();
    }
}
