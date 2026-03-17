package com.wbeam.hud;

/**
 * Renders full HTML shell for runtime HUD.
 */
@SuppressWarnings("java:S107")
public final class RuntimeHudShellRenderer {
    private static final String COLOR_BORDER_PRIMARY = "rgba(126,245,255,.52)";
    private static final String COLOR_BORDER_SECONDARY = "rgba(126,245,255,.5)";
    private static final String COLOR_BORDER_TERTIARY = "rgba(126,245,255,.42)";
    private static final String COLOR_BORDER_SUBTLE = "rgba(126,245,255,.35)";
    private static final String COLOR_BORDER_LIGHT = "rgba(126,245,255,.28)";
    private static final String COLOR_BORDER_MINIMAL = "rgba(126,245,255,.36)";
    private static final String COLOR_BORDER_FAINT = "rgba(126,245,255,.24)";
    private static final String BG_DARK_MEDIUM = "rgba(0,0,0,.4)";
    private static final String BG_DARK_LIGHT = "rgba(0,0,0,.24)";
    private static final String BG_DARK_LIGHTER = "rgba(0,0,0,.26)";
    private static final String BG_NAVY_MEDIUM = "rgba(2,10,14,.45)";
    private static final String BG_NAVY_DARK = "rgba(2,10,14,.35)";
    private static final String HUD_MODE_CHIP = HudRenderSupport.hudChip("HUD MODE", "RUNTIME", "");
    private static final String EMPTY = "";
    private static final String HTML_PREFIX =
            "<!doctype html><html><head><meta charset='utf-8'/>"
                    + "<meta name='viewport' content='width=device-width,height=device-height,initial-scale=1,maximum-scale=1,viewport-fit=cover'/>"
                    + "<style>"
                    + "html,body{margin:0;padding:0;background:transparent;color:#ecfbff;font-family:'JetBrains Mono','IBM Plex Mono',monospace;font-size:14px;width:100%;height:100%;}"
                    + ".root{position:fixed;inset:0;padding:6px;box-sizing:border-box;display:grid;grid-template-rows:auto minmax(0,1fr);gap:6px;}"
                    + ".top{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:6px;}"
                    + ".chip{border:1px solid " + COLOR_BORDER_PRIMARY + ";background:rgba(6,24,31,.82);padding:8px 10px;min-height:42px;}"
                    + ".chip .k{font-size:11px;color:#9dddea;display:block;letter-spacing:.05em;}"
                    + ".chip .v{font-size:14px;color:#ecfbff;word-break:break-word;font-weight:700;}"
                    + ".main{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(0,1fr);gap:6px;min-height:0;}"
                    + ".panel{border:1px solid " + COLOR_BORDER_SECONDARY + ";background:rgba(6,24,31,.88);padding:8px;min-height:0;overflow:auto;}"
                    + ".panel-main{display:grid;grid-template-rows:auto minmax(0,1fr) auto auto;gap:8px;overflow:hidden;}"
                    + ".panel-side{overflow:auto;}"
                    + ".kpi{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;}"
                    + ".kpi .item{border:1px solid " + COLOR_BORDER_TERTIARY + ";padding:8px;background:" + BG_DARK_MEDIUM + ";}"
                    + ".kpi .item .k{font-size:11px;color:#9dddea;display:block;}"
                    + ".kpi .item .v{font-size:18px;color:#dcf9ff;font-weight:700;line-height:1.15;}"
                    + ".trend{font-size:11px;line-height:1.35;margin-top:6px;color:#9dddea;word-break:break-word;}"
                    + ".metric-trends{margin-top:0;border:1px solid " + COLOR_BORDER_SUBTLE + ";padding:8px;background:" + BG_NAVY_MEDIUM + ";display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;min-height:0;overflow:auto;}"
                    + ".trend-row{display:grid;grid-template-columns:1fr;gap:6px;}"
                    + ".trend-label{font-size:11px;color:#9dddea;letter-spacing:.04em;}"
                    + ".trend-range{font-size:12px;color:#d6fbff;text-align:right;white-space:nowrap;font-weight:700;}"
                    + ".trend-card{border:1px solid " + COLOR_BORDER_LIGHT + ";padding:6px;background:" + BG_DARK_LIGHT + ";display:grid;grid-template-rows:auto minmax(0,1fr);gap:6px;min-width:0;min-height:124px;}"
                    + ".trend-head{display:flex;align-items:center;justify-content:space-between;gap:6px;}"
                    + ".trend-meta{display:grid;grid-template-rows:auto auto;gap:4px;}"
                    + ".trend-meta-row{display:grid;grid-template-columns:1fr 1fr 1fr;align-items:center;gap:6px;}"
                    + ".trend-meta-item{font-size:13px;font-weight:700;color:#d6fbff;}"
                    + ".trend-meta-item.mid{text-align:center;}"
                    + ".trend-meta-item.high{text-align:right;}"
                    + ".trend-meta-cur{font-size:12px;color:#b9f8ff;font-weight:700;}"
                    + ".resource{margin-top:7px;border:1px solid " + COLOR_BORDER_SUBTLE + ";padding:6px;background:" + BG_NAVY_DARK + ";display:grid;gap:5px;}"
                    + ".resource .title{font-size:10px;color:#9dddea;letter-spacing:.05em;}"
                    + ".res-row{display:grid;grid-template-columns:42px 62px minmax(0,1fr);align-items:center;gap:6px;}"
                    + ".res-row .rk{font-size:10px;color:#9dddea;}"
                    + ".res-row .rv{font-size:11px;color:#dcf9ff;}"
                    + ".spark{height:92px;display:flex;align-items:flex-end;gap:1px;border:1px solid " + COLOR_BORDER_FAINT + ";padding:2px;background:" + BG_DARK_LIGHTER + ";overflow:hidden;min-width:0;}"
                    + ".spark-svg{width:100%;height:100%;display:block;}"
                    + ".spark-bar{flex:1 1 0;min-width:2px;border-radius:2px 2px 0 0;background:linear-gradient(180deg,rgba(110,231,183,.96),rgba(110,231,183,.2));}"
                    + ".spark-bar.state-warn{background:linear-gradient(180deg,rgba(251,191,36,.96),rgba(251,191,36,.2));}"
                    + ".spark-bar.state-risk{background:linear-gradient(180deg,rgba(248,113,113,.96),rgba(248,113,113,.2));}"
                    + ".detail-table{width:100%;border-collapse:collapse;table-layout:fixed;}"
                    + ".detail-table td{border:1px solid " + COLOR_BORDER_MINIMAL + ";padding:4px 6px;vertical-align:top;word-break:break-word;}"
                    + ".detail-table td:first-child{width:52%;color:#dffcff;} .detail-table td:last-child{text-align:right;color:#b9f8ff;}"
                    + ".state-ok{color:#6ee7b7;} .state-warn{color:#fbbf24;} .state-risk{color:#f87171;} .state-pending{color:#94a3b8;}"
                    + "@media (max-width:980px){.main{grid-template-columns:1fr;}.metric-trends{grid-template-columns:1fr;}.spark{height:110px;}}"
                    + "</style></head><body class='";
    private static final String HTML_MID =
            "'><div class='root'><div class='top'>" + HUD_MODE_CHIP + "<div id='hud-chips'>";
    private static final String HTML_MAIN_PREFIX =
            "</div></div><div class='main'><div class='panel panel-main'><div class='kpi' id='hud-cards'>";
    private static final String HTML_MAIN_MID =
            "</div><div class='metric-trends' id='hud-charts'>";
    private static final String HTML_TREND_PREFIX =
            "</div><div class='trend' id='hud-trend'>";
    private static final String HTML_RESOURCE_PREFIX =
            "</div><div class='resource'><div class='title'>DEVICE RESOURCES (* GPU proxy from render time)</div><div id='hud-resources'>";
    private static final String HTML_SIDE_PREFIX =
            "</div></div></div><div class='panel panel-side'><table class='detail-table' id='hud-details'>";
    private static final String HTML_SCRIPT_SUFFIX =
            "</table></div></div></div>"
                    + "<script>(function(){"
                    + "function byId(id){return document.getElementById(id);}"
                    + "function setHtml(id,v){var e=byId(id);if(!e){return;}var n=v||'';if(e.innerHTML!==n){e.innerHTML=n;}}"
                    + "function setText(id,v){var e=byId(id);if(!e){return;}var n=v||'';if(e.textContent!==n){e.textContent=n;}}"
                    + "window.__wbeamHudUpdate=function(p){if(!p){return;}var cls=p.bodyClass||'';"
                    + "if(document.body.className!==cls){document.body.className=cls;}"
                    + "setHtml('hud-chips',p.chipsHtml);"
                    + "setHtml('hud-cards',p.cardsHtml);"
                    + "setHtml('hud-charts',p.chartsHtml);"
                    + "setText('hud-trend',p.trendText);"
                    + "setHtml('hud-resources',p.resourceRowsHtml);"
                    + "setHtml('hud-details',p.detailsRowsHtml);};"
                    + "if(window.__wbeamHudPending){window.__wbeamHudUpdate(window.__wbeamHudPending);window.__wbeamHudPending=null;}"
                    + "})();</script>";
    private static final String HTML_SUFFIX =
            HTML_SCRIPT_SUFFIX + "</body></html>";

