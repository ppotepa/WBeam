package com.wbeam.hud;

import org.json.JSONArray;

import java.util.Locale;

/**
 * Renders full HTML shell for trainer HUD (SOT layout).
 */
public final class TrainerHudShellRenderer {
    private static final String STATE_PENDING = "PENDING";
    private static final String SCALE_2X = "scale-2x";

    private TrainerHudShellRenderer() {}

    @SuppressWarnings("java:S107")
    public static String buildSotHtml(
            String runId,
            String profile,
            String encoder,
            String size,
            int fps,
            int gIdx,
            int gTotal,
            int tIdx,
            int tTotal,
            String trialId,
            int progressPercent,
            String progressLine,
            double score,
            double present,
            double recv,
            double decode,
            double liveMbps,
            double latency,
            double drops,
            double queue,
            double late,
            int sampleCount,
            String bestTrial,
            double bestScore,
            String fpsState,
            String mbpsState,
            String latState,
            String dropState,
            String queueState,
            String qualityState,
            String statusNote,
            JSONArray trendScore,
            JSONArray trendPresent,
            JSONArray trendMbps,
            JSONArray trendLatency,
            JSONArray trendDrops,
            JSONArray trendQueue,
            JSONArray trendRecv,
            JSONArray trendDecode,
            String resourceRowsHtml,
            String layoutMode,
            String fontProfile,
            double fpsLowAnchor
    ) {
        int pct = progressPercent;
        if (pct < 0 && tTotal > 0) {
            pct = (int) Math.round((Math.max(0, tIdx) * 100.0) / Math.max(1, tTotal));
        }
        pct = clampPercent(pct);
        String progressText = progressLine == null || progressLine.trim().isEmpty()
                ? String.format(Locale.US, "TRAINING PROGRESS %d%%", pct)
                : progressLine.trim();
        String modeLine = HudRenderSupport.safeText(encoder).toUpperCase(Locale.US)
                + " | " + HudRenderSupport.safeText(size)
                + " | " + Math.max(0, fps) + "fps";
        String liveTriplet = String.format(
                Locale.US,
                "P/R/D %s / %s / %s",
                HudRenderSupport.fmtDoubleOrPlaceholder(present, "%.1f", STATE_PENDING),
                HudRenderSupport.fmtDoubleOrPlaceholder(recv, "%.1f", STATE_PENDING),
                HudRenderSupport.fmtDoubleOrPlaceholder(decode, "%.1f", STATE_PENDING)
        );
        String statusLine1 = "best_trial: " + HudRenderSupport.safeText(bestTrial) + "    best_score: "
                + HudRenderSupport.fmtDoubleOrPlaceholder(bestScore, "%.2f", STATE_PENDING);
        String statusLine2 = "sample_count: " + sampleCount + "    state.fps: " + HudRenderSupport.safeText(fpsState);
        String statusLine3 = "state.mbps: " + HudRenderSupport.safeText(mbpsState) + "    state.latency: " + HudRenderSupport.safeText(latState);
        String statusLine4 = "state.drop: " + HudRenderSupport.safeText(dropState) + "    state.queue: " + HudRenderSupport.safeText(queueState);
        String statusLine5 = "quality: " + HudRenderSupport.safeText(qualityState) + "    note: " + HudRenderSupport.safeText(statusNote);

        String trendGrid = buildSotTrendCellHtml("SCORE TREND", trendScore, HudRenderSupport.hudToneClass(qualityState), "", fpsLowAnchor)
                + buildSotTrendCellHtml("PRESENT FPS TREND", trendPresent, HudRenderSupport.hudToneClass(fpsState), "", fpsLowAnchor)
                + buildSotTrendCellHtml("LIVE MBPS TREND", trendMbps, HudRenderSupport.hudToneClass(mbpsState), "Mbps", fpsLowAnchor)
                + buildSotTrendCellHtml("LAT p95 TREND", trendLatency, HudRenderSupport.hudToneClass(latState), "ms", fpsLowAnchor)
                + buildSotTrendCellHtml("DROPS/s TREND", trendDrops, HudRenderSupport.hudToneClass(dropState), "", fpsLowAnchor)
                + buildSotTrendCellHtml("QUEUE DEPTH TREND", trendQueue, HudRenderSupport.hudToneClass(queueState), "", fpsLowAnchor)
                + buildSotTrendCellHtml("RECV FPS TREND", trendRecv, HudRenderSupport.hudToneClass(fpsState), "", fpsLowAnchor)
                + buildSotTrendCellHtml("DECODE FPS TREND", trendDecode, HudRenderSupport.hudToneClass(fpsState), "", fpsLowAnchor);

        String hudShellClass = "sot shell " + TRAINER_SCALE_CLASS;
        return "<!doctype html><html><head><meta charset='utf-8'/>"
                + "<meta name='viewport' content='width=device-width,height=device-height,initial-scale=1,maximum-scale=1,viewport-fit=cover'/>"
                + "<style>"
                + "html,body{margin:0;padding:0;background:transparent;color:#ecfbff;font-family:'JetBrains Mono','IBM Plex Mono',monospace;width:100%;height:100%;}"
                + ".sot.shell{position:fixed;inset:0;padding:6px;box-sizing:border-box;display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:6px;}"
                + ".sot .panel{background:rgba(4,25,34,.78);border:1px solid rgba(110,242,255,.34);border-radius:8px;padding:8px;min-width:0;}"
                + ".sot .header{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:6px;}"
                + ".sot .chip{background:rgba(6,39,55,.85);border:1px solid rgba(124,236,255,.42);border-radius:6px;padding:6px 8px;min-width:0;}"
                + ".sot .chip .k{display:block;font-size:11px;color:#9fe8f2;letter-spacing:.04em;}"
                + ".sot .chip .v{display:block;font-size:16px;color:#f0fdff;font-weight:700;line-height:1.15;word-break:break-word;}"
                + ".sot .main{display:grid;grid-template-columns:42% 58%;gap:6px;min-height:0;}"
                + ".sot .left-col,.sot .right-col{display:grid;gap:6px;min-height:0;min-width:0;}"
                + ".sot .left-col{grid-template-rows:repeat(3,minmax(98px,auto)) minmax(0,1fr);}"
                + ".sot .kpi-block{background:rgba(3,19,27,.78);border:1px solid rgba(110,242,255,.24);border-radius:6px;padding:8px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;}"
                + ".sot .kpi-item .k{display:block;font-size:11px;color:#9fe8f2;}"
                + ".sot .kpi-item .v{display:block;font-size:20px;color:#f0fdff;font-weight:700;line-height:1.15;word-break:break-word;}"
                + ".sot .status{background:rgba(3,19,27,.78);border:1px solid rgba(110,242,255,.24);border-radius:6px;padding:8px;display:grid;gap:5px;align-content:start;}"
                + ".sot .status .row{font-size:12px;color:#c8f7ff;line-height:1.28;word-break:break-word;}"
                + ".sot .trend-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;min-height:0;align-content:stretch;grid-auto-rows:minmax(128px,1fr);}"
                + ".sot .trend-cell{background:rgba(3,19,27,.78);border:1px solid rgba(110,242,255,.24);border-radius:6px;padding:7px;display:grid;grid-template-rows:auto minmax(0,1fr);gap:6px;min-width:0;min-height:128px;}"
                + ".sot .trend-head{display:flex;justify-content:space-between;align-items:center;gap:8px;min-width:0;}"
                + ".sot .trend-title{font-size:11px;color:#9fe8f2;letter-spacing:.03em;}"
                + ".sot .trend-stats{font-size:12px;color:#d8fbff;white-space:nowrap;font-weight:700;}"
                + ".sot .spark{height:94px;display:grid;grid-auto-flow:column;grid-auto-columns:minmax(0,1fr);align-items:end;gap:1px;border:1px solid rgba(114,231,255,.3);background:rgba(2,12,18,.95);padding:2px;overflow:hidden;min-width:0;}"
                + ".sot .spark-svg{width:100%;height:100%;display:block;}"
                + ".sot .trend-meta{display:grid;grid-template-rows:auto auto;gap:4px;}"
                + ".sot .trend-meta-row{display:grid;grid-template-columns:1fr 1fr 1fr;align-items:center;gap:6px;}"
                + ".sot .trend-meta-item{font-size:13px;font-weight:700;color:#d8fbff;}"
                + ".sot .trend-meta-item.mid{text-align:center;}"
                + ".sot .trend-meta-item.high{text-align:right;}"
                + ".sot .trend-meta-cur{font-size:12px;color:#c8f7ff;font-weight:700;}"
                + ".sot .spark .spark-bar{width:100%;min-width:0;border-radius:2px 2px 0 0;background:linear-gradient(180deg,rgba(141,217,255,.96),rgba(141,217,255,.24));}"
                + ".sot .spark .spark-bar.state-warn{background:linear-gradient(180deg,rgba(251,191,36,.96),rgba(251,191,36,.24));}"
                + ".sot .spark .spark-bar.state-risk{background:linear-gradient(180deg,rgba(248,113,113,.96),rgba(248,113,113,.24));}"
                + ".sot .ok{color:#6ee7b7;} .sot .warn{color:#fbbf24;} .sot .risk{color:#f87171;}"
                + ".sot .state-ok{color:#6ee7b7;} .sot .state-warn{color:#fbbf24;} .sot .state-risk{color:#f87171;} .sot .state-pending{color:#94a3b8;}"
                + ".sot .res-row{display:grid;grid-template-columns:46px 70px minmax(0,1fr);align-items:center;gap:6px;margin-top:4px;}"
                + ".sot .res-row .rk{font-size:11px;color:#9fe8f2;}"
                + ".sot .res-row .rv{font-size:12px;color:#f0fdff;}"
                + ".sot .footer{display:grid;grid-template-columns:1fr 1fr 1fr;gap:6px;}"
                + ".sot .foot{background:rgba(3,19,27,.78);border:1px solid rgba(110,242,255,.24);border-radius:6px;padding:6px 8px;font-size:12px;color:#c8f7ff;line-height:1.3;word-break:break-word;}"
                + ".sot.scale-2x .chip .k{font-size:12px;}.sot.scale-2x .chip .v{font-size:20px;}.sot.scale-2x .kpi-item .k{font-size:12px;}.sot.scale-2x .kpi-item .v{font-size:24px;}.sot.scale-2x .status .row{font-size:14px;}.sot.scale-2x .trend-title{font-size:13px;}.sot.scale-2x .trend-stats{font-size:12px;}.sot.scale-2x .spark{height:110px;}"
                + "@media (max-width:1200px){.sot .main{grid-template-columns:1fr;}.sot .trend-grid{grid-template-columns:1fr;}.sot .footer{grid-template-columns:1fr;}}"
                + "</style></head><body class='hud-trainer'><div class='" + hudShellClass + "'>"
                + "<div class='panel header'>"
                + "<div class='chip'><span class='k'>RUN / PROFILE</span><span class='v'>" + HudRenderSupport.escapeHtml(HudRenderSupport.safeText(runId) + " / " + HudRenderSupport.safeText(profile)) + "</span></div>"
                + "<div class='chip'><span class='k'>GEN</span><span class='v'>" + HudRenderSupport.escapeHtml(gIdx + "/" + gTotal) + "</span></div>"
                + "<div class='chip'><span class='k'>TRIAL</span><span class='v'>" + HudRenderSupport.escapeHtml(tIdx + "/" + tTotal + " (" + HudRenderSupport.safeText(trialId) + ")") + "</span></div>"
                + "<div class='chip'><span class='k'>CURRENT MODE</span><span class='v'>" + HudRenderSupport.escapeHtml(modeLine) + "</span></div>"
                + "<div class='chip'><span class='k'>PROGRESS</span><span class='v'>" + HudRenderSupport.escapeHtml(progressText + " | " + pct + "%") + "</span></div>"
                + "</div>"
                + "<div class='main'>"
                + "<div class='panel left-col'>"
                + "<div class='kpi-block'>"
                + "<div class='kpi-item'><span class='k'>SCORE</span><span class='v " + HudRenderSupport.escapeHtml(HudRenderSupport.hudToneClass(qualityState)) + "'>" + HudRenderSupport.escapeHtml(HudRenderSupport.fmtDoubleOrPlaceholder(score, "%.2f", STATE_PENDING)) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>PRESENT FPS</span><span class='v " + HudRenderSupport.escapeHtml(HudRenderSupport.hudToneClass(fpsState)) + "'>" + HudRenderSupport.escapeHtml(HudRenderSupport.fmtDoubleOrPlaceholder(present, "%.1f", STATE_PENDING)) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>LIVE MBPS</span><span class='v " + HudRenderSupport.escapeHtml(HudRenderSupport.hudToneClass(mbpsState)) + "'>" + HudRenderSupport.escapeHtml(HudRenderSupport.fmtLiveMbps(liveMbps, Double.NaN) + " Mbps") + "</span></div>"
                + "</div>"
                + "<div class='kpi-block'>"
                + "<div class='kpi-item'><span class='k'>RECV FPS</span><span class='v'>" + HudRenderSupport.escapeHtml(HudRenderSupport.fmtDoubleOrPlaceholder(recv, "%.1f", STATE_PENDING)) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>DECODE FPS</span><span class='v'>" + HudRenderSupport.escapeHtml(HudRenderSupport.fmtDoubleOrPlaceholder(decode, "%.1f", STATE_PENDING)) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>LAT p95</span><span class='v " + HudRenderSupport.escapeHtml(HudRenderSupport.hudToneClass(latState)) + "'>" + HudRenderSupport.escapeHtml(HudRenderSupport.fmtDoubleOrPlaceholder(latency, "%.1f ms", STATE_PENDING)) + "</span></div>"
                + "</div>"
                + "<div class='kpi-block'>"
                + "<div class='kpi-item'><span class='k'>DROPS/s</span><span class='v " + HudRenderSupport.escapeHtml(HudRenderSupport.hudToneClass(dropState)) + "'>" + HudRenderSupport.escapeHtml(HudRenderSupport.fmtDoubleOrPlaceholder(drops, "%.3f", STATE_PENDING)) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>LATE/s</span><span class='v " + HudRenderSupport.escapeHtml(HudRenderSupport.hudToneClass(dropState)) + "'>" + HudRenderSupport.escapeHtml(HudRenderSupport.fmtDoubleOrPlaceholder(late, "%.3f", STATE_PENDING)) + "</span></div>"
                + "<div class='kpi-item'><span class='k'>QUEUE</span><span class='v " + HudRenderSupport.escapeHtml(HudRenderSupport.hudToneClass(queueState)) + "'>" + HudRenderSupport.escapeHtml(HudRenderSupport.fmtDoubleOrPlaceholder(queue, "%.3f", STATE_PENDING)) + "</span></div>"
                + "</div>"
                + "<div class='status'>"
                + "<div class='row'>" + HudRenderSupport.escapeHtml(statusLine1) + "</div>"
                + "<div class='row'>" + HudRenderSupport.escapeHtml(statusLine2) + "</div>"
                + "<div class='row'>" + HudRenderSupport.escapeHtml(statusLine3) + "</div>"
                + "<div class='row'>" + HudRenderSupport.escapeHtml(statusLine4) + "</div>"
                + "<div class='row'>" + HudRenderSupport.escapeHtml(statusLine5) + "</div>"
                + "<div class='row'>" + HudRenderSupport.escapeHtml(liveTriplet) + "</div>"
                + "</div>"
                + "</div>"
                + "<div class='panel right-col'><div class='trend-grid'>" + trendGrid + "</div></div>"
                + "</div>"
                + "<div class='footer'>"
                + "<div class='foot'><strong>LAYOUT/FONT</strong><br/>layout=" + HudRenderSupport.escapeHtml(HudRenderSupport.safeText(layoutMode)) + " | font=" + HudRenderSupport.escapeHtml(HudRenderSupport.safeText(fontProfile)) + "</div>"
                + "<div class='foot'><strong>DEVICE RESOURCES (* GPU proxy)</strong><br/>" + resourceRowsHtml + "</div>"
                + "<div class='foot'><strong>THRESHOLDS</strong><br/><span class='ok'>OK</span> stable | <span class='warn'>WARN</span> drift | <span class='risk'>RISK</span> critical</div>"
                + "</div>"
                + "</div></body></html>";
    }

    private static String buildSotTrendCellHtml(
            String label,
            JSONArray series,
            String toneClass,
            String unitSuffix,
            double fpsLowAnchor
    ) {
        String stats = HudRenderSupport.buildSeriesStats(series, unitSuffix);
        String meta = HudRenderSupport.buildSeriesMetaHtml(label, series, unitSuffix, fpsLowAnchor);
        return "<div class='trend-cell'>"
                + "<div class='trend-head'><span class='trend-title'>" + HudRenderSupport.escapeHtml(label) + "</span>"
                + "<span class='trend-stats " + HudRenderSupport.escapeHtml(toneClass == null ? "" : toneClass) + "'>" + HudRenderSupport.escapeHtml(stats) + "</span></div>"
                + "<div class='spark'>" + HudRenderSupport.buildTrendSparkChartFromJson(series, toneClass) + "</div>"
                + meta
                + "</div>";
    }

    private static final String TRAINER_SCALE_CLASS = SCALE_2X;

    private static int clampPercent(int progressPercent) {
        if (progressPercent < 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, progressPercent));
    }
}
