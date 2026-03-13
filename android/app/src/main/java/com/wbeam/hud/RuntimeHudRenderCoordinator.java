package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

public final class RuntimeHudRenderCoordinator {
    /**
     * Plain data carrier for HUD render coordinator input.
     */
    @SuppressWarnings("java:S1104")
    public static final class Input {
        public MetricSeriesBuffer runtimePresentSeries;
        public MetricSeriesBuffer runtimeMbpsSeries;
        public MetricSeriesBuffer runtimeDropSeries;
        public MetricSeriesBuffer runtimeLatencySeries;
        public MetricSeriesBuffer runtimeQueueSeries;
        public RuntimeHudUpdateState state;
        public double fpsLowAnchor;

        public boolean daemonReachable;
        public String selectedProfile;
        public String selectedEncoder;
        public int streamWidth;
        public int streamHeight;
        public String daemonHostName;
        public String daemonStateUi;
        public String daemonBuildRevision;
        public String appBuildRevision;
        public String daemonLastError;
        public ResourceUsageTracker resourceUsageTracker;
        public WebView perfHudWebView;
        public TextView perfHudText;
        public View perfHudPanel;
        public HudOverlayDisplay.State hudOverlayState;
        public int hudTextColorLive;
    }

    private RuntimeHudRenderCoordinator() {
    }

    public static void render(Input input) {
        RuntimeHudUpdateState state = input.state;
        String runtimeChartsHtml = RuntimeHudTrendComposer.appendSamplesAndBuildHtml(
                input.runtimePresentSeries,
                input.runtimeMbpsSeries,
                input.runtimeDropSeries,
                input.runtimeLatencySeries,
                input.runtimeQueueSeries,
                state.presentFps,
                state.bitrateMbps,
                state.dropPerSec,
                state.e2eP95,
                state.qT,
                state.qD,
                state.qR,
                state.pressureState.tone,
                input.fpsLowAnchor
        );
        RuntimeHudOverlayPipeline.render(
                input.daemonReachable,
                input.selectedProfile,
                input.selectedEncoder,
                input.streamWidth,
                input.streamHeight,
                input.daemonHostName,
                input.daemonStateUi,
                input.daemonBuildRevision,
                input.appBuildRevision,
                input.daemonLastError,
                state.targetFps,
                state.presentFps,
                state.recvFps,
                state.decodeFps,
                state.e2eP95,
                state.decodeP95,
                state.renderP95,
                state.frametimeP95,
                state.bitrateMbps,
                state.dropPerSec,
                state.qT,
                state.qD,
                state.qR,
                state.qTMax,
                state.qDMax,
                state.qRMax,
                state.adaptiveLevel,
                state.adaptiveAction,
                state.drops,
                state.bpHigh,
                state.bpRecover,
                state.reason,
                runtimeChartsHtml,
                state.pressureState.tone,
                input.resourceUsageTracker,
                input.perfHudWebView,
                input.perfHudText,
                input.perfHudPanel,
                input.hudOverlayState,
                input.hudTextColorLive
        );
    }
}
