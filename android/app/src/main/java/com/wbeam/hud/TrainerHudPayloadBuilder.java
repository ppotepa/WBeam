package com.wbeam.hud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Builds trainer HUD HTML payloads from raw trainer text or structured JSON payloads.
 */
public final class TrainerHudPayloadBuilder {
    private static final String FONT_PROFILE_ARCADE = "arcade";
    private static final String LAYOUT_MODE_KEY = "layout_mode";
    private static final String PENDING = "PENDING";
    private static final String PENDING_LOWERCASE = "pending";

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
        @SuppressWarnings("java:java:S135")
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
                PENDING,
                PENDING,
                PENDING,
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
                PENDING,
                Double.NaN,
                PENDING_LOWERCASE,
                PENDING_LOWERCASE,
                PENDING_LOWERCASE,
                PENDING_LOWERCASE,
                PENDING_LOWERCASE,
                PENDING_LOWERCASE,
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
                FONT_PROFILE_ARCADE,
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
        JSONObject header = getSection(sections, "header");
        JSONObject config = getSection(sections, "config");
        JSONObject kpi = getSection(sections, "kpi");
        JSONObject states = getSection(sections, "states");
        JSONObject trends = getSection(sections, "trends");
        JSONObject status = getSection(sections, "status");

        HeaderData headerData = extractHeaderData(hud, header);
        ConfigData configData = extractConfigData(hud, config);
        KpiData kpiData = extractKpiData(hud, kpi);
        StateData stateData = extractStateData(states);
        TrendData trendData = extractTrendData(trends);
        StatusData statusData = extractStatusData(status);

        // Fallback KPI values from trends if missing
        kpiData = applyFallbacksFromTrends(kpiData, trendData);

        double targetForSample = configData.fps > 1 
                ? configData.fps 
                : (fallbackTargetFps > 1.0 ? fallbackTargetFps : 60.0);
        
        String resourceRows = sampleAndBuildResourceRows(
                resourceRowsProvider,
                targetForSample,
                Double.isNaN(kpiData.renderMs) ? 0.0 : kpiData.renderMs
        );

