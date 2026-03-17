package com.wbeam.hud;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

import org.json.JSONObject;

public final class MainHudCoordinator {
    private static final long VISIBLE_HUD_RENDER_INTERVAL_MS = 700L;

    public interface IntProvider {
        int get();
    }

    public interface StringProvider {
        String get();
    }

    public interface StreamSizeProvider {
        int[] get();
    }

    public interface BoolProvider {
        boolean get();
    }

    public interface ToggleDebugOverlayHandler {
        void setDebugOverlayVisible(boolean visible);
    }

    public interface HudTextOnlyHandler {
        void show(String modeTag, String text, int color);
    }

    public interface RefreshHandler {
        void refresh();
    }

    public interface SnapshotLogHandler {
        void onSnapshot(String snapshot);
    }

    public static final class Input {
        private String logTag;
        private MainHudState state;
        private HudOverlayDisplay.State overlayState;

        private MetricSeriesBuffer runtimePresentSeries;
        private MetricSeriesBuffer runtimeMbpsSeries;
        private MetricSeriesBuffer runtimeDropSeries;
        private MetricSeriesBuffer runtimeLatencySeries;
        private MetricSeriesBuffer runtimeQueueSeries;
        private ResourceUsageTracker resourceUsageTracker;

        private WebView perfHudWebView;
        private TextView perfHudText;
        private View perfHudPanel;

        private int transportQueueMaxFrames;
        private int decodeQueueMaxFrames;
        private int renderQueueMaxFrames;
        private long presentFpsStaleGraceMs;
        private long metricsStaleGraceMs;
        private double fpsLowAnchor;
        private int hudTextColorOffline;
        private int hudTextColorLive;
        private String appBuildRevision;

        private IntProvider selectedFpsProvider;
        private StringProvider selectedProfileProvider;
        private StringProvider selectedEncoderProvider;
        private StreamSizeProvider streamSizeProvider;

        private BoolProvider daemonReachableProvider;
        private StringProvider daemonStateProvider;
        private StringProvider daemonHostNameProvider;
        private StringProvider daemonBuildRevisionProvider;
        private StringProvider daemonLastErrorProvider;
        private LongProvider daemonRunIdProvider;
        private LongProvider daemonUptimeSecProvider;
        private StringProvider daemonStateUiProvider;

        private BoolProvider debugOverlayVisibleProvider;
        private boolean buildDebug;

        private ToggleDebugOverlayHandler debugOverlayHandler;
        private HudTextOnlyHandler hudTextOnlyHandler;
        private RefreshHandler refreshDebugOverlayHandler;
        private SnapshotLogHandler snapshotLogHandler;

        public String getLogTag() {
            return logTag;
        }

        public void setLogTag(String logTag) {
            this.logTag = logTag;
        }

        public MainHudState getState() {
            return state;
        }

        public void setState(MainHudState state) {
            this.state = state;
        }

        public HudOverlayDisplay.State getOverlayState() {
            return overlayState;
        }

        public void setOverlayState(HudOverlayDisplay.State overlayState) {
            this.overlayState = overlayState;
        }

        public MetricSeriesBuffer getRuntimePresentSeries() {
            return runtimePresentSeries;
        }

        public void setRuntimePresentSeries(MetricSeriesBuffer runtimePresentSeries) {
            this.runtimePresentSeries = runtimePresentSeries;
        }

        public MetricSeriesBuffer getRuntimeMbpsSeries() {
            return runtimeMbpsSeries;
        }

        public void setRuntimeMbpsSeries(MetricSeriesBuffer runtimeMbpsSeries) {
            this.runtimeMbpsSeries = runtimeMbpsSeries;
        }

        public MetricSeriesBuffer getRuntimeDropSeries() {
            return runtimeDropSeries;
        }

        public void setRuntimeDropSeries(MetricSeriesBuffer runtimeDropSeries) {
            this.runtimeDropSeries = runtimeDropSeries;
        }

        public MetricSeriesBuffer getRuntimeLatencySeries() {
            return runtimeLatencySeries;
        }

        public void setRuntimeLatencySeries(MetricSeriesBuffer runtimeLatencySeries) {
            this.runtimeLatencySeries = runtimeLatencySeries;
        }

