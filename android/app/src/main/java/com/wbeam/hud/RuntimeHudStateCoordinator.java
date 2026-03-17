package com.wbeam.hud;

import com.wbeam.telemetry.RuntimeTelemetryMapper;
import com.wbeam.ui.MainActivityRuntimeStateView;

import org.json.JSONObject;

public final class RuntimeHudStateCoordinator {
    public static final class Input {
        private JSONObject metrics;
        private int selectedFps;
        private int transportQueueMaxFrames;
        private int decodeQueueMaxFrames;
        private int renderQueueMaxFrames;
        private long nowMs;
        private double stablePresentFps;
        private long stablePresentFpsAtMs;
        private long presentFpsStaleGraceMs;
        private long dropPrevCount;
        private long dropPrevAtMs;
        private String daemonState;
        private long latestStreamUptimeSec;
        private long latestFrameOutHost;
        private long daemonRunId;
        private long daemonUptimeSec;
        private String daemonLastError;

        public JSONObject getMetrics() {
            return metrics;
        }

        public void setMetrics(JSONObject metrics) {
            this.metrics = metrics;
        }

        public int getSelectedFps() {
            return selectedFps;
        }

        public void setSelectedFps(int selectedFps) {
            this.selectedFps = selectedFps;
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

        public long getNowMs() {
            return nowMs;
        }

        public void setNowMs(long nowMs) {
            this.nowMs = nowMs;
        }

        public double getStablePresentFps() {
            return stablePresentFps;
        }

        public void setStablePresentFps(double stablePresentFps) {
            this.stablePresentFps = stablePresentFps;
        }

        public long getStablePresentFpsAtMs() {
            return stablePresentFpsAtMs;
        }

        public void setStablePresentFpsAtMs(long stablePresentFpsAtMs) {
            this.stablePresentFpsAtMs = stablePresentFpsAtMs;
        }

        public long getPresentFpsStaleGraceMs() {
            return presentFpsStaleGraceMs;
        }

        public void setPresentFpsStaleGraceMs(long presentFpsStaleGraceMs) {
            this.presentFpsStaleGraceMs = presentFpsStaleGraceMs;
        }

        public long getDropPrevCount() {
            return dropPrevCount;
        }

        public void setDropPrevCount(long dropPrevCount) {
            this.dropPrevCount = dropPrevCount;
        }

        public long getDropPrevAtMs() {
            return dropPrevAtMs;
        }

        public void setDropPrevAtMs(long dropPrevAtMs) {
            this.dropPrevAtMs = dropPrevAtMs;
        }

        public String getDaemonState() {
            return daemonState;
        }

        public void setDaemonState(String daemonState) {
            this.daemonState = daemonState;
        }

        public long getLatestStreamUptimeSec() {
            return latestStreamUptimeSec;
        }

        public void setLatestStreamUptimeSec(long latestStreamUptimeSec) {
            this.latestStreamUptimeSec = latestStreamUptimeSec;
        }

        public long getLatestFrameOutHost() {
            return latestFrameOutHost;
        }

        public void setLatestFrameOutHost(long latestFrameOutHost) {
            this.latestFrameOutHost = latestFrameOutHost;
        }

        public long getDaemonRunId() {
            return daemonRunId;
        }

        public void setDaemonRunId(long daemonRunId) {
            this.daemonRunId = daemonRunId;
        }

        public long getDaemonUptimeSec() {
            return daemonUptimeSec;
        }

        public void setDaemonUptimeSec(long daemonUptimeSec) {
            this.daemonUptimeSec = daemonUptimeSec;
        }

        public String getDaemonLastError() {
            return daemonLastError;
        }

        public void setDaemonLastError(String daemonLastError) {
            this.daemonLastError = daemonLastError;
        }
    }

    public static final class Output {
        private RuntimeHudUpdateState state;
        private String daemonStateUi;
        private String compactLine;
        private String pressureLog;
        private String debugSnapshot;

        public RuntimeHudUpdateState getState() {
            return state;
        }

        public void setState(RuntimeHudUpdateState state) {
            this.state = state;
        }

        public String getDaemonStateUi() {
            return daemonStateUi;
        }

        public void setDaemonStateUi(String daemonStateUi) {
            this.daemonStateUi = daemonStateUi;
        }

        public String getCompactLine() {
            return compactLine;
        }

        public void setCompactLine(String compactLine) {
            this.compactLine = compactLine;
        }

        public String getPressureLog() {
            return pressureLog;
        }

        public void setPressureLog(String pressureLog) {
            this.pressureLog = pressureLog;
        }

        public String getDebugSnapshot() {
            return debugSnapshot;
        }

        public void setDebugSnapshot(String debugSnapshot) {
            this.debugSnapshot = debugSnapshot;
        }
    }

    private RuntimeHudStateCoordinator() {
    }

    public static Output compute(Input input) {
        RuntimeTelemetryMapper.Snapshot runtime = RuntimeTelemetryMapper.map(
                input.getMetrics(),
                input.getSelectedFps(),
                input.getTransportQueueMaxFrames(),
                input.getDecodeQueueMaxFrames(),
                input.getRenderQueueMaxFrames()
        );
        RuntimeHudUpdateState state = RuntimeHudUpdateState.fromSnapshot(
                runtime,
                input.getNowMs(),
                input.getStablePresentFps(),
                input.getStablePresentFpsAtMs(),
                input.getPresentFpsStaleGraceMs(),
                input.getDropPrevCount(),
                input.getDropPrevAtMs()
        );

        String daemonStateUi = MainActivityRuntimeStateView.effectiveDaemonState(
                input.getDaemonState(),
                state.presentFps,
                state.streamUptimeSec,
                state.frameOutHost
        );
        Output output = new Output();
        output.setState(state);
        output.setDaemonStateUi(daemonStateUi);
        output.setCompactLine(RuntimeHudComputation.formatCompactHudLine(
                state.targetFps,
                state.presentFps,
                state.e2eP95,
                state.decodeP95,
                state.renderP95,
                state.qT,
                state.qD,
                state.qR
        ));
        if (state.pressureState.highPressure) {
            output.setPressureLog(RuntimeHudComputation.formatHighPressureLog(
                    state.pressureState,
                    state.decodeP95,
                    state.renderP95,
                    state.qT,
                    state.qD,
                    state.qR,
                    state.qTMax,
                    state.qDMax,
                    state.qRMax,
                    state.presentFps,
                    state.streamUptimeSec
            ));
        }
        output.setDebugSnapshot(RuntimeHudComputation.buildRuntimeDebugSnapshot(
                daemonStateUi,
                input.getDaemonRunId(),
                input.getDaemonUptimeSec(),
                state.streamUptimeSec,
                state.frameInHost,
                state.frameOutHost,
                state.targetFps,
                state.presentFps,
                state.frametimeP95,
                state.decodeP95,
                state.renderP95,
                state.e2eP95,
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
                state.pressureState,
                state.reason,
                input.getDaemonLastError()
        ));
        return output;
    }
}
