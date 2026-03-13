package com.wbeam.hud;

import org.json.JSONObject;

import java.util.Locale;

public final class TrainerHudOverlayRenderer {
    private static final String STATE_PENDING = "PENDING";
    private static final String STATE_PENDING_LOWER = "pending";

    public interface ResourceRowsProvider {
        String buildRows(double targetFps, double renderP95Ms);
    }

    public static final class Rendered {
        public final String html;
        public final String textFallback;
        public final String compactLine;

        private Rendered(String html, String textFallback, String compactLine) {
            this.html = html;
            this.textFallback = textFallback;
            this.compactLine = compactLine;
        }

        public static Rendered empty() {
            return new Rendered("", "", "");
        }
    }

    private TrainerHudOverlayRenderer() {}

    public static Rendered fromText(
            String rawHudText,
            double latestTargetFps,
            double fpsLowAnchor,
            ResourceRowsProvider resourceRowsProvider
    ) {
        String hudText = rawHudText == null ? "" : rawHudText.replace("\r", "");
        hudText = hudText.replace("[MAIN]\n", "").replace("[MAIN]", "").trim();
        if (hudText.isEmpty()) {
            return Rendered.empty();
        }

        String progressLine = TrainerProgressParser.buildProgressLine(hudText);
        int progressPercent = TrainerProgressParser.parseProgressPercent(hudText);
        String html = TrainerHudPayloadBuilder.buildFromText(
                hudText,
                progressLine,
                progressPercent,
                latestTargetFps,
                fpsLowAnchor,
                resourceRowsProvider::buildRows
        );
        String textFallback = progressLine.isEmpty() ? hudText : progressLine + "\n" + hudText;
        String compact = progressLine.isEmpty() ? "hud: trainer overlay active" : progressLine;
        return new Rendered(html, textFallback, compact);
    }

    public static Rendered fromJson(
            JSONObject hudJson,
            double latestTargetFps,
            double fpsLowAnchor,
            ResourceRowsProvider resourceRowsProvider
    ) {
        if (hudJson == null) {
            return Rendered.empty();
        }
        int progressPercent = hudJson.optInt("progress_percent", -1);
        String progressText = String.format(
                Locale.US,
                "TRAINING PROGRESS %d%%  (trial %d/%d)",
                Math.max(0, progressPercent),
                hudJson.optInt("trial_index", 0),
                hudJson.optInt("trial_total", 0)
        );
        String html = TrainerHudPayloadBuilder.buildFromJson(
                hudJson,
                progressText,
                progressPercent,
                latestTargetFps,
                fpsLowAnchor,
                resourceRowsProvider::buildRows
        );
        return new Rendered(html, progressText, progressText);
    }

    public static Rendered placeholder(
            double latestTargetFps,
            double fpsLowAnchor,
            ResourceRowsProvider resourceRowsProvider
    ) {
        String resourceRows = resourceRowsProvider.buildRows(
                latestTargetFps > 1.0 ? latestTargetFps : 60.0,
                0.0
        );
        String html = TrainerHudShellRenderer.buildSotHtml(
                STATE_PENDING,
                STATE_PENDING,
                STATE_PENDING,
                STATE_PENDING,
                0,
                0,
                0,
                0,
                0,
                "T0",
                0,
                "TRAINING PROGRESS ...",
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
                STATE_PENDING,
                Double.NaN,
                STATE_PENDING_LOWER,
                STATE_PENDING_LOWER,
                STATE_PENDING_LOWER,
                STATE_PENDING_LOWER,
                STATE_PENDING_LOWER,
                STATE_PENDING_LOWER,
                "trainer feed pending | placeholders visible",
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
        return new Rendered(
                html,
                "TRAINER HUD PENDING\nplaceholder layout active",
                "trainer hud pending placeholders"
        );
    }
}