        public MetricSeriesBuffer getRuntimeQueueSeries() {
            return runtimeQueueSeries;
        }

        public void setRuntimeQueueSeries(MetricSeriesBuffer runtimeQueueSeries) {
            this.runtimeQueueSeries = runtimeQueueSeries;
        }

        public ResourceUsageTracker getResourceUsageTracker() {
            return resourceUsageTracker;
        }

        public void setResourceUsageTracker(ResourceUsageTracker resourceUsageTracker) {
            this.resourceUsageTracker = resourceUsageTracker;
        }

        public WebView getPerfHudWebView() {
            return perfHudWebView;
        }

        public void setPerfHudWebView(WebView perfHudWebView) {
            this.perfHudWebView = perfHudWebView;
        }

        public TextView getPerfHudText() {
            return perfHudText;
        }

        public void setPerfHudText(TextView perfHudText) {
            this.perfHudText = perfHudText;
        }

        public View getPerfHudPanel() {
            return perfHudPanel;
        }

        public void setPerfHudPanel(View perfHudPanel) {
            this.perfHudPanel = perfHudPanel;
        }

        public int getTransportQueueMaxFrames() {
            return transportQueueMaxFrames;
        }

        public void setTransportQueueMaxFrames(int transportQueueMaxFrames) {
            this.transportQueueMaxFrames = transportQueueMaxFrames;
        }

        public int getDecodeQueueMaxFrames() {
            return decodeQueueMaxFrames;
        }

        public void setDecodeQueueMaxFrames(int decodeQueueMaxFrames) {
            this.decodeQueueMaxFrames = decodeQueueMaxFrames;
        }

        public int getRenderQueueMaxFrames() {
            return renderQueueMaxFrames;
        }

        public void setRenderQueueMaxFrames(int renderQueueMaxFrames) {
            this.renderQueueMaxFrames = renderQueueMaxFrames;
        }

        public long getPresentFpsStaleGraceMs() {
            return presentFpsStaleGraceMs;
        }

        public void setPresentFpsStaleGraceMs(long presentFpsStaleGraceMs) {
            this.presentFpsStaleGraceMs = presentFpsStaleGraceMs;
        }

        public long getMetricsStaleGraceMs() {
            return metricsStaleGraceMs;
        }

        public void setMetricsStaleGraceMs(long metricsStaleGraceMs) {
            this.metricsStaleGraceMs = metricsStaleGraceMs;
        }

        public double getFpsLowAnchor() {
            return fpsLowAnchor;
        }

        public void setFpsLowAnchor(double fpsLowAnchor) {
            this.fpsLowAnchor = fpsLowAnchor;
        }

        public int getHudTextColorOffline() {
            return hudTextColorOffline;
        }

        public void setHudTextColorOffline(int hudTextColorOffline) {
            this.hudTextColorOffline = hudTextColorOffline;
        }

        public int getHudTextColorLive() {
            return hudTextColorLive;
        }

        public void setHudTextColorLive(int hudTextColorLive) {
            this.hudTextColorLive = hudTextColorLive;
        }

        public String getAppBuildRevision() {
            return appBuildRevision;
        }

        public void setAppBuildRevision(String appBuildRevision) {
            this.appBuildRevision = appBuildRevision;
        }

        public IntProvider getSelectedFpsProvider() {
            return selectedFpsProvider;
        }

        public void setSelectedFpsProvider(IntProvider selectedFpsProvider) {
            this.selectedFpsProvider = selectedFpsProvider;
        }

        public StringProvider getSelectedProfileProvider() {
            return selectedProfileProvider;
        }

        public void setSelectedProfileProvider(StringProvider selectedProfileProvider) {
            this.selectedProfileProvider = selectedProfileProvider;
        }

        public StringProvider getSelectedEncoderProvider() {
            return selectedEncoderProvider;
        }

        public void setSelectedEncoderProvider(StringProvider selectedEncoderProvider) {
            this.selectedEncoderProvider = selectedEncoderProvider;
        }

        public StreamSizeProvider getStreamSizeProvider() {
            return streamSizeProvider;
        }

        public void setStreamSizeProvider(StreamSizeProvider streamSizeProvider) {
            this.streamSizeProvider = streamSizeProvider;
        }

