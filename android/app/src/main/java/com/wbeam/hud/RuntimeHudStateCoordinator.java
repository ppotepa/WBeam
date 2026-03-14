package com.wbeam.hud;

import com.wbeam.telemetry.RuntimeTelemetryMapper;
import com.wbeam.ui.MainActivityRuntimeStateView;

import org.json.JSONObject;

public final class RuntimeHudStateCoordinator {
    /**
     * Plain data carrier for HUD state coordinator input.
     */
    static final class Input {
        JSONObject metrics;
        int selectedFps;
        int transportQueueMaxFrames;
        int decodeQueueMaxFrames;
        int renderQueueMaxFrames;
        long nowMs;
        double stablePresentFps;
        long stablePresentFpsAtMs;
        long presentFpsStaleGraceMs;
        long dropPrevCount;
        long dropPrevAtMs;
        String daemonState;
        long latestStreamUptimeSec;
        long latestFrameOutHost;
        long daemonRunId;
        long daemonUptimeSec;
        String daemonLastError;
    }

    /**
     * Plain data carrier for HUD state coordinator output.
     */
    static final class Output {
        RuntimeHudUpdateState state;
        String daemonStateUi;
        String compactLine;
        String pressureLog;
        String debugSnapshot;
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
