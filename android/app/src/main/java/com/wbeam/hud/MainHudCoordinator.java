package com.wbeam.hud;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

import org.json.JSONObject;

public final class MainHudCoordinator {
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

    /**
     * Plain data carrier for HUD input.
     */
    @SuppressWarnings("java:S1104")
    public static final class Input {
        public String logTag;
        public MainHudState state;
        public HudOverlayDisplay.State overlayState;

        public MetricSeriesBuffer runtimePresentSeries;
        public MetricSeriesBuffer runtimeMbpsSeries;
        public MetricSeriesBuffer runtimeDropSeries;
        public MetricSeriesBuffer runtimeLatencySeries;
        public MetricSeriesBuffer runtimeQueueSeries;
        public ResourceUsageTracker resourceUsageTracker;

        public WebView perfHudWebView;
        public TextView perfHudText;
        public View perfHudPanel;

        public int transportQueueMaxFrames;
        public int decodeQueueMaxFrames;
        public int renderQueueMaxFrames;
        public long presentFpsStaleGraceMs;
        public long metricsStaleGraceMs;
        public long trainerHudPayloadGraceMs;
        public double fpsLowAnchor;
        public int hudTextColorOffline;
        public int hudTextColorLive;
        public String appBuildRevision;

        public IntProvider selectedFpsProvider;
        public StringProvider selectedProfileProvider;
        public StringProvider selectedEncoderProvider;
        public StreamSizeProvider streamSizeProvider;

        public BoolProvider daemonReachableProvider;
        public StringProvider daemonStateProvider;
        public StringProvider daemonHostNameProvider;
        public StringProvider daemonBuildRevisionProvider;
        public StringProvider daemonLastErrorProvider;
        public LongProvider daemonRunIdProvider;
        public LongProvider daemonUptimeSecProvider;
        public StringProvider daemonStateUiProvider;

        public BoolProvider debugOverlayVisibleProvider;
        public boolean buildDebug;

        public ToggleDebugOverlayHandler debugOverlayHandler;
        public HudTextOnlyHandler hudTextOnlyHandler;
        public RefreshHandler refreshDebugOverlayHandler;
        public SnapshotLogHandler snapshotLogHandler;
    }

    public interface LongProvider {
        long get();
    }

    private MainHudCoordinator() {
    }

    public static void updateUnavailable(Input input) {
        if (input.perfHudText == null) {
            return;
        }
        RuntimeHudAvailabilityCoordinator.applyUnavailable(
                input.selectedFpsProvider.get(),
                input.hudTextColorOffline,
                RuntimeHudAvailabilityHooksFactory.create(
                        (targetFps, presentFps, uptimeSec, frameOutHost) -> {
                            input.state.latestTargetFps = targetFps;
                            input.state.latestPresentFps = presentFps;
                            input.state.latestStreamUptimeSec = uptimeSec;
                            input.state.latestFrameOutHost = frameOutHost;
                        },
                        line -> input.state.compactLine = line,
                        input.hudTextOnlyHandler::show,
                        alpha -> {
                            if (input.perfHudPanel != null) {
                                input.perfHudPanel.setAlpha(alpha);
                            }
                        },
                        input.refreshDebugOverlayHandler::refresh,
                        input.snapshotLogHandler::onSnapshot
                )
        );
    }

