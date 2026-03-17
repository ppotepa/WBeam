package com.wbeam.telemetry;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Maps raw daemon metrics JSON into normalized runtime HUD snapshot values.
 */
public final class RuntimeTelemetryMapper {
    private static final double BITS_PER_BYTE = 8.0;

    private RuntimeTelemetryMapper() {}

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
        private boolean tuningActive;
        private String tuningLine;

        public long getFrameInHost() {
            return frameInHost;
        }

        public void setFrameInHost(long frameInHost) {
            this.frameInHost = frameInHost;
        }

        public long getFrameOutHost() {
            return frameOutHost;
        }

        public void setFrameOutHost(long frameOutHost) {
            this.frameOutHost = frameOutHost;
        }

        public long getStreamUptimeSec() {
            return streamUptimeSec;
        }

        public void setStreamUptimeSec(long streamUptimeSec) {
            this.streamUptimeSec = streamUptimeSec;
        }

        public double getTargetFps() {
            return targetFps;
        }

        public void setTargetFps(double targetFps) {
            this.targetFps = targetFps;
        }

        public double getPresentFps() {
            return presentFps;
        }

        public void setPresentFps(double presentFps) {
            this.presentFps = presentFps;
        }

        public double getRecvFps() {
            return recvFps;
        }

        public void setRecvFps(double recvFps) {
            this.recvFps = recvFps;
        }

        public double getDecodeFps() {
            return decodeFps;
        }

        public void setDecodeFps(double decodeFps) {
            this.decodeFps = decodeFps;
        }

        public double getFrametimeP95() {
            return frametimeP95;
        }

        public void setFrametimeP95(double frametimeP95) {
            this.frametimeP95 = frametimeP95;
        }

        public double getDecodeP95() {
            return decodeP95;
        }

        public void setDecodeP95(double decodeP95) {
            this.decodeP95 = decodeP95;
        }

        public double getRenderP95() {
            return renderP95;
        }

        public void setRenderP95(double renderP95) {
            this.renderP95 = renderP95;
        }

        public double getE2eP95() {
            return e2eP95;
        }

        public void setE2eP95(double e2eP95) {
            this.e2eP95 = e2eP95;
        }

        public int getQT() {
            return qT;
        }

        public void setQT(int qT) {
            this.qT = qT;
        }

        public int getQD() {
            return qD;
        }

        public void setQD(int qD) {
            this.qD = qD;
        }

        public int getQR() {
            return qR;
        }

        public void setQR(int qR) {
            this.qR = qR;
        }

        public int getQTMax() {
            return qTMax;
        }

        public void setQTMax(int qTMax) {
            this.qTMax = qTMax;
        }

        public int getQDMax() {
            return qDMax;
        }

        public void setQDMax(int qDMax) {
            this.qDMax = qDMax;
        }

        public int getQRMax() {
            return qRMax;
        }

        public void setQRMax(int qRMax) {
            this.qRMax = qRMax;
        }

        public int getAdaptiveLevel() {
            return adaptiveLevel;
        }

        public void setAdaptiveLevel(int adaptiveLevel) {
            this.adaptiveLevel = adaptiveLevel;
        }

        public String getAdaptiveAction() {
            return adaptiveAction;
        }

        public void setAdaptiveAction(String adaptiveAction) {
            this.adaptiveAction = adaptiveAction;
        }

        public long getDrops() {
            return drops;
        }

        public void setDrops(long drops) {
            this.drops = drops;
        }

        public long getBpHigh() {
            return bpHigh;
        }

        public void setBpHigh(long bpHigh) {
            this.bpHigh = bpHigh;
        }

        public long getBpRecover() {
            return bpRecover;
        }

        public void setBpRecover(long bpRecover) {
            this.bpRecover = bpRecover;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public long getLatestDroppedFrames() {
            return latestDroppedFrames;
        }

        public void setLatestDroppedFrames(long latestDroppedFrames) {
            this.latestDroppedFrames = latestDroppedFrames;
        }

        public long getLatestTooLateFrames() {
            return latestTooLateFrames;
        }

        public void setLatestTooLateFrames(long latestTooLateFrames) {
            this.latestTooLateFrames = latestTooLateFrames;
        }

        public double getBitrateMbps() {
            return bitrateMbps;
        }

        public void setBitrateMbps(double bitrateMbps) {
            this.bitrateMbps = bitrateMbps;
        }

        public boolean isTuningActive() {
            return tuningActive;
        }

        public void setTuningActive(boolean tuningActive) {
            this.tuningActive = tuningActive;
        }

        public String getTuningLine() {
            return tuningLine;
        }

        public void setTuningLine(String tuningLine) {
            this.tuningLine = tuningLine;
        }
    }

    @SuppressWarnings("java:S3776")
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

        out.setFrameInHost(metrics.optLong("frame_in", 0));
        out.setFrameOutHost(metrics.optLong("frame_out", 0));
        out.setStreamUptimeSec(metrics.optLong("stream_uptime_sec", 0));

