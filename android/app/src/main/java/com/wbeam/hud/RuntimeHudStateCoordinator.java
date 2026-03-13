package com.wbeam.hud;

import com.wbeam.telemetry.RuntimeTelemetryMapper;
import com.wbeam.ui.MainActivityRuntimeStateView;

import org.json.JSONObject;

public final class RuntimeHudStateCoordinator {
    /**
     * Plain data carrier for HUD state coordinator input.
     */
    @SuppressWarnings("java:S1104")
    public static final class Input {
        public JSONObject metrics;
        public int selectedFps;
        public int transportQueueMaxFrames;
        public int decodeQueueMaxFrames;
        public int renderQueueMaxFrames;
        public long nowMs;
        public double stablePresentFps;
        public long stablePresentFpsAtMs;
        public long presentFpsStaleGraceMs;
        public long dropPrevCount;
        public long dropPrevAtMs;
        public String daemonState;
        public long latestStreamUptimeSec;
        public long latestFrameOutHost;
        public long daemonRunId;
        public long daemonUptimeSec;
        public String daemonLastError;
    }

    /**
     * Plain data carrier for HUD state coordinator output.
     */
    @SuppressWarnings("java:S1104")
    public static final class Output {
        public RuntimeHudUpdateState state;
        public String daemonStateUi;
        public String compactLine;
        public String pressureLog;
        public String debugSnapshot;
    }

    private RuntimeHudStateCoordinator() {
    }

    public static Output compute(Input input) {
        RuntimeTelemetryMapper.Snapshot runtime = RuntimeTelemetryMapper.map(
                input.metrics,
                input.selectedFps,
                input.transportQueueMaxFrames,
                input.decodeQueueMaxFrames,
                input.renderQueueMaxFrames
        );
        RuntimeHudUpdateState state = RuntimeHudUpdateState.fromSnapshot(
                runtime,
                input.nowMs,
                input.stablePresentFps,
                input.stablePresentFpsAtMs,
                input.presentFpsStaleGraceMs,
                input.dropPrevCount,
                input.dropPrevAtMs
        );

        String daemonStateUi = MainActivityRuntimeStateView.effectiveDaemonState(
                input.daemonState,
                state.presentFps,
                state.streamUptimeSec,
                state.frameOutHost
        );
        Output output = new Output();
        output.state = state;
        output.daemonStateUi = daemonStateUi;
        output.compactLine = RuntimeHudComputation.formatCompactHudLine(
                state.targetFps,
                state.presentFps,
                state.e2eP95,
                state.decodeP95,
                state.renderP95,
                state.qT,
                state.qD,
                state.qR
        );
        if (state.pressureState.highPressure) {
            output.pressureLog = RuntimeHudComputation.formatHighPressureLog(
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
            );
        }
        output.debugSnapshot = RuntimeHudComputation.buildRuntimeDebugSnapshot(
                daemonStateUi,
                input.daemonRunId,
                input.daemonUptimeSec,
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
                input.daemonLastError
        );
        return output;
    }
}
