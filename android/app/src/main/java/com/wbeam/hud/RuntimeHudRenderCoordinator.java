package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

public final class RuntimeHudRenderCoordinator {
    private static final String MODE_RUNTIME = "runtime";

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
        HudOverlayDisplay.State overlayState = input.getHudOverlayState();
        long semanticSignature = buildRuntimeSemanticSignature(input, state);
        if (renderFromCacheIfFresh(input, overlayState, semanticSignature)) {
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
            return;
        }

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
        RuntimeHudOverlayRenderer.Rendered rendered = RuntimeHudOverlayPipeline.render(
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
        if (overlayState != null) {
            overlayState.setRuntimeSemanticSignature(semanticSignature);
            overlayState.setLastRuntimeTextFallback(rendered.textFallback == null ? "" : rendered.textFallback);
        }
    }

    private static boolean renderFromCacheIfFresh(
            Input input,
            HudOverlayDisplay.State overlayState,
            long semanticSignature
    ) {
        if (overlayState == null
                || overlayState.getRuntimeSemanticSignature() != semanticSignature
                || !MODE_RUNTIME.equals(overlayState.getMode())) {
            return false;
        }
        WebView webView = input.getPerfHudWebView();
        TextView textView = input.getPerfHudText();
        if (webView != null && overlayState.getLastWebHtml() != null && !overlayState.getLastWebHtml().isEmpty()) {
            HudOverlayDisplay.showWebOnly(
                    webView,
                    textView,
                    MODE_RUNTIME,
                    overlayState
            );
        } else if (textView != null && overlayState.getLastRuntimeTextFallback() != null) {
            HudOverlayDisplay.showTextOnly(
                    webView,
                    textView,
                    MODE_RUNTIME,
                    overlayState.getLastRuntimeTextFallback(),
                    input.getHudTextColorLive(),
                    overlayState
            );
        } else {
            return false;
        }
        View panel = input.getPerfHudPanel();
        if (panel != null) {
            panel.setAlpha(0.96f);
        }
        return true;
    }

    private static long buildRuntimeSemanticSignature(Input input, RuntimeHudUpdateState state) {
        long sig = 17L;
        sig = mix(sig, input.isDaemonReachable() ? 1L : 0L);
        sig = mix(sig, input.getStreamWidth());
        sig = mix(sig, input.getStreamHeight());
        sig = mix(sig, input.getState().qTMax);
        sig = mix(sig, input.getState().qDMax);
        sig = mix(sig, input.getState().qRMax);
        sig = mix(sig, state.qT);
        sig = mix(sig, state.qD);
        sig = mix(sig, state.qR);
        sig = mix(sig, state.adaptiveLevel);
        sig = mix(sig, state.drops);
        sig = mix(sig, state.bpHigh);
        sig = mix(sig, state.bpRecover);
        sig = mix(sig, quantize(state.targetFps, 2.0));
        sig = mix(sig, quantize(state.presentFps, 2.0));
        sig = mix(sig, quantize(state.recvFps, 2.0));
        sig = mix(sig, quantize(state.decodeFps, 2.0));
        sig = mix(sig, quantize(state.bitrateMbps, 2.0));
        sig = mix(sig, quantize(state.dropPerSec, 2.0));
        sig = mix(sig, quantize(state.e2eP95, 2.0));
        sig = mix(sig, quantize(state.decodeP95, 2.0));
        sig = mix(sig, quantize(state.renderP95, 2.0));
        sig = mix(sig, quantize(state.frametimeP95, 2.0));
        sig = mix(sig, safeHash(input.getSelectedProfile()));
        sig = mix(sig, safeHash(input.getSelectedEncoder()));
        sig = mix(sig, safeHash(input.getDaemonHostName()));
        sig = mix(sig, safeHash(input.getDaemonStateUi()));
        sig = mix(sig, safeHash(input.getDaemonBuildRevision()));
        sig = mix(sig, safeHash(input.getAppBuildRevision()));
        sig = mix(sig, safeHash(input.getDaemonLastError()));
        sig = mix(sig, safeHash(state.adaptiveAction));
        sig = mix(sig, safeHash(state.reason));
        sig = mix(sig, state.tuningActive ? 1L : 0L);
        sig = mix(sig, safeHash(state.tuningLine));
        sig = mix(sig, safeHash(state.pressureState != null ? state.pressureState.tone : ""));
        return sig;
    }

    private static long mix(long left, long right) {
        return (left * 31L) ^ right;
    }

    private static long quantize(double value, double stepsPerUnit) {
        if (!Double.isFinite(value)) {
            return 0L;
        }
        return Math.round(value * stepsPerUnit);
    }

    private static int safeHash(String value) {
        return value == null ? 0 : value.hashCode();
    }
}
