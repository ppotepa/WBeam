package com.wbeam.hud;

import com.wbeam.telemetry.RuntimeTelemetryMapper;

import java.util.Locale;

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
        double presentFps = runtime.presentFps;
        boolean hasFlowSignals =
                runtime.streamUptimeSec > 0
                        || runtime.frameOutHost > 0
                        || runtime.recvFps >= 1.0
                        || runtime.decodeFps >= 1.0;
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
                pressureReasonBuilder.append("fps<").append(String.format(Locale.US, "%.1f", fpsFloor));
            }
            if (decodeP95 > 12.0) {
                appendPressureSegment(
                        pressureReasonBuilder,
                        "dec>12(" + String.format(Locale.US, "%.1f", decodeP95) + ")"
                );
            }
            if (renderP95 > 7.0) {
                appendPressureSegment(
                        pressureReasonBuilder,
                        "ren>7(" + String.format(Locale.US, "%.1f", renderP95) + ")"
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
        return String.format(
                Locale.US,
                "hud fps %.0f/%.1f | e2e %.1fms | dec %.1fms | ren %.1fms | q %d/%d/%d",
                targetFps,
                presentFps,
                e2eP95,
                decodeP95,
                renderP95,
                qT,
                qD,
                qR
        );
    }

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
                + " dec_p95=" + String.format(Locale.US, "%.2f", decodeP95)
                + " ren_p95=" + String.format(Locale.US, "%.2f", renderP95)
                + " qT=" + qT + "/" + qTMax
                + " qD=" + qD + "/" + qDMax
                + " qR=" + qR + "/" + qRMax
                + " fps_present=" + String.format(Locale.US, "%.1f", presentFps)
                + " stream_up=" + streamUptimeSec + "s";
    }

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
        return String.format(
                Locale.US,
                "state=%s run_id=%d up=%ds stream_up=%ds host_in_out=%d/%d fps_target=%.0f fps_present=%.1f frame_p95=%.2f dec_p95=%.2f ren_p95=%.2f e2e_p95=%.2f q=%d/%d/%d qmax=%d/%d/%d adapt=L%d:%s drops=%d bp=%d/%d warmup=%b hp=%s reason=%s host_err=%s",
                daemonStateUi,
                daemonRunId,
                daemonUptimeSec,
                streamUptimeSec,
                frameInHost,
                frameOutHost,
                targetFps,
                presentFps,
                frametimeP95,
                decodeP95,
                renderP95,
                e2eP95,
                qT,
                qD,
                qR,
                qTMax,
                qDMax,
                qRMax,
                adaptiveLevel,
                adaptiveAction,
                drops,
                bpHigh,
                bpRecover,
                pressureState.warmingUp,
                pressureState.reason,
                reason.isEmpty() ? "-" : reason,
                compactHostError(daemonLastError, 44)
        );
    }

    private static void appendPressureSegment(StringBuilder sb, String segment) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(segment);
    }
}
