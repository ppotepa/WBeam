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

        String pressureReason = buildPressureReason(
                warmingUp, targetFps, presentFps,
                decodeP95, renderP95,
                qT, qD, qR, qTMax, qDMax, qRMax
        );

        boolean highPressure = evaluateHighPressure(
                warmingUp, targetFps, presentFps,
                decodeP95, renderP95,
                qT, qD, qR, qTMax, qDMax, qRMax
        );

        String runtimeTone = determineRuntimeTone(
                warmingUp, highPressure, adaptiveAction,
                targetFps, presentFps, decodeP95, renderP95,
                qT, qD, qR, qTMax, qDMax, qRMax
        );

        return new PressureState(warmingUp, highPressure, pressureReason, runtimeTone);
    }

    private static String buildPressureReason(
            boolean warmingUp,
            double targetFps,
            double presentFps,
            double decodeP95,
            double renderP95,
            int qT, int qD, int qR,
            int qTMax, int qDMax, int qRMax
    ) {
        if (warmingUp) {
            return "warmup";
        }

        StringBuilder reason = new StringBuilder();
        double fpsFloor = targetFps * 0.90;

        if (presentFps > 0.0 && presentFps < fpsFloor) {
            reason.append("fps<").append(String.format(Locale.US, "%.1f", fpsFloor));
        }
        if (decodeP95 > 12.0) {
            appendPressureSegment(
                    reason,
                    "dec>12(" + String.format(Locale.US, "%.1f", decodeP95) + ")"
            );
        }
        if (renderP95 > 7.0) {
            appendPressureSegment(
                    reason,
                    "ren>7(" + String.format(Locale.US, "%.1f", renderP95) + ")"
            );
        }
        if (qT >= qTMax) {
            appendPressureSegment(reason, "qT=" + qT + "/" + qTMax);
        }
        if (qD >= qDMax) {
            appendPressureSegment(reason, "qD=" + qD + "/" + qDMax);
        }
        if (qR >= qRMax) {
            appendPressureSegment(reason, "qR=" + qR + "/" + qRMax);
        }

        return reason.length() > 0 ? reason.toString() : "ok";
    }

    private static boolean evaluateHighPressure(
            boolean warmingUp,
            double targetFps,
            double presentFps,
            double decodeP95,
            double renderP95,
            int qT, int qD, int qR,
            int qTMax, int qDMax, int qRMax
    ) {
        if (warmingUp) {
            return false;
        }

        double fpsFloor = targetFps * 0.90;
        boolean fpsUnderPressure = presentFps > 0.0 && presentFps < fpsFloor;
        boolean timingPressure = decodeP95 > 12.0 || renderP95 > 7.0;
        boolean queuePressure = qT >= qTMax || qD >= qDMax || qR >= qRMax;

        return fpsUnderPressure && (timingPressure || queuePressure);
    }

    private static String determineRuntimeTone(
            boolean warmingUp,
            boolean highPressure,
            String adaptiveAction,
            double targetFps,
            double presentFps,
            double decodeP95,
            double renderP95,
            int qT, int qD, int qR,
            int qTMax, int qDMax, int qRMax
    ) {
        if (highPressure) {
            return "risk";
        }

        if (warmingUp) {
            return "warn";
        }

        double fpsFloor = targetFps * 0.90;
        boolean fpsUnderPressure = presentFps > 0.0 && presentFps < fpsFloor;
        boolean timingPressure = decodeP95 > 12.0 || renderP95 > 7.0;
        boolean queuePressure = qT >= qTMax || qD >= qDMax || qR >= qRMax;
        boolean mediumPressure = adaptiveAction.startsWith("degrade")
                || fpsUnderPressure
                || timingPressure
                || queuePressure;

        return mediumPressure ? "warn" : "ok";
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
