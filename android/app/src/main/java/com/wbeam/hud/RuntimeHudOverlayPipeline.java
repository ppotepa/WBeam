package com.wbeam.hud;

import android.view.View;
import android.webkit.WebView;
import android.widget.TextView;

public final class RuntimeHudOverlayPipeline {
    private static final String MODE_RUNTIME = "runtime";

    private RuntimeHudOverlayPipeline() {
    }

    @SuppressWarnings("java:S107")
    public static RuntimeHudOverlayRenderer.Rendered render(
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
            boolean tuningActive,
            String tuningLine,
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
        input.setDaemonReachable(daemonReachable);
        input.setSelectedProfile(selectedProfile);
        input.setSelectedEncoder(selectedEncoder);
        input.setStreamWidth(streamWidth);
        input.setStreamHeight(streamHeight);
        input.setDaemonHostName(daemonHostName);
        input.setDaemonStateUi(daemonStateUi);
        input.setDaemonBuildRevision(daemonBuildRevision);
        input.setAppBuildRevision(appBuildRevision);
        input.setDaemonLastError(daemonLastError);
        input.setTone(tone);
        input.setTargetFps(targetFps);
        input.setPresentFps(presentFps);
        input.setRecvFps(recvFps);
        input.setDecodeFps(decodeFps);
        input.setLiveMbps(liveMbps);
        input.setE2eP95(e2eP95);
        input.setDecodeP95(decodeP95);
        input.setRenderP95(renderP95);
        input.setFrametimeP95(frametimeP95);
        input.setDropsPerSec(dropsPerSec);
        input.setQT(qT);
        input.setQD(qD);
        input.setQR(qR);
        input.setQTMax(qTMax);
        input.setQDMax(qDMax);
        input.setQRMax(qRMax);
        input.setAdaptiveLevel(adaptiveLevel);
        input.setAdaptiveAction(adaptiveAction);
        input.setDrops(drops);
        input.setBpHigh(bpHigh);
        input.setBpRecover(bpRecover);
        input.setReason(reason);
        input.setTuningActive(tuningActive);
        input.setTuningLine(tuningLine);
        input.setMetricChartsHtml(metricChartsHtml);

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
                    MODE_RUNTIME,
                    rendered.webPayload,
                    hudOverlayState
            ) && perfHudText != null) {
                HudOverlayDisplay.showTextOnly(
                        perfHudWebView,
                        perfHudText,
                        MODE_RUNTIME,
                        rendered.textFallback,
                        fallbackTextColor,
                        hudOverlayState
                );
            }
        } else if (perfHudText != null) {
            HudOverlayDisplay.showTextOnly(
                    perfHudWebView,
                    perfHudText,
                    MODE_RUNTIME,
                    rendered.textFallback,
                    fallbackTextColor,
                    hudOverlayState
            );
        }
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.96f);
        }
        return rendered;
    }
}
