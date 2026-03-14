package com.wbeam.hud;

import com.wbeam.telemetry.RuntimeTelemetryMapper;

public final class RuntimeHudUpdateState {
    public final long frameInHost;
    public final long frameOutHost;
    public final long streamUptimeSec;

    public final double targetFps;
    public final double presentFps;
    public final double recvFps;
    public final double decodeFps;
    public final double frametimeP95;
    public final double decodeP95;
    public final double renderP95;
    public final double e2eP95;

    public final int qT;
    public final int qD;
    public final int qR;
    public final int qTMax;
    public final int qDMax;
    public final int qRMax;

    public final int adaptiveLevel;
    public final String adaptiveAction;
    public final long drops;
    public final long bpHigh;
    public final long bpRecover;
    public final String reason;
    public final boolean tuningActive;
    public final String tuningLine;

    public final double bitrateMbps;
    public final RuntimeHudComputation.PressureState pressureState;
    public final double dropPerSec;
    public final double updatedStablePresentFps;
    public final long updatedStablePresentFpsAtMs;
    public final long updatedDropPrevCount;
    public final long updatedDropPrevAtMs;

    private RuntimeHudUpdateState(
            long frameInHost,
            long frameOutHost,
            long streamUptimeSec,
            double targetFps,
            double presentFps,
            double recvFps,
            double decodeFps,
            double frametimeP95,
            double decodeP95,
            double renderP95,
            double e2eP95,
            int qT,
            int qD,
            int qR,
            int qTMax,
            int qDMax,
            int qRMax,
            int adaptiveLevel,
            String adaptiveAction,
            long drops,
            long bpHigh,
            long bpRecover,
            String reason,
            boolean tuningActive,
            String tuningLine,
            double bitrateMbps,
            RuntimeHudComputation.PressureState pressureState,
            double dropPerSec,
            double updatedStablePresentFps,
            long updatedStablePresentFpsAtMs,
            long updatedDropPrevCount,
            long updatedDropPrevAtMs
    ) {
        this.frameInHost = frameInHost;
        this.frameOutHost = frameOutHost;
        this.streamUptimeSec = streamUptimeSec;
        this.targetFps = targetFps;
        this.presentFps = presentFps;
        this.recvFps = recvFps;
        this.decodeFps = decodeFps;
        this.frametimeP95 = frametimeP95;
        this.decodeP95 = decodeP95;
        this.renderP95 = renderP95;
        this.e2eP95 = e2eP95;
        this.qT = qT;
        this.qD = qD;
        this.qR = qR;
        this.qTMax = qTMax;
        this.qDMax = qDMax;
        this.qRMax = qRMax;
        this.adaptiveLevel = adaptiveLevel;
        this.adaptiveAction = adaptiveAction;
        this.drops = drops;
        this.bpHigh = bpHigh;
        this.bpRecover = bpRecover;
        this.reason = reason;
        this.tuningActive = tuningActive;
        this.tuningLine = tuningLine;
        this.bitrateMbps = bitrateMbps;
        this.pressureState = pressureState;
        this.dropPerSec = dropPerSec;
        this.updatedStablePresentFps = updatedStablePresentFps;
        this.updatedStablePresentFpsAtMs = updatedStablePresentFpsAtMs;
        this.updatedDropPrevCount = updatedDropPrevCount;
        this.updatedDropPrevAtMs = updatedDropPrevAtMs;
    }

    public static RuntimeHudUpdateState fromSnapshot(
            RuntimeTelemetryMapper.Snapshot runtime,
            long nowMs,
            double latestStablePresentFps,
            long latestStablePresentFpsAtMs,
            long presentFpsStaleGraceMs,
            long runtimeDropPrevCount,
            long runtimeDropPrevAtMs
    ) {
        RuntimeHudComputation.FpsStabilizationResult fpsStabilization =
                RuntimeHudComputation.stabilizePresentFps(
                        runtime,
                        nowMs,
                        latestStablePresentFps,
                        latestStablePresentFpsAtMs,
                        presentFpsStaleGraceMs
                );

        RuntimeHudComputation.PressureState pressureState = RuntimeHudComputation.evaluatePressure(
                runtime.targetFps,
                fpsStabilization.presentFps,
                runtime.decodeP95,
                runtime.renderP95,
                runtime.qT,
                runtime.qD,
                runtime.qR,
                runtime.qTMax,
                runtime.qDMax,
                runtime.qRMax,
                runtime.adaptiveAction,
                runtime.streamUptimeSec
        );

        RuntimeHudComputation.DropRateResult dropRate =
                RuntimeHudComputation.computeDropRatePerSec(
                        runtime.latestDroppedFrames,
                        runtime.latestTooLateFrames,
                        nowMs,
                        runtimeDropPrevCount,
                        runtimeDropPrevAtMs
                );

        return new RuntimeHudUpdateState(
                runtime.frameInHost,
                runtime.frameOutHost,
                runtime.streamUptimeSec,
                runtime.targetFps,
                fpsStabilization.presentFps,
                runtime.recvFps,
                runtime.decodeFps,
                runtime.frametimeP95,
                runtime.decodeP95,
                runtime.renderP95,
                runtime.e2eP95,
                runtime.qT,
                runtime.qD,
                runtime.qR,
                runtime.qTMax,
                runtime.qDMax,
                runtime.qRMax,
                runtime.adaptiveLevel,
                runtime.adaptiveAction,
                runtime.drops,
                runtime.bpHigh,
                runtime.bpRecover,
                runtime.reason,
                runtime.tuningActive,
                runtime.tuningLine,
                runtime.bitrateMbps,
                pressureState,
                dropRate.dropPerSec,
                fpsStabilization.updatedStablePresentFps,
                fpsStabilization.updatedStablePresentFpsAtMs,
                dropRate.updatedPrevCount,
                dropRate.updatedPrevAtMs
        );
    }
}