        public BoolProvider getDaemonReachableProvider() {
            return daemonReachableProvider;
        }

        public void setDaemonReachableProvider(BoolProvider daemonReachableProvider) {
            this.daemonReachableProvider = daemonReachableProvider;
        }

        public StringProvider getDaemonStateProvider() {
            return daemonStateProvider;
        }

        public void setDaemonStateProvider(StringProvider daemonStateProvider) {
            this.daemonStateProvider = daemonStateProvider;
        }

        public StringProvider getDaemonHostNameProvider() {
            return daemonHostNameProvider;
        }

        public void setDaemonHostNameProvider(StringProvider daemonHostNameProvider) {
            this.daemonHostNameProvider = daemonHostNameProvider;
        }

        public StringProvider getDaemonBuildRevisionProvider() {
            return daemonBuildRevisionProvider;
        }

        public void setDaemonBuildRevisionProvider(StringProvider daemonBuildRevisionProvider) {
            this.daemonBuildRevisionProvider = daemonBuildRevisionProvider;
        }

        public StringProvider getDaemonLastErrorProvider() {
            return daemonLastErrorProvider;
        }

        public void setDaemonLastErrorProvider(StringProvider daemonLastErrorProvider) {
            this.daemonLastErrorProvider = daemonLastErrorProvider;
        }

        public LongProvider getDaemonRunIdProvider() {
            return daemonRunIdProvider;
        }

        public void setDaemonRunIdProvider(LongProvider daemonRunIdProvider) {
            this.daemonRunIdProvider = daemonRunIdProvider;
        }

        public LongProvider getDaemonUptimeSecProvider() {
            return daemonUptimeSecProvider;
        }

        public void setDaemonUptimeSecProvider(LongProvider daemonUptimeSecProvider) {
            this.daemonUptimeSecProvider = daemonUptimeSecProvider;
        }

        public StringProvider getDaemonStateUiProvider() {
            return daemonStateUiProvider;
        }

        public void setDaemonStateUiProvider(StringProvider daemonStateUiProvider) {
            this.daemonStateUiProvider = daemonStateUiProvider;
        }

        public BoolProvider getDebugOverlayVisibleProvider() {
            return debugOverlayVisibleProvider;
        }

        public void setDebugOverlayVisibleProvider(BoolProvider debugOverlayVisibleProvider) {
            this.debugOverlayVisibleProvider = debugOverlayVisibleProvider;
        }

        public boolean isBuildDebug() {
            return buildDebug;
        }

        public void setBuildDebug(boolean buildDebug) {
            this.buildDebug = buildDebug;
        }

        public ToggleDebugOverlayHandler getDebugOverlayHandler() {
            return debugOverlayHandler;
        }

        public void setDebugOverlayHandler(ToggleDebugOverlayHandler debugOverlayHandler) {
            this.debugOverlayHandler = debugOverlayHandler;
        }

        public HudTextOnlyHandler getHudTextOnlyHandler() {
            return hudTextOnlyHandler;
        }

        public void setHudTextOnlyHandler(HudTextOnlyHandler hudTextOnlyHandler) {
            this.hudTextOnlyHandler = hudTextOnlyHandler;
        }

        public RefreshHandler getRefreshDebugOverlayHandler() {
            return refreshDebugOverlayHandler;
        }

        public void setRefreshDebugOverlayHandler(RefreshHandler refreshDebugOverlayHandler) {
            this.refreshDebugOverlayHandler = refreshDebugOverlayHandler;
        }

        public SnapshotLogHandler getSnapshotLogHandler() {
            return snapshotLogHandler;
        }

        public void setSnapshotLogHandler(SnapshotLogHandler snapshotLogHandler) {
            this.snapshotLogHandler = snapshotLogHandler;
        }
    }

    public interface LongProvider {
        long get();
    }

    private MainHudCoordinator() {
    }

