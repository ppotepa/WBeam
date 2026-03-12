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
            long trainerHudPayloadGraceMs,
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
        input.logTag = logTag;
        input.state = hudState;
        input.overlayState = overlayState;
        input.runtimePresentSeries = runtimePresentSeries;
        input.runtimeMbpsSeries = runtimeMbpsSeries;
        input.runtimeDropSeries = runtimeDropSeries;
        input.runtimeLatencySeries = runtimeLatencySeries;
        input.runtimeQueueSeries = runtimeQueueSeries;
        input.resourceUsageTracker = resourceUsageTracker;
        input.perfHudWebView = perfHudWebView;
        input.perfHudText = perfHudText;
        input.perfHudPanel = perfHudPanel;
        input.transportQueueMaxFrames = transportQueueMaxFrames;
        input.decodeQueueMaxFrames = decodeQueueMaxFrames;
        input.renderQueueMaxFrames = renderQueueMaxFrames;
        input.presentFpsStaleGraceMs = presentFpsStaleGraceMs;
        input.metricsStaleGraceMs = metricsStaleGraceMs;
        input.trainerHudPayloadGraceMs = trainerHudPayloadGraceMs;
        input.fpsLowAnchor = fpsLowAnchor;
        input.hudTextColorOffline = hudTextColorOffline;
        input.hudTextColorLive = hudTextColorLive;
        input.appBuildRevision = appBuildRevision;
        input.selectedFpsProvider = selectedFpsProvider;
        input.selectedProfileProvider = selectedProfileProvider;
        input.selectedEncoderProvider = selectedEncoderProvider;
        input.streamSizeProvider = streamSizeProvider;
        input.daemonReachableProvider = daemonReachableProvider;
        input.daemonStateProvider = daemonStateProvider;
        input.daemonHostNameProvider = daemonHostNameProvider;
        input.daemonBuildRevisionProvider = daemonBuildRevisionProvider;
        input.daemonLastErrorProvider = daemonLastErrorProvider;
        input.daemonRunIdProvider = daemonRunIdProvider;
        input.daemonUptimeSecProvider = daemonUptimeSecProvider;
        input.daemonStateUiProvider = daemonStateUiProvider;
        input.debugOverlayVisibleProvider = debugOverlayVisibleProvider;
        input.buildDebug = buildDebug;
        input.debugOverlayHandler = debugOverlayHandler;
        input.hudTextOnlyHandler = hudTextOnlyHandler;
        input.refreshDebugOverlayHandler = refreshDebugOverlayHandler;
        input.snapshotLogHandler = snapshotLogHandler;
        return input;
    }
}
