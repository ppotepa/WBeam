package com.wbeam.hud;

import com.wbeam.telemetry.RuntimeTelemetryMapper;

public final class RuntimeHudComputation {
    public static final class FpsStabilizationResult {
        public final double presentFps;
        public final double updatedStablePresentFps;
        public final long updatedStablePresentFpsAtMs;

        FpsStabilizationResult(
                double presentFps,
                double updatedStablePresentFps,
                long updatedStablePresentFpsAtMs
        ) {
            this.presentFps = presentFps;
            this.updatedStablePresentFps = updatedStablePresentFps;
            this.updatedStablePresentFpsAtMs = updatedStablePresentFpsAtMs;
        }
    }

    public static final class PressureState {
        public final boolean warmingUp;
        public final boolean highPressure;
        public final String reason;
        public final String tone;

        PressureState(boolean warmingUp, boolean highPressure, String reason, String tone) {
            this.warmingUp = warmingUp;
            this.highPressure = highPressure;
            this.reason = reason;
            this.tone = tone;
        }
    }

    public static final class DropRateResult {
        public final double dropPerSec;
        public final long updatedPrevCount;
        public final long updatedPrevAtMs;

        DropRateResult(double dropPerSec, long updatedPrevCount, long updatedPrevAtMs) {
            this.dropPerSec = dropPerSec;
            this.updatedPrevCount = updatedPrevCount;
            this.updatedPrevAtMs = updatedPrevAtMs;
        }
    }

    private RuntimeHudComputation() {
    }

    public static FpsStabilizationResult stabilizePresentFps(
            RuntimeTelemetryMapper.Snapshot runtime,
            long nowMs,
            double latestStablePresentFps,
            long latestStablePresentFpsAtMs,
            long staleGraceMs
    ) {
        double presentFps = runtime.getPresentFps();
        boolean hasFlowSignals =
                runtime.getStreamUptimeSec() > 0
                        || runtime.getFrameOutHost() > 0
                        || runtime.getRecvFps() >= 1.0
                        || runtime.getDecodeFps() >= 1.0;
        if (presentFps >= 1.0) {
            return new FpsStabilizationResult(presentFps, presentFps, nowMs);
        }
        if (hasFlowSignals
                && latestStablePresentFps >= 1.0
                && (nowMs - latestStablePresentFpsAtMs) <= staleGraceMs) {
            return new FpsStabilizationResult(
                    latestStablePresentFps,
                    latestStablePresentFps,
                    latestStablePresentFpsAtMs
            );
        }
        return new FpsStabilizationResult(
                presentFps,
                latestStablePresentFps,
                latestStablePresentFpsAtMs
        );
    }

    @SuppressWarnings({"java:S107", "java:S3776", "java:S3358"})
    public static PressureState evaluatePressure(
            double targetFps,
            double presentFps,
            double decodeP95,
            double renderP95,
            int qT,
            int qD,
            int qR,
            int qTMax,
            int qDMax,
            int qRMax,
            String adaptiveAction,
            long streamUptimeSec
    ) {
        boolean warmingUp = presentFps < 1.0 && streamUptimeSec < 5;
        StringBuilder pressureReasonBuilder = new StringBuilder();
        double fpsFloor = targetFps * 0.90;
        boolean fpsUnderPressure = presentFps > 0.0 && presentFps < fpsFloor;
        boolean timingPressure = decodeP95 > 12.0 || renderP95 > 7.0;
        boolean queuePressure = qT >= qTMax || qD >= qDMax || qR >= qRMax;
        if (!warmingUp) {
            if (fpsUnderPressure) {
                pressureReasonBuilder.append("fps<").append(fmt1(fpsFloor));
            }
            if (decodeP95 > 12.0) {
                appendPressureSegment(
                        pressureReasonBuilder,
                        "dec>12(" + fmt1(decodeP95) + ")"
                );
            }
            if (renderP95 > 7.0) {
                appendPressureSegment(
                        pressureReasonBuilder,
                        "ren>7(" + fmt1(renderP95) + ")"
                );
            }
            if (qT >= qTMax) {
                appendPressureSegment(pressureReasonBuilder, "qT=" + qT + "/" + qTMax);
            }
            if (qD >= qDMax) {
                appendPressureSegment(pressureReasonBuilder, "qD=" + qD + "/" + qDMax);
            }
            if (qR >= qRMax) {
                appendPressureSegment(pressureReasonBuilder, "qR=" + qR + "/" + qRMax);
            }
        }
        String pressureReason = pressureReasonBuilder.length() > 0
                ? pressureReasonBuilder.toString()
                : (warmingUp ? "warmup" : "ok");

        boolean highPressure = !warmingUp && fpsUnderPressure && (timingPressure || queuePressure);
        boolean mediumPressure = !warmingUp
                && (adaptiveAction.startsWith("degrade")
                || fpsUnderPressure
                || timingPressure
                || queuePressure);
        String runtimeTone = "ok";
        if (highPressure) {
            runtimeTone = "risk";
        } else if (warmingUp || mediumPressure) {
            runtimeTone = "warn";
        }
        return new PressureState(warmingUp, highPressure, pressureReason, runtimeTone);
    }