    public static void updateUnavailable(Input input) {
        if (input.getPerfHudText() == null) {
            return;
        }
        boolean overlayVisible = isDebugOverlayVisible(input);
        RuntimeHudAvailabilityCoordinator.applyUnavailable(
                input.getSelectedFpsProvider().get(),
                input.getHudTextColorOffline(),
                RuntimeHudAvailabilityHooksFactory.create(
                        (targetFps, presentFps, uptimeSec, frameOutHost) -> {
                            input.getState().latestTargetFps = targetFps;
                            input.getState().latestPresentFps = presentFps;
                            input.getState().latestStreamUptimeSec = uptimeSec;
                            input.getState().latestFrameOutHost = frameOutHost;
                        },
                        line -> input.getState().compactLine = line,
                        (modeTag, text, color) -> {
                            if (overlayVisible) {
                                input.getHudTextOnlyHandler().show(modeTag, text, color);
                            }
                        },
                        alpha -> {
                            if (overlayVisible && input.getPerfHudPanel() != null) {
                                input.getPerfHudPanel().setAlpha(alpha);
                            }
                        },
                        () -> {
                            if (overlayVisible) {
                                input.getRefreshDebugOverlayHandler().refresh();
                            }
                        },
                        message -> {
                            if (overlayVisible) {
                                input.getSnapshotLogHandler().onSnapshot(message);
                            }
                        }
                )
        );
    }

