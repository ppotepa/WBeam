package com.wbeam.hud;

import org.json.JSONArray;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Pure HUD rendering/formatting helpers used by runtime overlays.
 */
public final class HudRenderSupport {
    private HudRenderSupport() {}

    public static String buildTrendSparkChartFromJson(JSONArray series, String toneClass) {
        if (series == null || series.length() == 0) {
            return buildTrendSparkPlaceholderSvg(toneClass);
        }
        ArrayDeque<Double> values = new ArrayDeque<>();
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < series.length(); i++) {
            if (series.isNull(i)) {
                continue;
            }
            double v = series.optDouble(i, Double.NaN);
            if (!Double.isFinite(v)) {
                continue;
            }
            values.addLast(v);
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (!Double.isFinite(lo) || !Double.isFinite(hi) || values.isEmpty()) {
            return buildTrendSparkPlaceholderSvg(toneClass);
        }
        double span = hi - lo;
        if (span < 1e-3) {
            span = Math.max(1.0, Math.abs(hi) * 0.25);
        }
        double pad = Math.max(0.1, span * 0.12);
        double yMin = lo - pad;
        double yMax = hi + pad;
        double ySpan = Math.max(1e-3, yMax - yMin);

        int n = values.size();
        String stroke = toneStrokeColor(toneClass);
        String fill = toneFillColor(toneClass);
        String dot = toneDotColor(toneClass);
        if (n == 1) {
            double single = values.peekFirst() == null ? yMin : values.peekFirst();
            double norm = clampDouble((single - yMin) / ySpan, 0.0, 1.0);
            double y = 100.0 - (norm * 100.0);
            return "<svg class='spark-svg' viewBox='0 0 100 100' preserveAspectRatio='none'>"
                    + "<rect x='0' y='0' width='100' height='100' fill='transparent'/>"
                    + "<line x1='0' y1='" + fmt2(y) + "' x2='100' y2='" + fmt2(y) + "' stroke='" + stroke + "' stroke-width='2.4'/>"
                    + "<circle cx='50' cy='" + fmt2(y) + "' r='2.2' fill='" + dot + "'/>"
                    + "</svg>";
        }
        StringBuilder polyline = new StringBuilder();
        StringBuilder area = new StringBuilder("M 0 100 ");
        StringBuilder dots = new StringBuilder();
        int idx = 0;
        for (Double raw : values) {
            double v = raw == null ? yMin : raw;
            double norm = (v - yMin) / ySpan;
            norm = clampDouble(norm, 0.0, 1.0);
            double x = (idx * 100.0) / Math.max(1, n - 1);
            double y = 100.0 - (norm * 100.0);
            area.append("L ").append(fmt2(x)).append(" ").append(fmt2(y)).append(" ");
            polyline.append(fmt2(x)).append(",").append(fmt2(y)).append(" ");
            dots.append("<circle cx='")
                    .append(fmt2(x))
                    .append("' cy='")
                    .append(fmt2(y))
                    .append("' r='1.0' fill='")
                    .append(dot)
                    .append("'/>");
            idx++;
        }
        area.append("L 100 100 Z");
        return "<svg class='spark-svg' viewBox='0 0 100 100' preserveAspectRatio='none'>"
                + "<rect x='0' y='0' width='100' height='100' fill='transparent'/>"
                + "<path d='" + area + "' fill='" + fill + "'/>"
                + "<polyline points='" + polyline + "' fill='none' stroke='" + stroke + "' stroke-width='2.4' stroke-linecap='round' stroke-linejoin='round'/>"
                + dots
                + "</svg>";
    }

    private static String buildTrendSparkPlaceholderSvg(String toneClass) {
        String stroke = toneStrokeColor(toneClass);
        String fill = toneFillColor(toneClass);
        return "<svg class='spark-svg' viewBox='0 0 100 100' preserveAspectRatio='none'>"
                + "<rect x='0' y='0' width='100' height='100' fill='transparent'/>"
                + "<path d='M 0 100 L 0 70 L 15 68 L 30 72 L 45 66 L 60 69 L 75 63 L 90 65 L 100 62 L 100 100 Z' fill='" + fill + "'/>"
                + "<polyline points='0,70 15,68 30,72 45,66 60,69 75,63 90,65 100,62' fill='none' stroke='" + stroke + "' stroke-width='2.1' stroke-linecap='round' stroke-linejoin='round' stroke-dasharray='4 4'/>"
                + "</svg>";
    }

