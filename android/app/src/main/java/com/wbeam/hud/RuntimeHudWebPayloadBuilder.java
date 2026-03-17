package com.wbeam.hud;

import java.util.Locale;

/**
 * Builds runtime HUD web payload (chips/cards/details/trend/html shell).
 */
public final class RuntimeHudWebPayloadBuilder {
    private RuntimeHudWebPayloadBuilder() {}

    public static final class Input {
        public String selectedProfile;
        public String selectedEncoder;
        public int streamWidth;
        public int streamHeight;

        public String daemonHostName;
        public String daemonStateUi;
        public String daemonBuildRevision;
        public String appBuildRevision;
        public String daemonLastError;
        public String tone;

        public double targetFps;
        public double presentFps;
        public double recvFps;
        public double decodeFps;
        public double liveMbps;
        public double e2eP95;
        public double decodeP95;
        public double renderP95;
        public double frametimeP95;
        public double dropsPerSec;

        public int qT;
        public int qD;
        public int qR;
        public int qTMax;
        public int qDMax;
        public int qRMax;

        public int adaptiveLevel;
        public String adaptiveAction;
        public long drops;
        public long bpHigh;
        public long bpRecover;
        public String reason;
        public boolean tuningActive;
        public String tuningLine;

        public String metricChartsHtml;
        public String resourceRowsHtml;
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
        if (in.tuningActive) {
            chips.append(HudRenderSupport.hudChip("TUNING", "ACTIVE", "state-warn"));
        }

        StringBuilder cards = new StringBuilder();
        cards.append(HudRenderSupport.hudCard("PRESENT FPS", String.format(Locale.US, "%.1f", in.presentFps), HudRenderSupport.hudToneClass(in.tone)));
        cards.append(HudRenderSupport.hudCard("RECV FPS", String.format(Locale.US, "%.1f", in.recvFps), ""));
        cards.append(HudRenderSupport.hudCard("DECODE FPS", String.format(Locale.US, "%.1f", in.decodeFps), ""));
        cards.append(HudRenderSupport.hudCard("LIVE MBPS", HudRenderSupport.fmtDoubleOrPlaceholder(in.liveMbps, "%.2f", "PENDING"), HudRenderSupport.hudToneClass(in.tone)));
        cards.append(HudRenderSupport.hudCard("E2E p95", String.format(Locale.US, "%.1f ms", in.e2eP95), HudRenderSupport.hudToneClass(in.tone)));
        cards.append(HudRenderSupport.hudCard("Decode p95", String.format(Locale.US, "%.2f ms", in.decodeP95), ""));
        cards.append(HudRenderSupport.hudCard("Render p95", String.format(Locale.US, "%.2f ms", in.renderP95), ""));
        cards.append(HudRenderSupport.hudCard("Frame p95", String.format(Locale.US, "%.2f ms", in.frametimeP95), ""));
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
        if (in.tuningActive) {
            details.append(HudRenderSupport.hudDetailRow("Tuning", HudRenderSupport.safeText(in.tuningLine)));
        }
        details.append(HudRenderSupport.hudDetailRow("Host error", HudRenderSupport.safeText(in.daemonLastError)));
        details.append(HudRenderSupport.hudDetailRow("App build", HudRenderSupport.safeText(in.appBuildRevision)));
        details.append(HudRenderSupport.hudDetailRow("Daemon build", HudRenderSupport.safeText(in.daemonBuildRevision)));

        String trend = "runtime health=" + in.tone.toUpperCase(Locale.US)
                + " | recv=" + String.format(Locale.US, "%.1f", in.recvFps)
                + " decode=" + String.format(Locale.US, "%.1f", in.decodeFps)
                + " present=" + String.format(Locale.US, "%.1f", in.presentFps)
                + " | drops=" + in.drops
                + (in.tuningActive ? " | tune=" + HudRenderSupport.safeText(in.tuningLine) : "");

        RuntimeHudShellRenderer.HtmlContent content = new RuntimeHudShellRenderer.HtmlContent();
        content.chipsHtml = chips.toString();
        content.cardsHtml = cards.toString();
        content.chartsHtml = in.metricChartsHtml == null ? "" : in.metricChartsHtml;
        content.trendText = trend;
        content.detailsRowsHtml = details.toString();
        content.resourceRowsHtml = in.resourceRowsHtml;
        content.scaleClass = "scale-1x";
        return RuntimeHudShellRenderer.buildHtml(content);
    }
}