        out.setTargetFps(kpi != null ? kpi.optDouble("target_fps", selectedFps) : selectedFps);
        if (!Double.isFinite(out.getTargetFps()) || out.getTargetFps() <= 0.0) {
            out.setTargetFps(selectedFps);
        }
        out.setPresentFps(kpi != null ? kpi.optDouble("present_fps", 0.0) : 0.0);
        out.setRecvFps(kpi != null ? kpi.optDouble("recv_fps", 0.0) : 0.0);
        out.setDecodeFps(kpi != null ? kpi.optDouble("decode_fps", 0.0) : 0.0);
        if (!Double.isFinite(out.getPresentFps()) || out.getPresentFps() < 0.0) {
            out.setPresentFps(0.0);
        }
        if (out.getPresentFps() < 1.0) {
            if (Double.isFinite(out.getDecodeFps()) && out.getDecodeFps() >= 1.0) {
                out.setPresentFps(out.getDecodeFps());
            } else if (Double.isFinite(out.getRecvFps()) && out.getRecvFps() >= 1.0) {
                out.setPresentFps(out.getRecvFps());
            }
        }

        out.setFrametimeP95(kpi != null ? kpi.optDouble("frametime_ms_p95", 0.0) : 0.0);
        out.setDecodeP95(kpi != null ? kpi.optDouble("decode_time_ms_p95", 0.0) : 0.0);
        out.setRenderP95(kpi != null ? kpi.optDouble("render_time_ms_p95", 0.0) : 0.0);
        out.setE2eP95(kpi != null ? kpi.optDouble("e2e_latency_ms_p95", 0.0) : 0.0);

        out.setQT(latest != null ? latest.optInt("transport_queue_depth", 0) : 0);
        out.setQD(latest != null ? latest.optInt("decode_queue_depth", 0) : 0);
        out.setQR(latest != null ? latest.optInt("render_queue_depth", 0) : 0);

        out.setQTMax(limits != null
                ? limits.optInt("transport_queue_max", transportQueueMaxFrames)
                : transportQueueMaxFrames);
        out.setQDMax(limits != null
                ? limits.optInt("decode_queue_max", decodeQueueMaxFrames)
                : decodeQueueMaxFrames);
        out.setQRMax(limits != null
                ? limits.optInt("render_queue_max", renderQueueMaxFrames)
                : renderQueueMaxFrames);

        out.setAdaptiveLevel(metrics.optInt("adaptive_level", 0));
        out.setAdaptiveAction(metrics.optString("adaptive_action", "hold"));
        out.setDrops(metrics.optLong("drops", 0));
        out.setBpHigh(metrics.optLong("backpressure_high_events", 0));
        out.setBpRecover(metrics.optLong("backpressure_recover_events", 0));
        out.setReason(metrics.optString("adaptive_reason", ""));
        if (out.getReason().length() > 44) {
            out.setReason(out.getReason().substring(0, 44) + "...");
        }

        out.setLatestDroppedFrames(latest != null ? latest.optLong("dropped_frames", -1L) : -1L);
        out.setLatestTooLateFrames(latest != null ? latest.optLong("too_late_frames", 0L) : 0L);

        out.setBitrateMbps(0.0);
        if (latest != null) {
            long recvBytesPerSec = latest.optLong("recv_bps", 0L);
            if (recvBytesPerSec > 0L) {
                out.setBitrateMbps((recvBytesPerSec * BITS_PER_BYTE) / 1_000_000.0);
            }
        }
        if (out.getBitrateMbps() <= 0.0) {
            out.setBitrateMbps(metrics.optLong("bitrate_actual_bps", 0L) / 1_000_000.0);
        }

        JSONObject tuning = metrics.optJSONObject("tuning");
        out.setTuningActive(tuning != null && tuning.optBoolean("active", false));
        out.setTuningLine("");
        if (out.isTuningActive() && tuning != null) {
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
            StringBuilder line = new StringBuilder(64);
            line.append(codec)
                    .append(" G").append(generation).append('/').append(totalGenerations)
                    .append(" C").append(child).append('/').append(childrenPerGeneration)
                    .append(" S=").append(score)
                    .append(" B=").append(bestScore);
            if (!phase.isEmpty()) {
                line.append(' ').append(phase);
            }
            if (!note.isEmpty()) {
                line.append(' ').append(note);
            }
            out.setTuningLine(line.toString());
            if (out.getTuningLine().length() > 120) {
                out.setTuningLine(out.getTuningLine().substring(0, 120) + "...");
            }
        }

        return out;
    }

    private static String fmtScore(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        return formatFixed(value, 2);
    }

    private static String formatFixed(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "-";
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
        boolean negative = rounded < 0L;
        long abs = Math.abs(rounded);
        long whole = abs / factor;
        long fraction = abs % factor;
        StringBuilder out = new StringBuilder();
        if (negative) {
            out.append('-');
        }
        out.append(whole);
        if (safeDecimals <= 0) {
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
