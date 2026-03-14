package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

import org.json.JSONObject;

public final class TrainerHudOverlayPipeline {
    private static final String TRAINER_CHANNEL = "trainer";

    public interface CompactLineSink {
        void setCompactLine(String compactLine);
    }

    public interface RefreshAction {
        void refresh();
    }

    private TrainerHudOverlayPipeline() {
    }

    static final class Context {
        double latestTargetFps;
        double fpsLowAnchor;
        ResourceUsageTracker resourceUsageTracker;
        WebView perfHudWebView;
        TextView perfHudText;
        View perfHudPanel;
        HudOverlayDisplay.State hudOverlayState;
        int fallbackTextColor;
        CompactLineSink compactLineSink;
        RefreshAction refreshAction;
    }

    @SuppressWarnings("java:S107")
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
        renderFromText(rawHudText, createContext(
                latestTargetFps,
                fpsLowAnchor,
                resourceUsageTracker,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                fallbackTextColor,
                compactLineSink,
                refreshAction
        ));
    }

    public static void renderFromText(String rawHudText, Context context) {
        if (context.perfHudText == null) {
            return;
        }
        TrainerHudOverlayRenderer.Rendered rendered = TrainerHudOverlayRenderer.fromText(
                rawHudText,
                context.latestTargetFps,
                context.fpsLowAnchor,
                trainerResourceRowsProvider(context.resourceUsageTracker)
        );
        if (rendered.html.isEmpty() && rendered.textFallback.isEmpty()) {
            return;
        }
        applyRendered(rendered, context);
    }

    @SuppressWarnings("java:S107")
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
        renderFromJson(hudJson, createContext(
                latestTargetFps,
                fpsLowAnchor,
                resourceUsageTracker,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                fallbackTextColor,
                compactLineSink,
                refreshAction
        ));
    }

    public static void renderFromJson(JSONObject hudJson, Context context) {
        if (context.perfHudText == null || hudJson == null) {
            return;
        }
        TrainerHudOverlayRenderer.Rendered rendered = TrainerHudOverlayRenderer.fromJson(
                hudJson,
                context.latestTargetFps,
                context.fpsLowAnchor,
                trainerResourceRowsProvider(context.resourceUsageTracker)
        );
        applyRendered(rendered, context);
    }

    @SuppressWarnings("java:S107")
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
        renderPlaceholder(createContext(
                latestTargetFps,
                fpsLowAnchor,
                resourceUsageTracker,
                perfHudWebView,
                perfHudText,
                perfHudPanel,
                hudOverlayState,
                fallbackTextColor,
                compactLineSink,
                refreshAction
        ));
    }

    public static void renderPlaceholder(Context context) {
        if (context.perfHudText == null) {
            return;
        }
        TrainerHudOverlayRenderer.Rendered rendered = TrainerHudOverlayRenderer.placeholder(
                context.latestTargetFps,
                context.fpsLowAnchor,
                trainerResourceRowsProvider(context.resourceUsageTracker)
        );
        applyRendered(rendered, context);
    }

    private static TrainerHudOverlayRenderer.ResourceRowsProvider trainerResourceRowsProvider(
            ResourceUsageTracker resourceUsageTracker
    ) {
        return (targetFps, renderP95Ms) -> {
            resourceUsageTracker.sample(targetFps, renderP95Ms);
            return resourceUsageTracker.buildRowsHtml();
        };
    }

    private static void applyRendered(TrainerHudOverlayRenderer.Rendered rendered, Context context) {
        if (context.perfHudWebView != null) {
            if (!HudOverlayDisplay.showWebHtml(
                    context.perfHudWebView,
                    context.perfHudText,
                    TRAINER_CHANNEL,
                    rendered.html,
                    context.hudOverlayState
            )) {
                HudOverlayDisplay.showTextOnly(
                        context.perfHudWebView,
                        context.perfHudText,
                        TRAINER_CHANNEL,
                        rendered.textFallback,
                        context.fallbackTextColor,
                        context.hudOverlayState
                );
            }
        } else {
            HudOverlayDisplay.showTextOnly(
                    context.perfHudWebView,
                    context.perfHudText,
                    TRAINER_CHANNEL,
                    rendered.textFallback,
                    context.fallbackTextColor,
                    context.hudOverlayState
            );
        }
        context.compactLineSink.setCompactLine(rendered.compactLine);
        context.refreshAction.refresh();
        if (context.perfHudPanel != null) {
            context.perfHudPanel.setAlpha(0.96f);
        }
    }

    private static Context createContext(
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
        Context context = new Context();
        context.latestTargetFps = latestTargetFps;
        context.fpsLowAnchor = fpsLowAnchor;
        context.resourceUsageTracker = resourceUsageTracker;
        context.perfHudWebView = perfHudWebView;
        context.perfHudText = perfHudText;
        context.perfHudPanel = perfHudPanel;
        context.hudOverlayState = hudOverlayState;
        context.fallbackTextColor = fallbackTextColor;
        context.compactLineSink = compactLineSink;
        context.refreshAction = refreshAction;
        return context;
    }
}