    public static void update(Input input, JSONObject metrics) {
        if (input.perfHudText == null) {
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        if (handleMissingMetrics(input, metrics, nowMs)) {
            return;
        }
        input.state.lastPerfMetricsAtMs = nowMs;
        if (handleTrainerHudPath(input, metrics, nowMs)) {
            return;
        }
        input.overlayState.mode = "runtime";
        updateRuntimeHud(input, metrics, nowMs);
    }

    private static boolean handleMissingMetrics(Input input, JSONObject metrics, long nowMs) {
        if (RuntimeHudAvailabilityCoordinator.shouldKeepLastMetrics(
                metrics,
                input.daemonReachableProvider.get(),
                input.state.lastPerfMetricsAtMs,
                nowMs,
                input.metricsStaleGraceMs
        )) {
            input.snapshotLogHandler.onSnapshot("state=metrics_stale grace=1");
            return true;
        }
        if (metrics != null) {
            return false;
        }
        updateUnavailable(input);
        return true;
    }

    private static boolean handleTrainerHudPath(Input input, JSONObject metrics, long nowMs) {
        TrainerHudModeCoordinator.State trainerState = new TrainerHudModeCoordinator.State();
        trainerState.lastPayloadAtMs = input.state.lastTrainerPayloadAtMs;
        trainerState.sessionActive = input.state.trainerSessionActive;
        boolean handled = TrainerHudModeCoordinator.handle(
                metrics,
                nowMs,
                trainerState,
                input.trainerHudPayloadGraceMs,
                input.buildDebug,
                input.debugOverlayVisibleProvider.get(),
                TrainerHudModeHooksFactory.create(
                        () -> input.debugOverlayHandler.setDebugOverlayVisible(true),
                        input.snapshotLogHandler::onSnapshot,
                        hudJson -> TrainerHudOverlayPipeline.renderFromJson(
                                hudJson,
                                input.state.latestTargetFps,
                                input.fpsLowAnchor,
                                input.resourceUsageTracker,
                                input.perfHudWebView,
                                input.perfHudText,
                                input.perfHudPanel,
                                input.overlayState,
                                input.hudTextColorLive,
                                compactLine -> input.state.compactLine = compactLine,
                                input.refreshDebugOverlayHandler::refresh
                        ),
                        rawHudText -> TrainerHudOverlayPipeline.renderFromText(
                                rawHudText,
                                input.state.latestTargetFps,
                                input.fpsLowAnchor,
                                input.resourceUsageTracker,
                                input.perfHudWebView,
                                input.perfHudText,
                                input.perfHudPanel,
                                input.overlayState,
                                input.hudTextColorLive,
                                compactLine -> input.state.compactLine = compactLine,
                                input.refreshDebugOverlayHandler::refresh
                        ),
                        () -> { },
                        () -> TrainerHudOverlayPipeline.renderPlaceholder(
                                input.state.latestTargetFps,
                                input.fpsLowAnchor,
                                input.resourceUsageTracker,
                                input.perfHudWebView,
                                input.perfHudText,
                                input.perfHudPanel,
                                input.overlayState,
                                input.hudTextColorLive,
                                compactLine -> input.state.compactLine = compactLine,
                                input.refreshDebugOverlayHandler::refresh
                        ),
                        () -> {
                            input.hudTextOnlyHandler.show(
                                    "trainer",
                                    "TRAINING HUD\nwaiting for trainer metrics...",
                                    input.hudTextColorLive
                            );
                            input.state.compactLine = "trainer hud waiting metrics";
                            input.refreshDebugOverlayHandler.refresh();
                        }
                )
        );
        input.state.lastTrainerPayloadAtMs = trainerState.lastPayloadAtMs;
        input.state.trainerSessionActive = trainerState.sessionActive;
        return handled;
    }

    private static void updateRuntimeHud(Input input, JSONObject metrics, long nowMs) {
        RuntimeHudStateCoordinator.Input stateInput = new RuntimeHudStateCoordinator.Input();
        stateInput.metrics = metrics;
        stateInput.selectedFps = input.selectedFpsProvider.get();
        stateInput.transportQueueMaxFrames = input.transportQueueMaxFrames;
        stateInput.decodeQueueMaxFrames = input.decodeQueueMaxFrames;
        stateInput.renderQueueMaxFrames = input.renderQueueMaxFrames;
        stateInput.nowMs = nowMs;
        stateInput.stablePresentFps = input.state.latestStablePresentFps;
        stateInput.stablePresentFpsAtMs = input.state.latestStablePresentFpsAtMs;
        stateInput.presentFpsStaleGraceMs = input.presentFpsStaleGraceMs;
        stateInput.dropPrevCount = input.state.runtimeDropPrevCount;
        stateInput.dropPrevAtMs = input.state.runtimeDropPrevAtMs;
        stateInput.daemonState = input.daemonStateProvider.get();
        stateInput.latestStreamUptimeSec = input.state.latestStreamUptimeSec;
        stateInput.latestFrameOutHost = input.state.latestFrameOutHost;
        stateInput.daemonRunId = input.daemonRunIdProvider.get();
        stateInput.daemonUptimeSec = input.daemonUptimeSecProvider.get();
        stateInput.daemonLastError = input.daemonLastErrorProvider.get();
        RuntimeHudStateCoordinator.Output output = RuntimeHudStateCoordinator.compute(stateInput);
        RuntimeHudUpdateState state = output.state;

        input.state.latestStablePresentFps = state.updatedStablePresentFps;
        input.state.latestStablePresentFpsAtMs = state.updatedStablePresentFpsAtMs;
        input.state.runtimeDropPrevCount = state.updatedDropPrevCount;
        input.state.runtimeDropPrevAtMs = state.updatedDropPrevAtMs;

        input.state.latestTargetFps = state.targetFps;
        input.state.latestPresentFps = state.presentFps;
        input.state.latestStreamUptimeSec = state.streamUptimeSec;
        input.state.latestFrameOutHost = state.frameOutHost;

        input.state.compactLine = output.compactLine;
        input.refreshDebugOverlayHandler.refresh();

        if (output.pressureLog != null) {
            Log.w(input.logTag, output.pressureLog);
        }

        int[] streamSize = input.streamSizeProvider.get();
        RuntimeHudRenderCoordinator.Input renderInput = new RuntimeHudRenderCoordinator.Input();
        renderInput.runtimePresentSeries = input.runtimePresentSeries;
        renderInput.runtimeMbpsSeries = input.runtimeMbpsSeries;
        renderInput.runtimeDropSeries = input.runtimeDropSeries;
        renderInput.runtimeLatencySeries = input.runtimeLatencySeries;
        renderInput.runtimeQueueSeries = input.runtimeQueueSeries;
        renderInput.state = state;
        renderInput.fpsLowAnchor = input.fpsLowAnchor;
        renderInput.daemonReachable = input.daemonReachableProvider.get();
        renderInput.selectedProfile = input.selectedProfileProvider.get();
        renderInput.selectedEncoder = input.selectedEncoderProvider.get();
        renderInput.streamWidth = streamSize[0];
        renderInput.streamHeight = streamSize[1];
        renderInput.daemonHostName = input.daemonHostNameProvider.get();
        renderInput.daemonStateUi = output.daemonStateUi;
        renderInput.daemonBuildRevision = input.daemonBuildRevisionProvider.get();
        renderInput.appBuildRevision = input.appBuildRevision;
        renderInput.daemonLastError = input.daemonLastErrorProvider.get();
        renderInput.resourceUsageTracker = input.resourceUsageTracker;
        renderInput.perfHudWebView = input.perfHudWebView;
        renderInput.perfHudText = input.perfHudText;
        renderInput.perfHudPanel = input.perfHudPanel;
        renderInput.hudOverlayState = input.overlayState;
        renderInput.hudTextColorLive = input.hudTextColorLive;
        RuntimeHudRenderCoordinator.render(renderInput);

        input.snapshotLogHandler.onSnapshot(output.debugSnapshot);
    }
}