    @SuppressWarnings("java:S1104")
    public static final class HtmlContent {
        private String chipsHtml;
        private String cardsHtml;
        private String chartsHtml;
        private String trendText;
        private String detailsRowsHtml;
        private String resourceRowsHtml;
        private String scaleClass;

        public String getChipsHtml() {
            return chipsHtml;
        }

        public void setChipsHtml(String chipsHtml) {
            this.chipsHtml = chipsHtml;
        }

        public String getCardsHtml() {
            return cardsHtml;
        }

        public void setCardsHtml(String cardsHtml) {
            this.cardsHtml = cardsHtml;
        }

        public String getChartsHtml() {
            return chartsHtml;
        }

        public void setChartsHtml(String chartsHtml) {
            this.chartsHtml = chartsHtml;
        }

        public String getTrendText() {
            return trendText;
        }

        public void setTrendText(String trendText) {
            this.trendText = trendText;
        }

        public String getDetailsRowsHtml() {
            return detailsRowsHtml;
        }

        public void setDetailsRowsHtml(String detailsRowsHtml) {
            this.detailsRowsHtml = detailsRowsHtml;
        }

        public String getResourceRowsHtml() {
            return resourceRowsHtml;
        }

        public void setResourceRowsHtml(String resourceRowsHtml) {
            this.resourceRowsHtml = resourceRowsHtml;
        }