        return TrainerHudShellRenderer.buildSotHtml(
                headerData.runId,
                headerData.profile,
                configData.encoder,
                configData.size,
                Math.max(0, configData.fps),
                headerData.gIdx,
                headerData.gTotal,
                headerData.tIdx,
                headerData.tTotal,
                headerData.trialId,
                progressPercent,
                progressLine,
                kpiData.score,
                kpiData.present,
                kpiData.recv,
                kpiData.decode,
                kpiData.liveMbps,
                kpiData.latency,
                kpiData.drops,
                kpiData.queue,
                kpiData.late,
                statusData.sampleCount,
                configData.bestTrial,
                configData.bestScore,
                stateData.fpsState,
                stateData.mbpsState,
                stateData.latState,
                stateData.dropState,
                stateData.queueState,
                stateData.qualityState,
                statusData.statusNote,
                trendData.trendScoreArr,
                trendData.trendFpsArr,
                trendData.trendMbpsArr,
                trendData.trendLatencyArr,
                trendData.trendDropArr,
                trendData.trendQueueArr,
                trendData.trendRecvArr,
                trendData.trendDecodeArr,
                resourceRows,
                configData.layoutMode,
                configData.fontProfile,
                fpsLowAnchor
        );
    }

    private static JSONObject getSection(JSONObject sections, String name) {
        return sections != null ? sections.optJSONObject(name) : null;
    }

    private static HeaderData extractHeaderData(JSONObject hud, JSONObject header) {
        HeaderData data = new HeaderData();
        data.runId = header != null 
                ? header.optString("run_id", hud.optString("run_id", "-")) 
                : hud.optString("run_id", "-");
        data.profile = header != null 
                ? header.optString("profile_name", hud.optString("profile_name", "-")) 
                : hud.optString("profile_name", "-");
        data.trialId = header != null 
                ? header.optString("trial_id", hud.optString("trial_id", "-")) 
                : hud.optString("trial_id", "-");
        data.gIdx = header != null ? header.optInt("generation_index", 0) : hud.optInt("generation_index", 0);
        data.gTotal = header != null ? header.optInt("generation_total", 0) : hud.optInt("generation_total", 0);
        data.tIdx = header != null ? header.optInt("trial_index", 0) : hud.optInt("trial_index", 0);
        data.tTotal = header != null ? header.optInt("trial_total", 0) : hud.optInt("trial_total", 0);
        return data;
    }

    private static ConfigData extractConfigData(JSONObject hud, JSONObject config) {
        ConfigData data = new ConfigData();
        data.encoder = config != null ? config.optString("encoder", "-") : "-";
        data.size = config != null ? config.optString("size", "-") : "-";
        data.fontProfile = config != null ? config.optString("font_profile", FONT_PROFILE_ARCADE) : FONT_PROFILE_ARCADE;
        data.fps = config != null ? config.optInt("fps", 0) : 0;
        data.layoutMode = config != null
                ? config.optString(LAYOUT_MODE_KEY, hud.optString(LAYOUT_MODE_KEY, "wide"))
                : hud.optString(LAYOUT_MODE_KEY, "wide");
        data.bestTrial = config != null ? config.optString("best_trial", "-") : "-";
        data.bestScore = config != null ? config.optDouble("best_score", Double.NaN) : Double.NaN;
        return data;
    }

    private static KpiData extractKpiData(JSONObject hud, JSONObject kpi) {
        KpiData data = new KpiData();
        data.score = kpi != null ? kpi.optDouble("score", Double.NaN) : Double.NaN;
        data.present = kpi != null ? kpi.optDouble("present_fps", Double.NaN) : Double.NaN;
        data.recv = kpi != null ? kpi.optDouble("recv_fps", Double.NaN) : Double.NaN;
        data.decode = kpi != null ? kpi.optDouble("decode_fps", Double.NaN) : Double.NaN;
        data.liveMbps = extractLiveMbps(hud, kpi);
        data.latency = kpi != null ? kpi.optDouble("latency_ms_p95", Double.NaN) : Double.NaN;
        data.drops = kpi != null ? kpi.optDouble("drops_per_sec", Double.NaN) : Double.NaN;
        data.queue = kpi != null ? kpi.optDouble("queue_depth", Double.NaN) : Double.NaN;
        data.renderMs = extractRenderMs(kpi);
        data.late = kpi != null ? kpi.optDouble("late_per_sec", Double.NaN) : Double.NaN;
        return data;
    }

    private static double extractLiveMbps(JSONObject hud, JSONObject kpi) {
        double liveMbps = kpi != null ? kpi.optDouble("live_mbps", Double.NaN) : Double.NaN;
        JSONObject metricsObj = hud.optJSONObject("metrics");
        
        if (Double.isNaN(liveMbps) || liveMbps <= 0.0) {
            liveMbps = hud.optDouble("bitrate_mbps_mean", Double.NaN);
        }
        if ((Double.isNaN(liveMbps) || liveMbps <= 0.0) && metricsObj != null) {
            liveMbps = metricsObj.optDouble("bitrate_mbps_mean", Double.NaN);
        }
        return liveMbps;
    }

    private static double extractRenderMs(JSONObject kpi) {
        double renderMs = kpi != null ? kpi.optDouble("render_time_ms_p95", Double.NaN) : Double.NaN;
        if (Double.isNaN(renderMs)) {
            renderMs = kpi != null ? kpi.optDouble("render_ms_p95", 0.0) : 0.0;
        }
        return renderMs;
    }

    private static StateData extractStateData(JSONObject states) {
        StateData data = new StateData();
        data.fpsState = lowerState(states, "fps");
        data.latState = lowerState(states, "latency");
        data.mbpsState = lowerState(states, "mbps");
        data.dropState = lowerState(states, "drop");
        data.queueState = lowerState(states, "queue");
        data.qualityState = lowerState(states, "quality");
        return data;
    }

    private static TrendData extractTrendData(JSONObject trends) {
        TrendData data = new TrendData();
        data.trendScoreArr = trends != null ? trends.optJSONArray("score") : null;
        data.trendFpsArr = trends != null ? trends.optJSONArray("present_fps") : null;
        data.trendRecvArr = trends != null ? trends.optJSONArray("recv_fps") : null;
        data.trendDecodeArr = trends != null ? trends.optJSONArray("decode_fps") : null;
        data.trendMbpsArr = trends != null ? trends.optJSONArray("mbps") : null;
        data.trendDropArr = trends != null ? trends.optJSONArray("drop_per_sec") : null;
        
        if ((data.trendDropArr == null || data.trendDropArr.length() == 0) && trends != null) {
            data.trendDropArr = trends.optJSONArray("drop_pct");
        }
        
        data.trendLatencyArr = trends != null ? trends.optJSONArray("latency_ms_p95") : null;
        data.trendQueueArr = trends != null ? trends.optJSONArray("queue_depth") : null;
        data.trendLateArr = trends != null ? trends.optJSONArray("late_per_sec") : null;
        return data;
    }

    private static StatusData extractStatusData(JSONObject status) {
        StatusData data = new StatusData();
        data.statusNote = status != null ? status.optString("note", "") : "";
        data.sampleCount = status != null ? Math.max(0, status.optInt("sample_count", 0)) : 0;
        return data;
    }

    private static KpiData applyFallbacksFromTrends(KpiData kpi, TrendData trends) {
        if (Double.isNaN(kpi.liveMbps) || kpi.liveMbps <= 0.0) {
            kpi.liveMbps = HudRenderSupport.latestFiniteFromSeries(trends.trendMbpsArr);
        }
        if (Double.isNaN(kpi.present) || kpi.present <= 0.0) {
            kpi.present = HudRenderSupport.latestFiniteFromSeries(trends.trendFpsArr);
        }
        if (Double.isNaN(kpi.recv) || kpi.recv <= 0.0) {
            kpi.recv = HudRenderSupport.latestFiniteFromSeries(trends.trendRecvArr);
        }
        if (Double.isNaN(kpi.decode) || kpi.decode <= 0.0) {
            kpi.decode = HudRenderSupport.latestFiniteFromSeries(trends.trendDecodeArr);
        }
        if (Double.isNaN(kpi.drops) || kpi.drops < 0.0) {
            kpi.drops = HudRenderSupport.latestFiniteFromSeries(trends.trendDropArr);
        }
        if (Double.isNaN(kpi.latency) || kpi.latency < 0.0) {
            kpi.latency = HudRenderSupport.latestFiniteFromSeries(trends.trendLatencyArr);
        }
        if (Double.isNaN(kpi.queue) || kpi.queue < 0.0) {
            kpi.queue = HudRenderSupport.latestFiniteFromSeries(trends.trendQueueArr);
        }
        if (Double.isNaN(kpi.late) || kpi.late < 0.0) {
            kpi.late = HudRenderSupport.latestFiniteFromSeries(trends.trendLateArr);
        }
        return kpi;
    }

    // Data transfer objects
    private static class HeaderData {
        String runId;
        String profile;
        String trialId;
        int gIdx;
        int gTotal;
        int tIdx;
        int tTotal;
    }

    private static class ConfigData {
        String encoder;
        String size;
        String fontProfile;
        int fps;
        String layoutMode;
        String bestTrial;
        double bestScore;
    }

    private static class KpiData {
        double score;
        double present;
        double recv;
        double decode;
        double liveMbps;
        double latency;
        double drops;
        double queue;
        double renderMs;
        double late;
    }

    private static class StateData {
        String fpsState;
        String latState;
        String mbpsState;
        String dropState;
        String queueState;
        String qualityState;
    }

    private static class TrendData {
        JSONArray trendScoreArr;
        JSONArray trendFpsArr;
        JSONArray trendRecvArr;
        JSONArray trendDecodeArr;
        JSONArray trendMbpsArr;
        JSONArray trendDropArr;
        JSONArray trendLatencyArr;
        JSONArray trendQueueArr;
        JSONArray trendLateArr;
    }

    private static class StatusData {
        String statusNote;
        int sampleCount;
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
