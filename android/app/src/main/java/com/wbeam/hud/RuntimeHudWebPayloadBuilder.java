package com.wbeam.hud;

import java.util.Locale;

/**
 * Builds runtime HUD web payload (chips/cards/details/trend/html shell).
 */
public final class RuntimeHudWebPayloadBuilder {
    private static final String FMT_MS_P95 = "%.1f ms";

    private RuntimeHudWebPayloadBuilder() {}

    /**
     * Plain data carrier for HUD web payload builder input.
     */
    static final class Input {
        String selectedProfile;
        String selectedEncoder;
        int streamWidth;
        int streamHeight;

        String daemonHostName;
        String daemonStateUi;
        String daemonBuildRevision;
        String appBuildRevision;
        String daemonLastError;
        String tone;

        double targetFps;
        double presentFps;
        double recvFps;
        double decodeFps;
        double liveMbps;
        double e2eP95;
        double decodeP95;
        double renderP95;
        double frametimeP95;
        double dropsPerSec;

        int qT;
        int qD;
        int qR;
        int qTMax;
        int qDMax;
        int qRMax;

        int adaptiveLevel;
        String adaptiveAction;
        long drops;
        long bpHigh;
        long bpRecover;
        String reason;

        String metricChartsHtml;
        String resourceRowsHtml;
    }

    public static String build(Input in) {
        String streamMode = String.format(
                Locale.US,
                "%s | %dx%d | %.0ffps",
                in.selectedEncoder.toUpperCase(Locale.US),
                in.streamWidth,
                in.streamHeight,
                in.targetFps
        );
        String profileRev = HudRenderSupport.safeText(in.daemonBuildRevision).equals("-")
                ? HudRenderSupport.safeText(in.appBuildRevision)
                : HudRenderSupport.safeText(in.daemonBuildRevision);

        StringBuilder chips = new StringBuilder();
        chips.append(HudRenderSupport.hudChip("CURRENT PROFILE", in.selectedProfile, ""));
        chips.append(HudRenderSupport.hudChip("PROFILE REV", profileRev, ""));
        chips.append(HudRenderSupport.hudChip("STREAM MODE", streamMode, ""));
        chips.append(HudRenderSupport.hudChip("DEVICE", in.daemonHostName, ""));
        chips.append(HudRenderSupport.hudChip("STATE", in.daemonStateUi, HudRenderSupport.hudToneClass(in.tone)));

        StringBuilder cards = new StringBuilder();
        cards.append(HudRenderSupport.hudCard("PRESENT FPS", String.format(Locale.US, "%.1f", in.presentFps), HudRenderSupport.hudToneClass(in.tone)));
        cards.append(HudRenderSupport.hudCard("RECV FPS", String.format(Locale.US, "%.1f", in.recvFps), ""));
        cards.append(HudRenderSupport.hudCard("DECODE FPS", String.format(Locale.US, "%.1f", in.decodeFps), ""));
        cards.append(HudRenderSupport.hudCard("LIVE MBPS", HudRenderSupport.fmtDoubleOrPlaceholder(in.liveMbps, "%.2f", "PENDING"), HudRenderSupport.hudToneClass(in.tone)));
        cards.append(HudRenderSupport.hudCard("E2E p95", String.format(Locale.US, FMT_MS_P95, in.e2eP95), HudRenderSupport.hudToneClass(in.tone)));
        cards.append(HudRenderSupport.hudCard("Decode p95", String.format(Locale.US, FMT_MS_P95, in.decodeP95), ""));
        cards.append(HudRenderSupport.hudCard("Render p95", String.format(Locale.US, FMT_MS_P95, in.renderP95), ""));
        cards.append(HudRenderSupport.hudCard("Frame p95", String.format(Locale.US, FMT_MS_P95, in.frametimeP95), ""));
        cards.append(HudRenderSupport.hudCard("Drops / s", HudRenderSupport.fmtDoubleOrPlaceholder(in.dropsPerSec, "%.3f", "PENDING"), HudRenderSupport.hudToneClass(in.tone)));
        cards.append(HudRenderSupport.hudCard("Drops total", String.valueOf(in.drops), ""));

        StringBuilder details = new StringBuilder();
        details.append(HudRenderSupport.hudDetailRow("Adaptive", "L" + in.adaptiveLevel + " " + HudRenderSupport.safeText(in.adaptiveAction)));
        details.append(HudRenderSupport.hudDetailRow("Transport queue", in.qT + "/" + in.qTMax));
        details.append(HudRenderSupport.hudDetailRow("Decode queue", in.qD + "/" + in.qDMax));
        details.append(HudRenderSupport.hudDetailRow("Render queue", in.qR + "/" + in.qRMax));
        details.append(HudRenderSupport.hudDetailRow("Backpressure", in.bpHigh + "/" + in.bpRecover));
        details.append(HudRenderSupport.hudDetailRow("Connection mode", "runtime"));
        details.append(HudRenderSupport.hudDetailRow("Reason", HudRenderSupport.safeText(in.reason)));
        details.append(HudRenderSupport.hudDetailRow("Host error", HudRenderSupport.safeText(in.daemonLastError)));
        details.append(HudRenderSupport.hudDetailRow("App build", HudRenderSupport.safeText(in.appBuildRevision)));
        details.append(HudRenderSupport.hudDetailRow("Daemon build", HudRenderSupport.safeText(in.daemonBuildRevision)));

        String trend = "runtime health=" + in.tone.toUpperCase(Locale.US)
                + " | recv=" + String.format(Locale.US, "%.1f", in.recvFps)
                + " decode=" + String.format(Locale.US, "%.1f", in.decodeFps)
                + " present=" + String.format(Locale.US, "%.1f", in.presentFps)
                + " | drops=" + in.drops;

        return RuntimeHudShellRenderer.buildHtml(
                chips.toString(),
                cards.toString(),
                in.metricChartsHtml == null ? "" : in.metricChartsHtml,
                trend,
                details.toString(),
                in.resourceRowsHtml,
                "scale-1x"
        );
    }
}
