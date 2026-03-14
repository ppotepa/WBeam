package com.wbeam.telemetry;

import org.json.JSONObject;

/**
 * Maps raw daemon metrics JSON into normalized runtime HUD snapshot values.
 */
public final class RuntimeTelemetryMapper {
    private RuntimeTelemetryMapper() {}

    /**
     * Plain data carrier for telemetry snapshot.
     */
    public static final class Snapshot {
        private long frameInHost;
        private long frameOutHost;
        private long streamUptimeSec;

        private double targetFps;
        private double presentFps;
        private double recvFps;
        private double decodeFps;
        private double frametimeP95;
        private double decodeP95;
        private double renderP95;
        private double e2eP95;

        private int qT;
        private int qD;
        private int qR;
        private int qTMax;
        private int qDMax;
        private int qRMax;

        private int adaptiveLevel;
        private String adaptiveAction;
        private long drops;
        private long bpHigh;
        private long bpRecover;
        private String reason;

        private long latestDroppedFrames;
        private long latestTooLateFrames;
        private double bitrateMbps;

        public long getFrameInHost() {
            return frameInHost;
        }

        public long getFrameOutHost() {
            return frameOutHost;
        }

        public long getStreamUptimeSec() {
            return streamUptimeSec;
        }

        public double getTargetFps() {
            return targetFps;
        }

        public double getPresentFps() {
            return presentFps;
        }

        public double getRecvFps() {
            return recvFps;
        }

        public double getDecodeFps() {
            return decodeFps;
        }

        public double getFrametimeP95() {
            return frametimeP95;
        }

        public double getDecodeP95() {
            return decodeP95;
        }

        public double getRenderP95() {
            return renderP95;
        }

        public double getE2eP95() {
            return e2eP95;
        }

        public int getQT() {
            return qT;
        }

        public int getQD() {
            return qD;
        }

        public int getQR() {
            return qR;
        }

        public int getQTMax() {
            return qTMax;
        }

        public int getQDMax() {
            return qDMax;
        }

        public int getQRMax() {
            return qRMax;
        }

        public int getAdaptiveLevel() {
            return adaptiveLevel;
        }

        public String getAdaptiveAction() {
            return adaptiveAction;
        }

        public long getDrops() {
            return drops;
        }

        public long getBpHigh() {
            return bpHigh;
        }

        public long getBpRecover() {
            return bpRecover;
        }

        public String getReason() {
            return reason;
        }

        public long getLatestDroppedFrames() {
            return latestDroppedFrames;
        }

        public long getLatestTooLateFrames() {
            return latestTooLateFrames;
        }

        public double getBitrateMbps() {
            return bitrateMbps;
        }
    }

    public static Snapshot map(
            JSONObject metrics,
            int selectedFps,
            int transportQueueMaxFrames,
            int decodeQueueMaxFrames,
            int renderQueueMaxFrames
    ) {
        Snapshot out = new Snapshot();
        JSONObject kpi = metrics.optJSONObject("kpi");
        JSONObject latest = metrics.optJSONObject("latest_client_metrics");
        JSONObject limits = metrics.optJSONObject("queue_limits");

        populateFrameStats(out, metrics);
        populateKpiMetrics(out, kpi, selectedFps);
        populateQueueMeasurements(out, latest, limits, transportQueueMaxFrames, decodeQueueMaxFrames, renderQueueMaxFrames);
        populateAdaptiveState(out, metrics);
        populateLatestSampleTotals(out, latest);
        populateBitrate(out, latest, metrics);

        return out;
    }

    private static void populateFrameStats(Snapshot out, JSONObject metrics) {
        out.frameInHost = metrics.optLong("frame_in", 0);
        out.frameOutHost = metrics.optLong("frame_out", 0);
        out.streamUptimeSec = metrics.optLong("stream_uptime_sec", 0);
    }

