package com.wbeam.hud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Builds trainer HUD HTML payloads from raw trainer text or structured JSON payloads.
 */
public final class TrainerHudPayloadBuilder {
    private TrainerHudPayloadBuilder() {}

    public interface ResourceRowsProvider {
        String sampleAndBuildRows(double targetFps, double renderP95Ms);
    }

    public static String buildFromText(
            String hudText,
            String progressLine,
            int progressPercent,
            double fallbackTargetFps,
            double fpsLowAnchor,
            ResourceRowsProvider resourceRowsProvider
    ) {
        String safeHudText = hudText == null ? "" : hudText;
        StringBuilder details = new StringBuilder();
        String[] lines = safeHudText.split("\n");
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("+") && line.endsWith("+")) {
                details.append(HudRenderSupport.hudDetailRow(" ", " "));
                continue;
            }
            if (line.startsWith("|") && line.endsWith("|") && line.length() > 2) {
                String content = line.substring(1, line.length() - 1).trim();
                String[] parts = splitHudColumns(content);
                details.append(HudRenderSupport.hudDetailRow(parts[0], parts[1]));
            } else {
                details.append(HudRenderSupport.hudDetailRow(line, "-"));
            }
        }
        String resourceRows = sampleAndBuildResourceRows(
                resourceRowsProvider,
                fallbackTargetFps > 1.0 ? fallbackTargetFps : 60.0,
                0.0
        );

        return TrainerHudShellRenderer.buildSotHtml(
                "TEXT-SNAPSHOT",
                "PENDING",
                "PENDING",
                "PENDING",
                0,
                0,
                0,
                0,
                0,
                "T0",
                progressPercent,
                progressLine,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                0,
                "PENDING",
                Double.NaN,
                "pending",
                "pending",
                "pending",
                "pending",
                "pending",
                "pending",
                "text snapshot mode | " + details.toString().replaceAll("<[^>]+>", " ").trim(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                resourceRows,
                "wide",
                "arcade",
                fpsLowAnchor
        );
    }

    public static String buildFromJson(
            JSONObject hud,
            String progressLine,
            int progressPercent,
            double fallbackTargetFps,
            double fpsLowAnchor,
            ResourceRowsProvider resourceRowsProvider
    ) {
        if (hud == null) {
            return buildFromText(
                    "",
                    progressLine,
                    progressPercent,
                    fallbackTargetFps,
                    fpsLowAnchor,
                    resourceRowsProvider
            );
        }

        JSONObject sections = hud.optJSONObject("sections");
        JSONObject header = sections != null ? sections.optJSONObject("header") : null;
        JSONObject config = sections != null ? sections.optJSONObject("config") : null;
        JSONObject kpi = sections != null ? sections.optJSONObject("kpi") : null;
        JSONObject states = sections != null ? sections.optJSONObject("states") : null;
        JSONObject trends = sections != null ? sections.optJSONObject("trends") : null;
        JSONObject status = sections != null ? sections.optJSONObject("status") : null;

        String runId = header != null ? header.optString("run_id", hud.optString("run_id", "-")) : hud.optString("run_id", "-");
        String profile = header != null ? header.optString("profile_name", hud.optString("profile_name", "-")) : hud.optString("profile_name", "-");
        String trialId = header != null ? header.optString("trial_id", hud.optString("trial_id", "-")) : hud.optString("trial_id", "-");
        int gIdx = header != null ? header.optInt("generation_index", 0) : hud.optInt("generation_index", 0);
        int gTotal = header != null ? header.optInt("generation_total", 0) : hud.optInt("generation_total", 0);
        int tIdx = header != null ? header.optInt("trial_index", 0) : hud.optInt("trial_index", 0);
        int tTotal = header != null ? header.optInt("trial_total", 0) : hud.optInt("trial_total", 0);

        String encoder = config != null ? config.optString("encoder", "-") : "-";
        String size = config != null ? config.optString("size", "-") : "-";
        String fontProfile = config != null ? config.optString("font_profile", "arcade") : "arcade";
        int fps = config != null ? config.optInt("fps", 0) : 0;
        String layoutMode = config != null ? config.optString("layout_mode", hud.optString("layout_mode", "wide")) : hud.optString("layout_mode", "wide");

        double score = kpi != null ? kpi.optDouble("score", Double.NaN) : Double.NaN;
        double present = kpi != null ? kpi.optDouble("present_fps", Double.NaN) : Double.NaN;
        double recv = kpi != null ? kpi.optDouble("recv_fps", Double.NaN) : Double.NaN;
        double decode = kpi != null ? kpi.optDouble("decode_fps", Double.NaN) : Double.NaN;
        double liveMbps = kpi != null ? kpi.optDouble("live_mbps", Double.NaN) : Double.NaN;
        JSONObject metricsObj = hud.optJSONObject("metrics");
        if (Double.isNaN(liveMbps) || liveMbps <= 0.0) {
            liveMbps = hud.optDouble("bitrate_mbps_mean", Double.NaN);
        }
        if ((Double.isNaN(liveMbps) || liveMbps <= 0.0) && metricsObj != null) {
            liveMbps = metricsObj.optDouble("bitrate_mbps_mean", Double.NaN);
        }
        double latency = kpi != null ? kpi.optDouble("latency_ms_p95", Double.NaN) : Double.NaN;
        double drops = kpi != null ? kpi.optDouble("drops_per_sec", Double.NaN) : Double.NaN;
        double queue = kpi != null ? kpi.optDouble("queue_depth", Double.NaN) : Double.NaN;
        double renderMs = kpi != null ? kpi.optDouble("render_time_ms_p95", Double.NaN) : Double.NaN;
        if (Double.isNaN(renderMs)) {
            renderMs = kpi != null ? kpi.optDouble("render_ms_p95", 0.0) : 0.0;
        }

        String fpsState = lowerState(states, "fps");
        String latState = lowerState(states, "latency");
        String mbpsState = lowerState(states, "mbps");
        String dropState = lowerState(states, "drop");
        String queueState = lowerState(states, "queue");
        String qualityState = lowerState(states, "quality");

        JSONArray trendScoreArr = trends != null ? trends.optJSONArray("score") : null;
        JSONArray trendFpsArr = trends != null ? trends.optJSONArray("present_fps") : null;
        JSONArray trendRecvArr = trends != null ? trends.optJSONArray("recv_fps") : null;
        JSONArray trendDecodeArr = trends != null ? trends.optJSONArray("decode_fps") : null;
        JSONArray trendMbpsArr = trends != null ? trends.optJSONArray("mbps") : null;
        JSONArray trendDropArr = trends != null ? trends.optJSONArray("drop_per_sec") : null;
        if ((trendDropArr == null || trendDropArr.length() == 0) && trends != null) {
            trendDropArr = trends.optJSONArray("drop_pct");
        }
        JSONArray trendLatencyArr = trends != null ? trends.optJSONArray("latency_ms_p95") : null;
        JSONArray trendQueueArr = trends != null ? trends.optJSONArray("queue_depth") : null;
        JSONArray trendLateArr = trends != null ? trends.optJSONArray("late_per_sec") : null;

        double late = kpi != null ? kpi.optDouble("late_per_sec", Double.NaN) : Double.NaN;
        if (Double.isNaN(liveMbps) || liveMbps <= 0.0) {
            liveMbps = HudRenderSupport.latestFiniteFromSeries(trendMbpsArr);
        }
        if (Double.isNaN(present) || present <= 0.0) {
            present = HudRenderSupport.latestFiniteFromSeries(trendFpsArr);
        }
        if (Double.isNaN(recv) || recv <= 0.0) {
            recv = HudRenderSupport.latestFiniteFromSeries(trendRecvArr);
        }
        if (Double.isNaN(decode) || decode <= 0.0) {
            decode = HudRenderSupport.latestFiniteFromSeries(trendDecodeArr);
        }
        if (Double.isNaN(drops) || drops < 0.0) {
            drops = HudRenderSupport.latestFiniteFromSeries(trendDropArr);
        }
        if (Double.isNaN(latency) || latency < 0.0) {
            latency = HudRenderSupport.latestFiniteFromSeries(trendLatencyArr);
        }
        if (Double.isNaN(queue) || queue < 0.0) {
            queue = HudRenderSupport.latestFiniteFromSeries(trendQueueArr);
        }
        if (Double.isNaN(late) || late < 0.0) {
            late = HudRenderSupport.latestFiniteFromSeries(trendLateArr);
        }

        String statusNote = status != null ? status.optString("note", "") : "";
        int sampleCount = status != null ? Math.max(0, status.optInt("sample_count", 0)) : 0;
        String bestTrial = config != null ? config.optString("best_trial", "-") : "-";
        double bestScore = config != null ? config.optDouble("best_score", Double.NaN) : Double.NaN;

        double targetForSample = fps > 1 ? fps : (fallbackTargetFps > 1.0 ? fallbackTargetFps : 60.0);
        String resourceRows = sampleAndBuildResourceRows(
                resourceRowsProvider,
                targetForSample,
                Double.isNaN(renderMs) ? 0.0 : renderMs
        );

        return TrainerHudShellRenderer.buildSotHtml(
                runId,
                profile,
                encoder,
                size,
                Math.max(0, fps),
                gIdx,
                gTotal,
                tIdx,
                tTotal,
                trialId,
                progressPercent,
                progressLine,
                score,
                present,
                recv,
                decode,
                liveMbps,
                latency,
                drops,
                queue,
                late,
                sampleCount,
                bestTrial,
                bestScore,
                fpsState,
                mbpsState,
                latState,
                dropState,
                queueState,
                qualityState,
                statusNote,
                trendScoreArr,
                trendFpsArr,
                trendMbpsArr,
                trendLatencyArr,
                trendDropArr,
                trendQueueArr,
                trendRecvArr,
                trendDecodeArr,
                resourceRows,
                layoutMode,
                fontProfile,
                fpsLowAnchor
        );
    }

    private static String lowerState(JSONObject states, String key) {
        String value = states != null ? states.optString(key, "PENDING") : "PENDING";
        return value.toLowerCase(Locale.US);
    }

    private static String sampleAndBuildResourceRows(
            ResourceRowsProvider provider,
            double targetFps,
            double renderP95Ms
    ) {
        if (provider == null) {
            return "";
        }
        String rows = provider.sampleAndBuildRows(targetFps, renderP95Ms);
        return rows == null ? "" : rows;
    }

    private static String[] splitHudColumns(String content) {
        if (content == null) {
            return new String[]{"", ""};
        }
        int pivot = -1;
        for (int i = 2; i < content.length() - 2; i++) {
            if (content.charAt(i) == ' ' && content.charAt(i - 1) == ' ' && content.charAt(i + 1) == ' ') {
                pivot = i;
                break;
            }
        }
        if (pivot < 0) {
            return new String[]{content.trim(), ""};
        }
        String left = content.substring(0, pivot).trim();
        String right = content.substring(pivot).trim();
        return new String[]{left, right};
    }
}
