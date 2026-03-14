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

    public static final class QueueSnapshot {
        public final int transport;
        public final int decode;
        public final int render;
        public final int transportMax;
        public final int decodeMax;
        public final int renderMax;

        QueueSnapshot(int transport, int decode, int render, int transportMax, int decodeMax, int renderMax) {
            this.transport = transport;
            this.decode = decode;
            this.render = render;
            this.transportMax = transportMax;
            this.decodeMax = decodeMax;
            this.renderMax = renderMax;
        }

        private boolean isUnderPressure() {
            return transport >= transportMax || decode >= decodeMax || render >= renderMax;
        }
    }

    public static final class PressureComputationInput {
        public final double targetFps;
        public final double presentFps;
        public final double decodeP95;
        public final double renderP95;
        public final QueueSnapshot queues;
        public final String adaptiveAction;
        public final long streamUptimeSec;

        PressureComputationInput(
                double targetFps,
                double presentFps,
                double decodeP95,
                double renderP95,
                QueueSnapshot queues,
                String adaptiveAction,
                long streamUptimeSec
        ) {
            this.targetFps = targetFps;
            this.presentFps = presentFps;
            this.decodeP95 = decodeP95;
            this.renderP95 = renderP95;
            this.queues = queues;
            this.adaptiveAction = adaptiveAction == null ? "" : adaptiveAction;
            this.streamUptimeSec = streamUptimeSec;
        }

        private double fpsFloor() {
            return targetFps * 0.90;
        }

        private boolean isWarmingUp() {
            return presentFps < 1.0 && streamUptimeSec < 5;
        }

        private boolean isFpsUnderPressure() {
            return presentFps > 0.0 && presentFps < fpsFloor();
        }

        private boolean hasTimingPressure() {
            return decodeP95 > 12.0 || renderP95 > 7.0;
        }

        private boolean hasQueuePressure() {
            return queues.isUnderPressure();
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

    public static final class CompactHudLineInput {
        public final double targetFps;
        public final double presentFps;
        public final double e2eP95;
        public final double decodeP95;
        public final double renderP95;
        public final QueueSnapshot queues;

        CompactHudLineInput(
                double targetFps,
                double presentFps,
                double e2eP95,
                double decodeP95,
                double renderP95,
                QueueSnapshot queues
        ) {
            this.targetFps = targetFps;
            this.presentFps = presentFps;
            this.e2eP95 = e2eP95;
            this.decodeP95 = decodeP95;
            this.renderP95 = renderP95;
            this.queues = queues;
        }
    }

    public static final class PressureLogInput {
        public final PressureState pressureState;
        public final double decodeP95;
        public final double renderP95;
        public final QueueSnapshot queues;
        public final double presentFps;
        public final long streamUptimeSec;

        PressureLogInput(
                PressureState pressureState,
                double decodeP95,
                double renderP95,
                QueueSnapshot queues,
                double presentFps,
                long streamUptimeSec
        ) {
            this.pressureState = pressureState;
            this.decodeP95 = decodeP95;
            this.renderP95 = renderP95;
            this.queues = queues;
            this.presentFps = presentFps;
            this.streamUptimeSec = streamUptimeSec;
        }
    }

    public static final class RuntimeDebugSnapshotInput {
        public final String daemonStateUi;
        public final long daemonRunId;
        public final long daemonUptimeSec;
        public final long streamUptimeSec;
        public final long frameInHost;
        public final long frameOutHost;
        public final double targetFps;
        public final double presentFps;
        public final double frametimeP95;
        public final double decodeP95;
        public final double renderP95;
        public final double e2eP95;
        public final QueueSnapshot queues;
        public final int adaptiveLevel;
        public final String adaptiveAction;
        public final long drops;
        public final long bpHigh;
        public final long bpRecover;
        public final PressureState pressureState;
        public final String reason;
        public final String daemonLastError;

        RuntimeDebugSnapshotInput(
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
                QueueSnapshot queues,
                int adaptiveLevel,
                String adaptiveAction,
                long drops,
                long bpHigh,
                long bpRecover,
                PressureState pressureState,
                String reason,
                String daemonLastError
        ) {
            this.daemonStateUi = daemonStateUi;
            this.daemonRunId = daemonRunId;
            this.daemonUptimeSec = daemonUptimeSec;
            this.streamUptimeSec = streamUptimeSec;
            this.frameInHost = frameInHost;
            this.frameOutHost = frameOutHost;
            this.targetFps = targetFps;
            this.presentFps = presentFps;
            this.frametimeP95 = frametimeP95;
            this.decodeP95 = decodeP95;
            this.renderP95 = renderP95;
            this.e2eP95 = e2eP95;
            this.queues = queues;
            this.adaptiveLevel = adaptiveLevel;
            this.adaptiveAction = adaptiveAction;
            this.drops = drops;
            this.bpHigh = bpHigh;
            this.bpRecover = bpRecover;
            this.pressureState = pressureState;
            this.reason = reason;
            this.daemonLastError = daemonLastError;
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

    @SuppressWarnings("java:S107")
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
        return evaluatePressure(new PressureComputationInput(
                targetFps,
                presentFps,
                decodeP95,
                renderP95,
                queueSnapshot(qT, qD, qR, qTMax, qDMax, qRMax),
                adaptiveAction,
                streamUptimeSec
        ));
    }

    public static PressureState evaluatePressure(PressureComputationInput input) {
        boolean warmingUp = input.isWarmingUp();
        String pressureReason = buildPressureReason(input, warmingUp);
        boolean highPressure = evaluateHighPressure(input, warmingUp);
        String runtimeTone = determineRuntimeTone(input, warmingUp, highPressure);
        return new PressureState(warmingUp, highPressure, pressureReason, runtimeTone);
    }

    private static String buildPressureReason(PressureComputationInput input, boolean warmingUp) {
        if (warmingUp) {
            return "warmup";
        }

        StringBuilder reason = new StringBuilder();
        if (input.isFpsUnderPressure()) {
            reason.append("fps<").append(String.format(Locale.US, "%.1f", input.fpsFloor()));
        }
        if (input.decodeP95 > 12.0) {
            appendPressureSegment(
                    reason,
                    "dec>12(" + String.format(Locale.US, "%.1f", input.decodeP95) + ")"
            );
        }
        if (input.renderP95 > 7.0) {
            appendPressureSegment(
                    reason,
                    "ren>7(" + String.format(Locale.US, "%.1f", input.renderP95) + ")"
            );
        }
        if (input.queues.transport >= input.queues.transportMax) {
            appendPressureSegment(
                    reason,
                    "qT=" + input.queues.transport + "/" + input.queues.transportMax
            );
        }
        if (input.queues.decode >= input.queues.decodeMax) {
            appendPressureSegment(
                    reason,
                    "qD=" + input.queues.decode + "/" + input.queues.decodeMax
            );
        }
        if (input.queues.render >= input.queues.renderMax) {
            appendPressureSegment(
                    reason,
                    "qR=" + input.queues.render + "/" + input.queues.renderMax
            );
        }

        return reason.length() > 0 ? reason.toString() : "ok";
    }

    private static boolean evaluateHighPressure(PressureComputationInput input, boolean warmingUp) {
        if (warmingUp) {
            return false;
        }
        return input.isFpsUnderPressure() && (input.hasTimingPressure() || input.hasQueuePressure());
    }

    private static String determineRuntimeTone(
            PressureComputationInput input,
            boolean warmingUp,
            boolean highPressure
    ) {
        if (highPressure) {
            return "risk";
        }
        if (warmingUp) {
            return "warn";
        }

        boolean mediumPressure = input.adaptiveAction.startsWith("degrade")
                || input.isFpsUnderPressure()
                || input.hasTimingPressure()
                || input.hasQueuePressure();
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
        return formatCompactHudLine(new CompactHudLineInput(
                targetFps,
                presentFps,
                e2eP95,
                decodeP95,
                renderP95,
                queueSnapshot(qT, qD, qR, 0, 0, 0)
        ));
    }

    public static String formatCompactHudLine(CompactHudLineInput input) {
        return String.format(
                Locale.US,
                "hud fps %.0f/%.1f | e2e %.1fms | dec %.1fms | ren %.1fms | q %d/%d/%d",
                input.targetFps,
                input.presentFps,
                input.e2eP95,
                input.decodeP95,
                input.renderP95,
                input.queues.transport,
                input.queues.decode,
                input.queues.render
        );
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
        return formatHighPressureLog(new PressureLogInput(
                pressureState,
                decodeP95,
                renderP95,
                queueSnapshot(qT, qD, qR, qTMax, qDMax, qRMax),
                presentFps,
                streamUptimeSec
        ));
    }

    public static String formatHighPressureLog(PressureLogInput input) {
        return "HUD RED warmingUp=" + input.pressureState.warmingUp + " hp=" + input.pressureState.reason
                + " dec_p95=" + String.format(Locale.US, "%.2f", input.decodeP95)
                + " ren_p95=" + String.format(Locale.US, "%.2f", input.renderP95)
                + " qT=" + input.queues.transport + "/" + input.queues.transportMax
                + " qD=" + input.queues.decode + "/" + input.queues.decodeMax
                + " qR=" + input.queues.render + "/" + input.queues.renderMax
                + " fps_present=" + String.format(Locale.US, "%.1f", input.presentFps)
                + " stream_up=" + input.streamUptimeSec + "s";
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
        return buildRuntimeDebugSnapshot(new RuntimeDebugSnapshotInput(
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
                queueSnapshot(qT, qD, qR, qTMax, qDMax, qRMax),
                adaptiveLevel,
                adaptiveAction,
                drops,
                bpHigh,
                bpRecover,
                pressureState,
                reason,
                daemonLastError
        ));
    }

    public static String buildRuntimeDebugSnapshot(RuntimeDebugSnapshotInput input) {
        return String.format(
                Locale.US,
                "state=%s run_id=%d up=%ds stream_up=%ds host_in_out=%d/%d fps_target=%.0f fps_present=%.1f frame_p95=%.2f dec_p95=%.2f ren_p95=%.2f e2e_p95=%.2f q=%d/%d/%d qmax=%d/%d/%d adapt=L%d:%s drops=%d bp=%d/%d warmup=%b hp=%s reason=%s host_err=%s",
                input.daemonStateUi,
                input.daemonRunId,
                input.daemonUptimeSec,
                input.streamUptimeSec,
                input.frameInHost,
                input.frameOutHost,
                input.targetFps,
                input.presentFps,
                input.frametimeP95,
                input.decodeP95,
                input.renderP95,
                input.e2eP95,
                input.queues.transport,
                input.queues.decode,
                input.queues.render,
                input.queues.transportMax,
                input.queues.decodeMax,
                input.queues.renderMax,
                input.adaptiveLevel,
                input.adaptiveAction,
                input.drops,
                input.bpHigh,
                input.bpRecover,
                input.pressureState.warmingUp,
                input.pressureState.reason,
                input.reason == null || input.reason.isEmpty() ? "-" : input.reason,
                compactHostError(input.daemonLastError, 44)
        );
    }

    private static void appendPressureSegment(StringBuilder sb, String segment) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append(segment);
    }

    private static QueueSnapshot queueSnapshot(
            int qT,
            int qD,
            int qR,
            int qTMax,
            int qDMax,
            int qRMax
    ) {
        return new QueueSnapshot(qT, qD, qR, qTMax, qDMax, qRMax);
    }
}
