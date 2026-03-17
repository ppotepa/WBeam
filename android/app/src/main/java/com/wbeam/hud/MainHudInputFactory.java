package com.wbeam.hud;

public final class MainHudInputFactory {
    private MainHudInputFactory() {
    }

    public static MainHudCoordinator.Input create(
            String logTag,
            MainHudState hudState,
            HudOverlayDisplay.State overlayState,
            MetricSeriesBuffer runtimePresentSeries,
            MetricSeriesBuffer runtimeMbpsSeries,
            MetricSeriesBuffer runtimeDropSeries,
            MetricSeriesBuffer runtimeLatencySeries,
            MetricSeriesBuffer runtimeQueueSeries,
            ResourceUsageTracker resourceUsageTracker,
            android.webkit.WebView perfHudWebView,
            android.widget.TextView perfHudText,
            android.view.View perfHudPanel,
            int transportQueueMaxFrames,
            int decodeQueueMaxFrames,
            int renderQueueMaxFrames,
            long presentFpsStaleGraceMs,
            long metricsStaleGraceMs,
            double fpsLowAnchor,
            int hudTextColorOffline,
            int hudTextColorLive,
            String appBuildRevision,
            MainHudCoordinator.IntProvider selectedFpsProvider,
            MainHudCoordinator.StringProvider selectedProfileProvider,
            MainHudCoordinator.StringProvider selectedEncoderProvider,
            MainHudCoordinator.StreamSizeProvider streamSizeProvider,
            MainHudCoordinator.BoolProvider daemonReachableProvider,
            MainHudCoordinator.StringProvider daemonStateProvider,
            MainHudCoordinator.StringProvider daemonHostNameProvider,
            MainHudCoordinator.StringProvider daemonBuildRevisionProvider,
            MainHudCoordinator.StringProvider daemonLastErrorProvider,
            MainHudCoordinator.LongProvider daemonRunIdProvider,
            MainHudCoordinator.LongProvider daemonUptimeSecProvider,
            MainHudCoordinator.StringProvider daemonStateUiProvider,
            MainHudCoordinator.BoolProvider debugOverlayVisibleProvider,
            boolean buildDebug,
            MainHudCoordinator.ToggleDebugOverlayHandler debugOverlayHandler,
            MainHudCoordinator.HudTextOnlyHandler hudTextOnlyHandler,
            MainHudCoordinator.RefreshHandler refreshDebugOverlayHandler,
            MainHudCoordinator.SnapshotLogHandler snapshotLogHandler
    ) {
        MainHudCoordinator.Input input = new MainHudCoordinator.Input();
        input.setLogTag(logTag);
        input.setState(hudState);
        input.setOverlayState(overlayState);
        input.setRuntimePresentSeries(runtimePresentSeries);
        input.setRuntimeMbpsSeries(runtimeMbpsSeries);
        input.setRuntimeDropSeries(runtimeDropSeries);
        input.setRuntimeLatencySeries(runtimeLatencySeries);
        input.setRuntimeQueueSeries(runtimeQueueSeries);
        input.setResourceUsageTracker(resourceUsageTracker);
        input.setPerfHudWebView(perfHudWebView);
        input.setPerfHudText(perfHudText);
        input.setPerfHudPanel(perfHudPanel);
        input.setTransportQueueMaxFrames(transportQueueMaxFrames);
        input.setDecodeQueueMaxFrames(decodeQueueMaxFrames);
        input.setRenderQueueMaxFrames(renderQueueMaxFrames);
        input.setPresentFpsStaleGraceMs(presentFpsStaleGraceMs);
        input.setMetricsStaleGraceMs(metricsStaleGraceMs);
        input.setFpsLowAnchor(fpsLowAnchor);
        input.setHudTextColorOffline(hudTextColorOffline);
        input.setHudTextColorLive(hudTextColorLive);
        input.setAppBuildRevision(appBuildRevision);
        input.setSelectedFpsProvider(selectedFpsProvider);
        input.setSelectedProfileProvider(selectedProfileProvider);
        input.setSelectedEncoderProvider(selectedEncoderProvider);
        input.setStreamSizeProvider(streamSizeProvider);
        input.setDaemonReachableProvider(daemonReachableProvider);
        input.setDaemonStateProvider(daemonStateProvider);
        input.setDaemonHostNameProvider(daemonHostNameProvider);
        input.setDaemonBuildRevisionProvider(daemonBuildRevisionProvider);
        input.setDaemonLastErrorProvider(daemonLastErrorProvider);
        input.setDaemonRunIdProvider(daemonRunIdProvider);
        input.setDaemonUptimeSecProvider(daemonUptimeSecProvider);
        input.setDaemonStateUiProvider(daemonStateUiProvider);
        input.setDebugOverlayVisibleProvider(debugOverlayVisibleProvider);
        input.setBuildDebug(buildDebug);
        input.setDebugOverlayHandler(debugOverlayHandler);
        input.setHudTextOnlyHandler(hudTextOnlyHandler);
        input.setRefreshDebugOverlayHandler(refreshDebugOverlayHandler);
        input.setSnapshotLogHandler(snapshotLogHandler);
        return input;
    }
}