    private static void populateKpiMetrics(Snapshot out, JSONObject kpi, int selectedFps) {
        out.targetFps = readPositiveMetric(kpi, "target_fps", selectedFps);
        out.recvFps = readNonNegativeMetric(kpi, "recv_fps");
        out.decodeFps = readNonNegativeMetric(kpi, "decode_fps");
        out.presentFps = resolvePresentFps(kpi, out.decodeFps, out.recvFps);
        out.frametimeP95 = readNonNegativeMetric(kpi, "frametime_ms_p95");
        out.decodeP95 = readNonNegativeMetric(kpi, "decode_time_ms_p95");
        out.renderP95 = readNonNegativeMetric(kpi, "render_time_ms_p95");
        out.e2eP95 = readNonNegativeMetric(kpi, "e2e_latency_ms_p95");
    }

    private static double resolvePresentFps(JSONObject kpi, double decodeFps, double recvFps) {
        double presentFps = readNonNegativeMetric(kpi, "present_fps");
        if (presentFps >= 1.0) {
            return presentFps;
        }
        if (decodeFps >= 1.0) {
            return decodeFps;
        }
        if (recvFps >= 1.0) {
            return recvFps;
        }
        return presentFps;
    }

    private static double readPositiveMetric(JSONObject json, String key, double fallback) {
        double value = json != null ? json.optDouble(key, fallback) : fallback;
        return (Double.isFinite(value) && value > 0.0) ? value : fallback;
    }

    private static double readNonNegativeMetric(JSONObject json, String key) {
        double value = json != null ? json.optDouble(key, 0.0) : 0.0;
        return (Double.isFinite(value) && value >= 0.0) ? value : 0.0;
    }

    private static void populateQueueMeasurements(
            Snapshot out,
            JSONObject latest,
            JSONObject limits,
            int transportQueueMaxFrames,
            int decodeQueueMaxFrames,
            int renderQueueMaxFrames
    ) {
        out.qT = latest != null ? latest.optInt("transport_queue_depth", 0) : 0;
        out.qD = latest != null ? latest.optInt("decode_queue_depth", 0) : 0;
        out.qR = latest != null ? latest.optInt("render_queue_depth", 0) : 0;

        out.qTMax = limits != null
                ? limits.optInt("transport_queue_max", transportQueueMaxFrames)
                : transportQueueMaxFrames;
        out.qDMax = limits != null
                ? limits.optInt("decode_queue_max", decodeQueueMaxFrames)
                : decodeQueueMaxFrames;
        out.qRMax = limits != null
                ? limits.optInt("render_queue_max", renderQueueMaxFrames)
                : renderQueueMaxFrames;
    }

    private static void populateAdaptiveState(Snapshot out, JSONObject metrics) {
        out.adaptiveLevel = metrics.optInt("adaptive_level", 0);
        out.adaptiveAction = metrics.optString("adaptive_action", "hold");
        out.drops = metrics.optLong("drops", 0);
        out.bpHigh = metrics.optLong("backpressure_high_events", 0);
        out.bpRecover = metrics.optLong("backpressure_recover_events", 0);
        out.reason = metrics.optString("adaptive_reason", "");
        if (out.reason.length() > 44) {
            out.reason = out.reason.substring(0, 44) + "...";
        }
    }

    private static void populateLatestSampleTotals(Snapshot out, JSONObject latest) {
        out.latestDroppedFrames = latest != null ? latest.optLong("dropped_frames", -1L) : -1L;
        out.latestTooLateFrames = latest != null ? latest.optLong("too_late_frames", 0L) : 0L;
    }

    private static void populateBitrate(Snapshot out, JSONObject latest, JSONObject metrics) {
        out.bitrateMbps = 0.0;
        if (latest != null) {
            long recvBps = latest.optLong("recv_bps", 0L);
            if (recvBps > 0L) {
                out.bitrateMbps = recvBps / 1_000_000.0;
            }
        }
        if (out.bitrateMbps <= 0.0) {
            out.bitrateMbps = metrics.optLong("bitrate_actual_bps", 0L) / 1_000_000.0;
        }
    }
}