    private static String toneStrokeColor(String toneClass) {
        String tone = toneClass == null ? "" : toneClass.trim().toLowerCase(Locale.US);
        if ("state-risk".equals(tone)) {
            return "#f87171";
        }
        if ("state-warn".equals(tone)) {
            return "#fbbf24";
        }
        if ("state-ok".equals(tone)) {
            return "#6ee7b7";
        }
        return "#8dd9ff";
    }

    private static String toneFillColor(String toneClass) {
        String tone = toneClass == null ? "" : toneClass.trim().toLowerCase(Locale.US);
        if ("state-risk".equals(tone)) {
            return "rgba(248,113,113,0.20)";
        }
        if ("state-warn".equals(tone)) {
            return "rgba(251,191,36,0.22)";
        }
        if ("state-ok".equals(tone)) {
            return "rgba(110,231,183,0.20)";
        }
        return "rgba(141,217,255,0.20)";
    }

    private static String toneDotColor(String toneClass) {
        String tone = toneClass == null ? "" : toneClass.trim().toLowerCase(Locale.US);
        if ("state-risk".equals(tone)) {
            return "#fecaca";
        }
        if ("state-warn".equals(tone)) {
            return "#fde68a";
        }
        if ("state-ok".equals(tone)) {
            return "#bbf7d0";
        }
        return "#dbeafe";
    }

    private static String fmt2(double value) {
        if (!Double.isFinite(value)) {
            return "0.00";
        }
        return String.format(Locale.US, "%.2f", value);
    }

