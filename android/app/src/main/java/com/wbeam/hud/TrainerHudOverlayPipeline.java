package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

import org.json.JSONObject;

public final class TrainerHudOverlayPipeline {
    public interface CompactLineSink {
        void setCompactLine(String compactLine);
    }

    public interface RefreshAction {
        void refresh();
    }

    private TrainerHudOverlayPipeline() {
    }

    public static void renderFromText(
            String rawHudText,
            double latestTargetFps,
            double fpsLowAnchor,
            ResourceUsageTracker resourceUsageTracker,
            WebView perfHudWebView,
            TextView perfHudText,
            View perfHudPanel,
            HudOverlayDisplay.State hudOverlayState,
            int fallbackTextColor,
            CompactLineSink compactLineSink,
            RefreshAction refreshAction
    ) {
        if (perfHudText == null) {
            return;
        }
        TrainerHudOverlayRenderer.Rendered rendered = TrainerHudOverlayRenderer.fromText(
                rawHudText,
                latestTargetFps,
                fpsLowAnchor,
                trainerResourceRowsProvider(resourceUsageTracker)
        );
        if (rendered.html.isEmpty() && rendered.textFallback.isEmpty()) {
            return;
        }
        applyRendered(
                rendered,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                fallbackTextColor,
                compactLineSink,
                refreshAction
        );
    }

    public static void renderFromJson(
            JSONObject hudJson,
            double latestTargetFps,
            double fpsLowAnchor,
            ResourceUsageTracker resourceUsageTracker,
            WebView perfHudWebView,
            TextView perfHudText,
            View perfHudPanel,
            HudOverlayDisplay.State hudOverlayState,
            int fallbackTextColor,
            CompactLineSink compactLineSink,
            RefreshAction refreshAction
    ) {
        if (perfHudText == null || hudJson == null) {
            return;
        }
        TrainerHudOverlayRenderer.Rendered rendered = TrainerHudOverlayRenderer.fromJson(
                hudJson,
                latestTargetFps,
                fpsLowAnchor,
                trainerResourceRowsProvider(resourceUsageTracker)
        );
        applyRendered(
                rendered,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                fallbackTextColor,
                compactLineSink,
                refreshAction
        );
    }

    public static void renderPlaceholder(
            double latestTargetFps,
            double fpsLowAnchor,
            ResourceUsageTracker resourceUsageTracker,
            WebView perfHudWebView,
            TextView perfHudText,
            View perfHudPanel,
            HudOverlayDisplay.State hudOverlayState,
            int fallbackTextColor,
            CompactLineSink compactLineSink,
            RefreshAction refreshAction
    ) {
        if (perfHudText == null) {
            return;
        }
        TrainerHudOverlayRenderer.Rendered rendered = TrainerHudOverlayRenderer.placeholder(
                latestTargetFps,
                fpsLowAnchor,
                trainerResourceRowsProvider(resourceUsageTracker)
        );
        applyRendered(
                rendered,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                fallbackTextColor,
                compactLineSink,
                refreshAction
        );
    }

    private static TrainerHudOverlayRenderer.ResourceRowsProvider trainerResourceRowsProvider(
            ResourceUsageTracker resourceUsageTracker
    ) {
        return (targetFps, renderP95Ms) -> {
            resourceUsageTracker.sample(targetFps, renderP95Ms);
            return resourceUsageTracker.buildRowsHtml();
        };
    }

    private static void applyRendered(
            TrainerHudOverlayRenderer.Rendered rendered,
            WebView perfHudWebView,
            TextView perfHudText,
            View perfHudPanel,
            HudOverlayDisplay.State hudOverlayState,
            int fallbackTextColor,
            CompactLineSink compactLineSink,
            RefreshAction refreshAction
    ) {
        if (perfHudWebView != null) {
            if (!HudOverlayDisplay.showWebHtml(
                    perfHudWebView,
                    perfHudText,
                    "trainer",
                    rendered.html,
                    hudOverlayState
            )) {
                HudOverlayDisplay.showTextOnly(
                        perfHudWebView,
                        perfHudText,
                        "trainer",
                        rendered.textFallback,
                        fallbackTextColor,
                        hudOverlayState
                );
            }
        } else {
            HudOverlayDisplay.showTextOnly(
                    perfHudWebView,
                    perfHudText,
                    "trainer",
                    rendered.textFallback,
                    fallbackTextColor,
                    hudOverlayState
            );
        }
        compactLineSink.setCompactLine(rendered.compactLine);
        refreshAction.refresh();
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
    }
}
