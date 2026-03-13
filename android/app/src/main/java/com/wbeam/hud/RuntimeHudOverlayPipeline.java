package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

public final class RuntimeHudOverlayPipeline {
    private static final String RUNTIME = "runtime";

    private RuntimeHudOverlayPipeline() {
    }

    @SuppressWarnings("java:S107")
    public static void render(
            boolean daemonReachable,
            String selectedProfile,
            String selectedEncoder,
            int streamWidth,
            int streamHeight,
            String daemonHostName,
            String daemonStateUi,
            String daemonBuildRevision,
            String appBuildRevision,
            String daemonLastError,
            double targetFps,
            double presentFps,
            double recvFps,
            double decodeFps,
            double e2eP95,
            double decodeP95,
            double renderP95,
            double frametimeP95,
            double liveMbps,
            double dropsPerSec,
            int qT,
            int qD,
            int qR,
            int qTMax,
            int qDMax,
            int qRMax,
            int adaptiveLevel,
            String adaptiveAction,
            long drops,
            long bpHigh,
            long bpRecover,
            String reason,
            String metricChartsHtml,
            String tone,
            ResourceUsageTracker resourceUsageTracker,
            WebView perfHudWebView,
            TextView perfHudText,
            View perfHudPanel,
            HudOverlayDisplay.State hudOverlayState,
            int fallbackTextColor
    ) {
        RuntimeHudOverlayRenderer.Input input = new RuntimeHudOverlayRenderer.Input();
        input.daemonReachable = daemonReachable;
        input.selectedProfile = selectedProfile;
        input.selectedEncoder = selectedEncoder;
        input.streamWidth = streamWidth;
        input.streamHeight = streamHeight;
        input.daemonHostName = daemonHostName;
        input.daemonStateUi = daemonStateUi;
        input.daemonBuildRevision = daemonBuildRevision;
        input.appBuildRevision = appBuildRevision;
        input.daemonLastError = daemonLastError;
        input.tone = tone;
        input.targetFps = targetFps;
        input.presentFps = presentFps;
        input.recvFps = recvFps;
        input.decodeFps = decodeFps;
        input.liveMbps = liveMbps;
        input.e2eP95 = e2eP95;
        input.decodeP95 = decodeP95;
        input.renderP95 = renderP95;
        input.frametimeP95 = frametimeP95;
        input.dropsPerSec = dropsPerSec;
        input.qT = qT;
        input.qD = qD;
        input.qR = qR;
        input.qTMax = qTMax;
        input.qDMax = qDMax;
        input.qRMax = qRMax;
        input.adaptiveLevel = adaptiveLevel;
        input.adaptiveAction = adaptiveAction;
        input.drops = drops;
        input.bpHigh = bpHigh;
        input.bpRecover = bpRecover;
        input.reason = reason;
        input.metricChartsHtml = metricChartsHtml;

        RuntimeHudOverlayRenderer.Rendered rendered = RuntimeHudOverlayRenderer.render(
                input,
                (target, render) -> {
                    resourceUsageTracker.sample(target, render);
                    return resourceUsageTracker.buildRowsHtml();
                }
        );

        if (perfHudWebView != null) {
            if (!HudOverlayDisplay.showWebHtml(
                    perfHudWebView,
                    perfHudText,
                    RUNTIME,
                    rendered.html,
                    hudOverlayState
            ) && perfHudText != null) {
                HudOverlayDisplay.showTextOnly(
                        perfHudWebView,
                        perfHudText,
                        RUNTIME,
                        rendered.textFallback,
                        fallbackTextColor,
                        hudOverlayState
                );
            }
        } else if (perfHudText != null) {
            HudOverlayDisplay.showTextOnly(
                    perfHudWebView,
                    perfHudText,
                    RUNTIME,
                    rendered.textFallback,
                    fallbackTextColor,
                    hudOverlayState
            );
        }
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
    }
}