    public static void update(Input input, JSONObject metrics) {
        if (input.getPerfHudText() == null) {
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        if (handleMissingMetrics(input, metrics, nowMs)) {
            return;
        }
        input.getState().lastPerfMetricsAtMs = nowMs;
        input.getOverlayState().setMode("runtime");
        updateRuntimeHud(input, metrics, nowMs);
    }

    private static boolean isDebugOverlayVisible(Input input) {
        return input.getDebugOverlayVisibleProvider() == null
                || input.getDebugOverlayVisibleProvider().get();
    }

    private static boolean shouldRenderVisibleOverlay(Input input, long nowMs) {
        HudOverlayDisplay.State overlayState = input.getOverlayState();
        return overlayState == null
                || !isVisibleHudRenderIntervalActive(overlayState, nowMs);
    }

    private static boolean isVisibleHudRenderIntervalActive(
            HudOverlayDisplay.State overlayState,
            long nowMs
    ) {
        return nowMs - overlayState.getLastRenderAtMs() < VISIBLE_HUD_RENDER_INTERVAL_MS;
    }

    private static boolean handleMissingMetrics(Input input, JSONObject metrics, long nowMs) {
        if (RuntimeHudAvailabilityCoordinator.shouldKeepLastMetrics(
                metrics,
                input.getDaemonReachableProvider().get(),
                input.getState().lastPerfMetricsAtMs,
                nowMs,
                input.getMetricsStaleGraceMs()
        )) {
            if (isDebugOverlayVisible(input)) {
                input.getSnapshotLogHandler().onSnapshot("state=metrics_stale grace=1");
            }
            return true;
        }
        if (metrics != null) {
            return false;
        }
        updateUnavailable(input);
        return true;
    }

    private static void updateRuntimeHud(Input input, JSONObject metrics, long nowMs) {
        RuntimeHudStateCoordinator.Input stateInput = new RuntimeHudStateCoordinator.Input();
        stateInput.setMetrics(metrics);
        stateInput.setSelectedFps(input.getSelectedFpsProvider().get());
        stateInput.setTransportQueueMaxFrames(input.getTransportQueueMaxFrames());
        stateInput.setDecodeQueueMaxFrames(input.getDecodeQueueMaxFrames());
        stateInput.setRenderQueueMaxFrames(input.getRenderQueueMaxFrames());
        stateInput.setNowMs(nowMs);
        stateInput.setStablePresentFps(input.getState().latestStablePresentFps);
        stateInput.setStablePresentFpsAtMs(input.getState().latestStablePresentFpsAtMs);
        stateInput.setPresentFpsStaleGraceMs(input.getPresentFpsStaleGraceMs());
        stateInput.setDropPrevCount(input.getState().runtimeDropPrevCount);
        stateInput.setDropPrevAtMs(input.getState().runtimeDropPrevAtMs);
        stateInput.setDaemonState(input.getDaemonStateProvider().get());
        stateInput.setLatestStreamUptimeSec(input.getState().latestStreamUptimeSec);
        stateInput.setLatestFrameOutHost(input.getState().latestFrameOutHost);
        stateInput.setDaemonRunId(input.getDaemonRunIdProvider().get());
        stateInput.setDaemonUptimeSec(input.getDaemonUptimeSecProvider().get());
        stateInput.setDaemonLastError(input.getDaemonLastErrorProvider().get());
        RuntimeHudStateCoordinator.Output output = RuntimeHudStateCoordinator.compute(stateInput);
        RuntimeHudUpdateState state = output.getState();

        input.getState().latestStablePresentFps = state.updatedStablePresentFps;
        input.getState().latestStablePresentFpsAtMs = state.updatedStablePresentFpsAtMs;
        input.getState().runtimeDropPrevCount = state.updatedDropPrevCount;
        input.getState().runtimeDropPrevAtMs = state.updatedDropPrevAtMs;

        input.getState().latestTargetFps = state.targetFps;
        input.getState().latestPresentFps = state.presentFps;
        input.getState().latestStreamUptimeSec = state.streamUptimeSec;
        input.getState().latestFrameOutHost = state.frameOutHost;

        input.getState().compactLine = output.getCompactLine();
        boolean overlayVisible = isDebugOverlayVisible(input);
        if (overlayVisible) {
            input.getRefreshDebugOverlayHandler().refresh();
        }

        if (output.getPressureLog() != null) {
            Log.w(input.getLogTag(), output.getPressureLog());
        }

        if (!overlayVisible || !shouldRenderVisibleOverlay(input, nowMs)) {
            RuntimeHudTrendComposer.appendSamples(
                    input.getRuntimePresentSeries(),
                    input.getRuntimeMbpsSeries(),
                    input.getRuntimeDropSeries(),
                    input.getRuntimeLatencySeries(),
                    input.getRuntimeQueueSeries(),
                    state.presentFps,
                    state.bitrateMbps,
                    state.dropPerSec,
                    state.e2eP95,
                    state.qT,
                    state.qD,
                    state.qR
            );
            if (overlayVisible) {
                input.getSnapshotLogHandler().onSnapshot(output.getDebugSnapshot());
            }
            return;
        }
        input.getOverlayState().setLastRenderAtMs(nowMs);

        int[] streamSize = input.getStreamSizeProvider().get();
        RuntimeHudRenderCoordinator.Input renderInput = new RuntimeHudRenderCoordinator.Input();
        renderInput.setRuntimePresentSeries(input.getRuntimePresentSeries());
        renderInput.setRuntimeMbpsSeries(input.getRuntimeMbpsSeries());
        renderInput.setRuntimeDropSeries(input.getRuntimeDropSeries());
        renderInput.setRuntimeLatencySeries(input.getRuntimeLatencySeries());
        renderInput.setRuntimeQueueSeries(input.getRuntimeQueueSeries());
        renderInput.setState(state);
        renderInput.setFpsLowAnchor(input.getFpsLowAnchor());
        renderInput.setDaemonReachable(input.getDaemonReachableProvider().get());
        renderInput.setSelectedProfile(input.getSelectedProfileProvider().get());
        renderInput.setSelectedEncoder(input.getSelectedEncoderProvider().get());
        renderInput.setStreamWidth(streamSize[0]);
        renderInput.setStreamHeight(streamSize[1]);
        renderInput.setDaemonHostName(input.getDaemonHostNameProvider().get());
        renderInput.setDaemonStateUi(output.getDaemonStateUi());
        renderInput.setDaemonBuildRevision(input.getDaemonBuildRevisionProvider().get());
        renderInput.setAppBuildRevision(input.getAppBuildRevision());
        renderInput.setDaemonLastError(input.getDaemonLastErrorProvider().get());
        renderInput.setTuningActive(state.tuningActive);
        renderInput.setTuningLine(state.tuningLine);
        renderInput.setResourceUsageTracker(input.getResourceUsageTracker());
        renderInput.setPerfHudWebView(input.getPerfHudWebView());
        renderInput.setPerfHudText(input.getPerfHudText());
        renderInput.setPerfHudPanel(input.getPerfHudPanel());
        renderInput.setHudOverlayState(input.getOverlayState());
        renderInput.setHudTextColorLive(input.getHudTextColorLive());
        RuntimeHudRenderCoordinator.render(renderInput);

        input.getSnapshotLogHandler().onSnapshot(output.getDebugSnapshot());
    }
}