        public String getScaleClass() {
            return scaleClass;
        }

        public void setScaleClass(String scaleClass) {
            this.scaleClass = scaleClass;
        }
    }

    private RuntimeHudShellRenderer() {}

    public static String buildHtml(HtmlContent content) {
        String bodyClass = bodyClass(content);
        StringBuilder html = new StringBuilder(6144);
        html.append(HTML_PREFIX)
                .append(bodyClass)
                .append(HTML_MID)
                .append(nonNull(content.getChipsHtml()))
                .append(HTML_MAIN_PREFIX)
                .append(nonNull(content.getCardsHtml()))
                .append(HTML_MAIN_MID)
                .append(nonNull(content.getChartsHtml()))
                .append(HTML_TREND_PREFIX)
                .append(HudRenderSupport.escapeHtml(HudRenderSupport.safeText(content.getTrendText())))
                .append(HTML_RESOURCE_PREFIX)
                .append(nonNull(content.getResourceRowsHtml()))
                .append(HTML_SIDE_PREFIX)
                .append(nonNull(content.getDetailsRowsHtml()))
                .append(HTML_SUFFIX);
        return html.toString();
    }

    public static String buildUpdateScript(HtmlContent content) {
        return "window.__wbeamHudUpdate(" + buildUpdatePayloadJson(content) + ");";
    }

    private static String buildUpdatePayloadJson(HtmlContent content) {
        StringBuilder json = new StringBuilder(1536);
        json.append('{')
                .append("\"bodyClass\":").append(jsString(bodyClass(content))).append(',')
                .append("\"chipsHtml\":").append(jsString(nonNull(content.getChipsHtml()))).append(',')
                .append("\"cardsHtml\":").append(jsString(nonNull(content.getCardsHtml()))).append(',')
                .append("\"chartsHtml\":").append(jsString(nonNull(content.getChartsHtml()))).append(',')
                .append("\"trendText\":").append(jsString(HudRenderSupport.safeText(content.getTrendText()))).append(',')
                .append("\"detailsRowsHtml\":").append(jsString(nonNull(content.getDetailsRowsHtml()))).append(',')
                .append("\"resourceRowsHtml\":").append(jsString(nonNull(content.getResourceRowsHtml())))
                .append('}');
        return json.toString();
    }

    private static String bodyClass(HtmlContent content) {
        return "hud-live " + HudRenderSupport.safeText(content == null ? EMPTY : content.getScaleClass());
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static String jsString(String value) {
        String text = value == null ? EMPTY : value;
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        escaped.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int p = hex.length(); p < 4; p++) {
                            escaped.append('0');
                        }
                        escaped.append(hex);
                    } else {
                        escaped.append(c);
                    }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }
}
