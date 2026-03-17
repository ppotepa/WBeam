package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

public final class RuntimeHudRenderCoordinator {
    public static final class Input {
        private MetricSeriesBuffer runtimePresentSeries;
        private MetricSeriesBuffer runtimeMbpsSeries;
        private MetricSeriesBuffer runtimeDropSeries;
        private MetricSeriesBuffer runtimeLatencySeries;
        private MetricSeriesBuffer runtimeQueueSeries;
        private RuntimeHudUpdateState state;
        private double fpsLowAnchor;

        private boolean daemonReachable;
        private String selectedProfile;
        private String selectedEncoder;
        private int streamWidth;
        private int streamHeight;
        private String daemonHostName;
        private String daemonStateUi;
        private String daemonBuildRevision;
        private String appBuildRevision;
        private String daemonLastError;
        private boolean tuningActive;
        private String tuningLine;
        private ResourceUsageTracker resourceUsageTracker;
        private WebView perfHudWebView;
        private TextView perfHudText;
        private View perfHudPanel;
        private HudOverlayDisplay.State hudOverlayState;
        private int hudTextColorLive;

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

        public RuntimeHudUpdateState getState() {
            return state;
        }

        public void setState(RuntimeHudUpdateState state) {
            this.state = state;
        }

        public double getFpsLowAnchor() {
            return fpsLowAnchor;
        }

        public void setFpsLowAnchor(double fpsLowAnchor) {
            this.fpsLowAnchor = fpsLowAnchor;
        }

        public boolean isDaemonReachable() {
            return daemonReachable;
        }

        public void setDaemonReachable(boolean daemonReachable) {
            this.daemonReachable = daemonReachable;
        }

        public String getSelectedProfile() {
            return selectedProfile;
        }

        public void setSelectedProfile(String selectedProfile) {
            this.selectedProfile = selectedProfile;
        }

        public String getSelectedEncoder() {
            return selectedEncoder;
        }

        public void setSelectedEncoder(String selectedEncoder) {
            this.selectedEncoder = selectedEncoder;
        }

        public int getStreamWidth() {
            return streamWidth;
        }

        public void setStreamWidth(int streamWidth) {
            this.streamWidth = streamWidth;
        }

        public int getStreamHeight() {
            return streamHeight;
        }

        public void setStreamHeight(int streamHeight) {
            this.streamHeight = streamHeight;
        }

        public String getDaemonHostName() {
            return daemonHostName;
        }

        public void setDaemonHostName(String daemonHostName) {
            this.daemonHostName = daemonHostName;
        }

        public String getDaemonStateUi() {
            return daemonStateUi;
        }

        public void setDaemonStateUi(String daemonStateUi) {
            this.daemonStateUi = daemonStateUi;
        }

        public String getDaemonBuildRevision() {
            return daemonBuildRevision;
        }

        public void setDaemonBuildRevision(String daemonBuildRevision) {
            this.daemonBuildRevision = daemonBuildRevision;
        }

        public String getAppBuildRevision() {
            return appBuildRevision;
        }

        public void setAppBuildRevision(String appBuildRevision) {
            this.appBuildRevision = appBuildRevision;
        }

        public String getDaemonLastError() {
            return daemonLastError;
        }

        public void setDaemonLastError(String daemonLastError) {
            this.daemonLastError = daemonLastError;
        }

        public boolean isTuningActive() {
            return tuningActive;
        }

        public void setTuningActive(boolean tuningActive) {
            this.tuningActive = tuningActive;
        }

        public String getTuningLine() {
            return tuningLine;
        }

        public void setTuningLine(String tuningLine) {
            this.tuningLine = tuningLine;
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

        public HudOverlayDisplay.State getHudOverlayState() {
            return hudOverlayState;
        }

        public void setHudOverlayState(HudOverlayDisplay.State hudOverlayState) {
            this.hudOverlayState = hudOverlayState;
        }

        public int getHudTextColorLive() {
            return hudTextColorLive;
        }

        public void setHudTextColorLive(int hudTextColorLive) {
            this.hudTextColorLive = hudTextColorLive;
        }
    }

    private RuntimeHudRenderCoordinator() {
    }

    public static void render(Input input) {
        RuntimeHudUpdateState state = input.getState();
        String runtimeChartsHtml = RuntimeHudTrendComposer.appendSamplesAndBuildHtml(
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
                state.qR,
                state.pressureState.tone,
                input.getFpsLowAnchor()
        );
        RuntimeHudOverlayPipeline.render(
                input.isDaemonReachable(),
                input.getSelectedProfile(),
                input.getSelectedEncoder(),
                input.getStreamWidth(),
                input.getStreamHeight(),
                input.getDaemonHostName(),
                input.getDaemonStateUi(),
                input.getDaemonBuildRevision(),
                input.getAppBuildRevision(),
                input.getDaemonLastError(),
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
                state.tuningActive,
                state.tuningLine,
                runtimeChartsHtml,
                state.pressureState.tone,
                input.getResourceUsageTracker(),
                input.getPerfHudWebView(),
                input.getPerfHudText(),
                input.getPerfHudPanel(),
                input.getHudOverlayState(),
                input.getHudTextColorLive()
        );
    }
}