    public static String buildSparkPlaceholderBars(String toneClass, int count) {
        int n = Math.max(8, Math.min(64, count));
        String cls = escapeHtml(toneClass == null ? "" : toneClass);
        StringBuilder bars = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int h = 12 + ((i % 4) * 3);
            bars.append("<span class='spark-bar ")
                    .append(cls)
                    .append("' style='height:")
                    .append(h)
                    .append("%'></span>");
        }
        return bars.toString();
    }

    public static String buildSeriesStats(JSONArray series, String unitSuffix) {
        if (series == null || series.length() == 0) {
            return "PENDING";
        }
        double last = Double.NaN;
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < series.length(); i++) {
            if (series.isNull(i)) {
                continue;
            }
            double v = series.optDouble(i, Double.NaN);
            if (!Double.isFinite(v)) {
                continue;
            }
            last = v;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (!Double.isFinite(last) || !Double.isFinite(lo) || !Double.isFinite(hi)) {
            return "PENDING";
        }
        String unit = unitSuffix == null ? "" : unitSuffix.trim();
        if (!unit.isEmpty()) {
            unit = " " + unit;
        }
        return String.format(Locale.US, "L %.2f%s · %.2f..%.2f", last, unit, lo, hi);
    }

    public static String buildSeriesMetaHtml(
            String metricLabel,
            JSONArray series,
            String unitSuffix,
            double fpsLowAnchor
    ) {
        double[] w = computeSeriesWindow(metricLabel, series, fpsLowAnchor);
        String unit = unitSuffix == null ? "" : unitSuffix.trim();
        if (!unit.isEmpty()) {
            unit = " " + unit;
        }
        if (w == null) {
            return "<div class='trend-meta'>"
                    + "<div class='trend-meta-row'>"
                    + "<span class='trend-meta-item low'>LOW: -</span>"
                    + "<span class='trend-meta-item mid'>MID: -</span>"
                    + "<span class='trend-meta-item high'>HIGH: -</span>"
                    + "</div>"
                    + "<div class='trend-meta-cur'>CUR: -</div>"
                    + "</div>";
        }
        return "<div class='trend-meta'>"
                + "<div class='trend-meta-row'>"
                + "<span class='trend-meta-item low'>LOW: " + escapeHtml(fmt1(w[1]) + unit) + "</span>"
                + "<span class='trend-meta-item mid'>MID: " + escapeHtml(fmt1(w[2]) + unit) + "</span>"
                + "<span class='trend-meta-item high'>HIGH: " + escapeHtml(fmt1(w[3]) + unit) + "</span>"
                + "</div>"
                + "<div class='trend-meta-cur'>CUR: " + escapeHtml(fmt1(w[0]) + unit) + "</div>"
                + "</div>";
    }

    private static double[] computeSeriesWindow(
            String metricLabel,
            JSONArray series,
            double fpsLowAnchor
    ) {
        if (series == null || series.length() == 0) {
            return null;
        }
        double last = Double.NaN;
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < series.length(); i++) {
            if (series.isNull(i)) {
                continue;
            }
            double v = series.optDouble(i, Double.NaN);
            if (!Double.isFinite(v)) {
                continue;
            }
            last = v;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (!Double.isFinite(last) || !Double.isFinite(lo) || !Double.isFinite(hi)) {
            return null;
        }
        String key = normalizeMetricKey(metricLabel);
        double displayLow;
        if ("fps".equals(key)) {
            displayLow = fpsLowAnchor;
        } else if ("mbps".equals(key) || "latency".equals(key) || "drops".equals(key) || "queue".equals(key)) {
            displayLow = 0.0;
        } else {
            displayLow = Math.max(0.0, Math.min(lo, hi));
        }
        double displayHigh = Math.max(hi, displayLow + 1.0);
        double mid = (displayLow + displayHigh) * 0.5;
        return new double[]{last, displayLow, mid, displayHigh};
    }

    private static String normalizeMetricKey(String metricLabel) {
        String label = metricLabel == null ? "" : metricLabel.trim().toUpperCase(Locale.US);
        if (label.contains("FPS")) {
            return "fps";
        }
        if (label.contains("MBPS") || label.contains("BITRATE")) {
            return "mbps";
        }
        if (label.contains("LAT")) {
            return "latency";
        }
        if (label.contains("DROP") || label.contains("LATE")) {
            return "drops";
        }
        if (label.contains("QUEUE")) {
            return "queue";
        }
        return "generic";
    }

    public static String fmt1(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        return String.format(Locale.US, "%.1f", value);
    }

    public static double latestFiniteFromSeries(JSONArray series) {
        if (series == null || series.length() == 0) {
            return Double.NaN;
        }
        for (int i = series.length() - 1; i >= 0; i--) {
            if (series.isNull(i)) {
                continue;
            }
            double value = series.optDouble(i, Double.NaN);
            if (Double.isFinite(value)) {
                return value;
            }
        }
        return Double.NaN;
    }

    public static String fmtDoubleOrPlaceholder(double value, String pattern, String fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) {
            return fallback;
        }
        return String.format(Locale.US, pattern, value);
    }

    public static String fmtLiveMbps(double liveMbps, double targetMbps) {
        if (!Double.isNaN(liveMbps) && !Double.isInfinite(liveMbps) && liveMbps > 0.0) {
            return String.format(Locale.US, "%.2f", liveMbps);
        }
        if (!Double.isNaN(targetMbps) && !Double.isInfinite(targetMbps) && targetMbps > 0.0) {
            return String.format(Locale.US, "%.2f (target)", targetMbps);
        }
        return "PENDING";
    }

    public static String safeText(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "-";
        }
        return value.trim();
    }

    public static String hudToneClass(String tone) {
        String t = tone == null ? "" : tone.trim().toLowerCase(Locale.US);
        if ("risk".equals(t) || "bad".equals(t) || "red".equals(t)) {
            return "state-risk";
        }
        if ("warn".equals(t) || "orange".equals(t) || "yellow".equals(t)) {
            return "state-warn";
        }
        if ("ok".equals(t) || "good".equals(t) || "green".equals(t)) {
            return "state-ok";
        }
        return "state-pending";
    }

    public static String hudChip(String key, String value, String toneClass) {
        String tone = toneClass == null ? "" : toneClass.trim();
        String cls = tone.isEmpty() ? "v" : "v " + tone;
        return "<div class='chip'><span class='k'>" + escapeHtml(safeText(key))
                + "</span><span class='" + escapeHtml(cls) + "'>"
                + escapeHtml(safeText(value)) + "</span></div>";
    }

    public static String hudCard(String key, String value, String toneClass) {
        String tone = toneClass == null ? "" : toneClass.trim();
        String cls = tone.isEmpty() ? "v" : "v " + tone;
        return "<div class='item'><span class='k'>" + escapeHtml(safeText(key))
                + "</span><span class='" + escapeHtml(cls) + "'>"
                + escapeHtml(safeText(value)) + "</span></div>";
    }

    public static String hudDetailRow(String left, String right) {
        return "<tr><td>" + escapeHtml(safeText(left)) + "</td><td>" + escapeHtml(safeText(right)) + "</td></tr>";
    }

    public static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static double clampDouble(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
