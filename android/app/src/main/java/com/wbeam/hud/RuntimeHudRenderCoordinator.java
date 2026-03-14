package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

public final class RuntimeHudRenderCoordinator {
    /**
     * Plain data carrier for HUD render coordinator input.
     */
    static final class Input {
        MetricSeriesBuffer runtimePresentSeries;
        MetricSeriesBuffer runtimeMbpsSeries;
        MetricSeriesBuffer runtimeDropSeries;
        MetricSeriesBuffer runtimeLatencySeries;
        MetricSeriesBuffer runtimeQueueSeries;
        RuntimeHudUpdateState state;
        double fpsLowAnchor;

        boolean daemonReachable;
        String selectedProfile;
        String selectedEncoder;
        int streamWidth;
        int streamHeight;
        String daemonHostName;
        String daemonStateUi;
        String daemonBuildRevision;
        String appBuildRevision;
        String daemonLastError;
        ResourceUsageTracker resourceUsageTracker;
        WebView perfHudWebView;
        TextView perfHudText;
        View perfHudPanel;
        HudOverlayDisplay.State hudOverlayState;
        int hudTextColorLive;
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
