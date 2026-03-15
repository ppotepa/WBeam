package com.wbeam.telemetry;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Maps raw daemon metrics JSON into normalized runtime HUD snapshot values.
 */
public final class RuntimeTelemetryMapper {
    private RuntimeTelemetryMapper() {}

    public static final class Snapshot {
        public long frameInHost;
        public long frameOutHost;
        public long streamUptimeSec;

        public double targetFps;
        public double presentFps;
        public double recvFps;
        public double decodeFps;
        public double frametimeP95;
        public double decodeP95;
        public double renderP95;
        public double e2eP95;

        public int qT;
        public int qD;
        public int qR;
        public int qTMax;
        public int qDMax;
        public int qRMax;

        public int adaptiveLevel;
        public String adaptiveAction;
        public long drops;
        public long bpHigh;
        public long bpRecover;
        public String reason;

        public long latestDroppedFrames;
        public long latestTooLateFrames;
        public double bitrateMbps;
        public boolean tuningActive;
        public String tuningLine;
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

        out.frameInHost = metrics.optLong("frame_in", 0);
        out.frameOutHost = metrics.optLong("frame_out", 0);
        out.streamUptimeSec = metrics.optLong("stream_uptime_sec", 0);

        out.targetFps = kpi != null ? kpi.optDouble("target_fps", selectedFps) : selectedFps;
        if (!Double.isFinite(out.targetFps) || out.targetFps <= 0.0) {
            out.targetFps = selectedFps;
        }
        out.presentFps = kpi != null ? kpi.optDouble("present_fps", 0.0) : 0.0;
        out.recvFps = kpi != null ? kpi.optDouble("recv_fps", 0.0) : 0.0;
        out.decodeFps = kpi != null ? kpi.optDouble("decode_fps", 0.0) : 0.0;
        if (!Double.isFinite(out.presentFps) || out.presentFps < 0.0) {
            out.presentFps = 0.0;
        }
        if (out.presentFps < 1.0) {
            if (Double.isFinite(out.decodeFps) && out.decodeFps >= 1.0) {
                out.presentFps = out.decodeFps;
            } else if (Double.isFinite(out.recvFps) && out.recvFps >= 1.0) {
                out.presentFps = out.recvFps;
            }
        }

        out.frametimeP95 = kpi != null ? kpi.optDouble("frametime_ms_p95", 0.0) : 0.0;
        out.decodeP95 = kpi != null ? kpi.optDouble("decode_time_ms_p95", 0.0) : 0.0;
        out.renderP95 = kpi != null ? kpi.optDouble("render_time_ms_p95", 0.0) : 0.0;
        out.e2eP95 = kpi != null ? kpi.optDouble("e2e_latency_ms_p95", 0.0) : 0.0;

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

        out.adaptiveLevel = metrics.optInt("adaptive_level", 0);
        out.adaptiveAction = metrics.optString("adaptive_action", "hold");
        out.drops = metrics.optLong("drops", 0);
        out.bpHigh = metrics.optLong("backpressure_high_events", 0);
        out.bpRecover = metrics.optLong("backpressure_recover_events", 0);
        out.reason = metrics.optString("adaptive_reason", "");
        if (out.reason.length() > 44) {
            out.reason = out.reason.substring(0, 44) + "...";
        }

        out.latestDroppedFrames = latest != null ? latest.optLong("dropped_frames", -1L) : -1L;
        out.latestTooLateFrames = latest != null ? latest.optLong("too_late_frames", 0L) : 0L;

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

        JSONObject tuning = metrics.optJSONObject("tuning");
        out.tuningActive = tuning != null && tuning.optBoolean("active", false);
        out.tuningLine = "";
        if (out.tuningActive && tuning != null) {
            String codec = tuning.optString("codec", "-").toUpperCase(Locale.US);
            String phase = tuning.optString("phase", "");
            int generation = tuning.optInt("generation", 0);
            int totalGenerations = tuning.optInt("total_generations", 0);
            int child = tuning.optInt("child", 0);
            int childrenPerGeneration = tuning.optInt("children_per_generation", 0);
            String score = fmtScore(tuning.optDouble("score", Double.NaN));
            String bestScore = fmtScore(tuning.optDouble("best_score", Double.NaN));
            String note = tuning.optString("note", "");
            if (note.length() > 28) {
                note = note.substring(0, 28) + "...";
            }
            out.tuningLine = String.format(
                    Locale.US,
                    "%s G%d/%d C%d/%d S=%s B=%s %s %s",
                    codec,
                    generation,
                    totalGenerations,
                    child,
                    childrenPerGeneration,
                    score,
                    bestScore,
                    phase,
                    note
            ).trim();
            if (out.tuningLine.length() > 120) {
                out.tuningLine = out.tuningLine.substring(0, 120) + "...";
            }
        }

        return out;
    }

    private static String fmtScore(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        return String.format(Locale.US, "%.2f", value);
    }
}