    public static DropRateResult computeDropRatePerSec(
            long latestDroppedFrames,
            long latestTooLateFrames,
            long nowMs,
            long runtimeDropPrevCount,
            long runtimeDropPrevAtMs
    ) {
        if (latestDroppedFrames < 0L) {
            return new DropRateResult(0.0, runtimeDropPrevCount, runtimeDropPrevAtMs);
        }
        long combined = latestDroppedFrames + Math.max(0L, latestTooLateFrames);
        double dropPerSec = 0.0;
        if (runtimeDropPrevCount >= 0L && runtimeDropPrevAtMs > 0L && nowMs > runtimeDropPrevAtMs) {
            long deltaFrames = Math.max(0L, combined - runtimeDropPrevCount);
            long deltaMs = Math.max(1L, nowMs - runtimeDropPrevAtMs);
            dropPerSec = (deltaFrames * 1000.0) / deltaMs;
        }
        return new DropRateResult(dropPerSec, combined, nowMs);
    }

    public static String compactHostError(String daemonLastError, int maxLen) {
        if (daemonLastError == null || daemonLastError.isEmpty()) {
            return "-";
        }
        return daemonLastError.length() > maxLen
                ? daemonLastError.substring(0, maxLen) + "..."
                : daemonLastError;
    }

    @SuppressWarnings("java:S107")
    public static String formatCompactHudLine(
            double targetFps,
            double presentFps,
            double e2eP95,
            double decodeP95,
            double renderP95,
            int qT,
            int qD,
            int qR
    ) {
        StringBuilder line = new StringBuilder(96);
        line.append("hud fps ")
                .append(fmt0(targetFps))
                .append('/')
                .append(fmt1(presentFps))
                .append(" | e2e ")
                .append(fmt1(e2eP95))
                .append("ms | dec ")
                .append(fmt1(decodeP95))
                .append("ms | ren ")
                .append(fmt1(renderP95))
                .append("ms | q ")
                .append(qT)
                .append('/')
                .append(qD)
                .append('/')
                .append(qR);
        return line.toString();
    }

    @SuppressWarnings("java:S107")
    public static String formatHighPressureLog(
            PressureState pressureState,
            double decodeP95,
            double renderP95,
            int qT,
            int qD,
            int qR,
            int qTMax,
            int qDMax,
            int qRMax,
            double presentFps,
            long streamUptimeSec
    ) {
        return "HUD RED warmingUp=" + pressureState.warmingUp + " hp=" + pressureState.reason
                + " dec_p95=" + fmt2(decodeP95)
                + " ren_p95=" + fmt2(renderP95)
                + " qT=" + qT + "/" + qTMax
                + " qD=" + qD + "/" + qDMax
                + " qR=" + qR + "/" + qRMax
                + " fps_present=" + fmt1(presentFps)
                + " stream_up=" + streamUptimeSec + "s";
    }

    @SuppressWarnings("java:S107")
    public static String buildRuntimeDebugSnapshot(
            String daemonStateUi,
            long daemonRunId,
            long daemonUptimeSec,
            long streamUptimeSec,
            long frameInHost,
            long frameOutHost,
            double targetFps,
            double presentFps,
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
            PressureState pressureState,
            String reason,
            String daemonLastError
    ) {
        StringBuilder snapshot = new StringBuilder(320);
        snapshot.append("state=").append(daemonStateUi)
                .append(" run_id=").append(daemonRunId)
                .append(" up=").append(daemonUptimeSec).append('s')
                .append(" stream_up=").append(streamUptimeSec).append('s')
                .append(" host_in_out=").append(frameInHost).append('/').append(frameOutHost)
                .append(" fps_target=").append(fmt0(targetFps))
                .append(" fps_present=").append(fmt1(presentFps))
                .append(" frame_p95=").append(fmt2(frametimeP95))
                .append(" dec_p95=").append(fmt2(decodeP95))
                .append(" ren_p95=").append(fmt2(renderP95))
                .append(" e2e_p95=").append(fmt2(e2eP95))
                .append(" q=").append(qT).append('/').append(qD).append('/').append(qR)
                .append(" qmax=").append(qTMax).append('/').append(qDMax).append('/').append(qRMax)
                .append(" adapt=L").append(adaptiveLevel).append(':').append(adaptiveAction)
                .append(" drops=").append(drops)
                .append(" bp=").append(bpHigh).append('/').append(bpRecover)
                .append(" warmup=").append(pressureState.warmingUp)
                .append(" hp=").append(pressureState.reason)
                .append(" reason=").append(reason.isEmpty() ? "-" : reason)
                .append(" host_err=").append(compactHostError(daemonLastError, 44));
        return snapshot.toString();
    }

    private static void appendPressureSegment(StringBuilder sb, String segment) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(segment);
    }

    private static String fmt0(double value) {
        return formatFixed(value, 0);
    }

    private static String fmt1(double value) {
        return formatFixed(value, 1);
    }

    private static String fmt2(double value) {
        return formatFixed(value, 2);
    }

    private static String formatFixed(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        int safeDecimals = Math.max(0, Math.min(3, decimals));
        long factor;
        switch (safeDecimals) {
            case 0:
                factor = 1L;
                break;
            case 1:
                factor = 10L;
                break;
            case 2:
                factor = 100L;
                break;
            default:
                factor = 1000L;
                break;
        }
        long rounded = Math.round(value * factor);
        long abs = Math.abs(rounded);
        long whole = abs / factor;
        long fraction = abs % factor;
        StringBuilder out = new StringBuilder();
        if (rounded < 0L) {
            out.append('-');
        }
        out.append(whole);
        if (safeDecimals == 0) {
            return out.toString();
        }
        out.append('.');
        if (safeDecimals >= 3 && fraction < 100L) {
            out.append('0');
        }
        if (safeDecimals >= 2 && fraction < 10L) {
            out.append('0');
        }
        out.append(fraction);
        return out.toString();
    }
}
