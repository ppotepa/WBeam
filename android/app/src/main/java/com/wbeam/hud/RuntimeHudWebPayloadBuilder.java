package com.wbeam.hud;

import java.util.Locale;

/**
 * Builds runtime HUD web payload (chips/cards/details/trend/html shell).
 */
public final class RuntimeHudWebPayloadBuilder {
    private static final String METRIC_FORMAT = "%.2f ms";

    private RuntimeHudWebPayloadBuilder() {}

    public static final class Input {
        private String selectedProfile;
        private String selectedEncoder;
        private int streamWidth;
        private int streamHeight;

        private String daemonHostName;
        private String daemonStateUi;
        private String daemonBuildRevision;
        private String appBuildRevision;
        private String daemonLastError;
        private String tone;

        private double targetFps;
        private double presentFps;
        private double recvFps;
        private double decodeFps;
        private double liveMbps;
        private double e2eP95;
        private double decodeP95;
        private double renderP95;
        private double frametimeP95;
        private double dropsPerSec;

        private int qT;
        private int qD;
        private int qR;
        private int qTMax;
        private int qDMax;
        private int qRMax;

        private int adaptiveLevel;
        private String adaptiveAction;
        private long drops;
        private long bpHigh;
        private long bpRecover;
        private String reason;
        private boolean tuningActive;
        private String tuningLine;

        private String metricChartsHtml;
        private String resourceRowsHtml;

        public String getSelectedProfile() {
            return selectedProfile;
        }

        public void setSelectedProfile(String selectedProfile) {
            this.selectedProfile = selectedProfile;
        }

        public String getSelectedEncoder() {
            return selectedEncoder;
        }

        public void setSelectedEncoder(String selectedEncoder) {
            this.selectedEncoder = selectedEncoder;
        }

        public int getStreamWidth() {
            return streamWidth;
        }

        public void setStreamWidth(int streamWidth) {
            this.streamWidth = streamWidth;
        }

        public int getStreamHeight() {
            return streamHeight;
        }

        public void setStreamHeight(int streamHeight) {
            this.streamHeight = streamHeight;
        }

        public String getDaemonHostName() {
            return daemonHostName;
        }

        public void setDaemonHostName(String daemonHostName) {
            this.daemonHostName = daemonHostName;
        }

        public String getDaemonStateUi() {
            return daemonStateUi;
        }

        public void setDaemonStateUi(String daemonStateUi) {
            this.daemonStateUi = daemonStateUi;
        }

        public String getDaemonBuildRevision() {
            return daemonBuildRevision;
        }

        public void setDaemonBuildRevision(String daemonBuildRevision) {
            this.daemonBuildRevision = daemonBuildRevision;
        }

        public String getAppBuildRevision() {
            return appBuildRevision;
        }

        public void setAppBuildRevision(String appBuildRevision) {
            this.appBuildRevision = appBuildRevision;
        }

        public String getDaemonLastError() {
            return daemonLastError;
        }

        public void setDaemonLastError(String daemonLastError) {
            this.daemonLastError = daemonLastError;
        }

        public String getTone() {
            return tone;
        }

        public void setTone(String tone) {
            this.tone = tone;
        }

        public double getTargetFps() {
            return targetFps;
        }

        public void setTargetFps(double targetFps) {
            this.targetFps = targetFps;
        }

        public double getPresentFps() {
            return presentFps;
        }

        public void setPresentFps(double presentFps) {
            this.presentFps = presentFps;
        }

        public double getRecvFps() {
            return recvFps;
        }

        public void setRecvFps(double recvFps) {
            this.recvFps = recvFps;
        }

        public double getDecodeFps() {
            return decodeFps;
        }

        public void setDecodeFps(double decodeFps) {
            this.decodeFps = decodeFps;
        }

        public double getLiveMbps() {
            return liveMbps;
        }

        public void setLiveMbps(double liveMbps) {
            this.liveMbps = liveMbps;
        }

        public double getE2eP95() {
            return e2eP95;
        }

        public void setE2eP95(double e2eP95) {
            this.e2eP95 = e2eP95;
        }

        public double getDecodeP95() {
            return decodeP95;
        }

        public void setDecodeP95(double decodeP95) {
            this.decodeP95 = decodeP95;
        }

        public double getRenderP95() {
            return renderP95;
        }

        public void setRenderP95(double renderP95) {
            this.renderP95 = renderP95;
        }

        public double getFrametimeP95() {
            return frametimeP95;
        }

        public void setFrametimeP95(double frametimeP95) {
            this.frametimeP95 = frametimeP95;
        }

        public double getDropsPerSec() {
            return dropsPerSec;
        }

        public void setDropsPerSec(double dropsPerSec) {
            this.dropsPerSec = dropsPerSec;
        }

        public int getQT() {
            return qT;
        }

        public void setQT(int qT) {
            this.qT = qT;
        }

        public int getQD() {
            return qD;
        }

        public void setQD(int qD) {
            this.qD = qD;
        }

        public int getQR() {
            return qR;
        }

        public void setQR(int qR) {
            this.qR = qR;
        }

        public int getQTMax() {
            return qTMax;
        }

        public void setQTMax(int qTMax) {
            this.qTMax = qTMax;
        }

        public int getQDMax() {
            return qDMax;
        }

        public void setQDMax(int qDMax) {
            this.qDMax = qDMax;
        }

        public int getQRMax() {
            return qRMax;
        }

        public void setQRMax(int qRMax) {
            this.qRMax = qRMax;
        }

        public int getAdaptiveLevel() {
            return adaptiveLevel;
        }

        public void setAdaptiveLevel(int adaptiveLevel) {
            this.adaptiveLevel = adaptiveLevel;
        }

        public String getAdaptiveAction() {
            return adaptiveAction;
        }

        public void setAdaptiveAction(String adaptiveAction) {
            this.adaptiveAction = adaptiveAction;
        }

        public long getDrops() {
            return drops;
        }

        public void setDrops(long drops) {
            this.drops = drops;
        }

        public long getBpHigh() {
            return bpHigh;
        }

        public void setBpHigh(long bpHigh) {
            this.bpHigh = bpHigh;
        }

        public long getBpRecover() {
            return bpRecover;
        }

        public void setBpRecover(long bpRecover) {
            this.bpRecover = bpRecover;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public boolean isTuningActive() {
            return tuningActive;
        }

        public void setTuningActive(boolean tuningActive) {
            this.tuningActive = tuningActive;
        }

        public String getTuningLine() {
            return tuningLine;
        }

        public void setTuningLine(String tuningLine) {
            this.tuningLine = tuningLine;
        }

        public String getMetricChartsHtml() {
            return metricChartsHtml;
        }

        public void setMetricChartsHtml(String metricChartsHtml) {
            this.metricChartsHtml = metricChartsHtml;
        }

        public String getResourceRowsHtml() {
            return resourceRowsHtml;
        }

        public void setResourceRowsHtml(String resourceRowsHtml) {
            this.resourceRowsHtml = resourceRowsHtml;
        }
    }

    public static String build(Input in) {
        String streamMode = String.format(
                Locale.US,
                "%s | %dx%d | %.0ffps",
                in.getSelectedEncoder().toUpperCase(Locale.US),
                in.getStreamWidth(),
                in.getStreamHeight(),
                in.getTargetFps()
        );
        String profileRev = HudRenderSupport.safeText(in.getDaemonBuildRevision()).equals("-")
                ? HudRenderSupport.safeText(in.getAppBuildRevision())
                : HudRenderSupport.safeText(in.getDaemonBuildRevision());

        StringBuilder chips = new StringBuilder();
        chips.append(HudRenderSupport.hudChip("CURRENT PROFILE", in.getSelectedProfile(), ""));
        chips.append(HudRenderSupport.hudChip("PROFILE REV", profileRev, ""));
        chips.append(HudRenderSupport.hudChip("STREAM MODE", streamMode, ""));
        chips.append(HudRenderSupport.hudChip("DEVICE", in.getDaemonHostName(), ""));
        chips.append(HudRenderSupport.hudChip("STATE", in.getDaemonStateUi(), HudRenderSupport.hudToneClass(in.getTone())));
        if (in.isTuningActive()) {
            chips.append(HudRenderSupport.hudChip("TUNING", "ACTIVE", "state-warn"));
        }

        StringBuilder cards = new StringBuilder();
        cards.append(HudRenderSupport.hudCard("PRESENT FPS", String.format(Locale.US, "%.1f", in.getPresentFps()), HudRenderSupport.hudToneClass(in.getTone())));
        cards.append(HudRenderSupport.hudCard("RECV FPS", String.format(Locale.US, "%.1f", in.getRecvFps()), ""));
        cards.append(HudRenderSupport.hudCard("DECODE FPS", String.format(Locale.US, "%.1f", in.getDecodeFps()), ""));
        cards.append(HudRenderSupport.hudCard("LIVE MBPS", HudRenderSupport.fmtDoubleOrPlaceholder(in.getLiveMbps(), "%.2f", "PENDING"), HudRenderSupport.hudToneClass(in.getTone())));
        cards.append(HudRenderSupport.hudCard("E2E p95", String.format(Locale.US, "%.1f ms", in.getE2eP95()), HudRenderSupport.hudToneClass(in.getTone())));
        cards.append(HudRenderSupport.hudCard("Decode p95", String.format(Locale.US, METRIC_FORMAT, in.getDecodeP95()), ""));
        cards.append(HudRenderSupport.hudCard("Render p95", String.format(Locale.US, METRIC_FORMAT, in.getRenderP95()), ""));
        cards.append(HudRenderSupport.hudCard("Frame p95", String.format(Locale.US, METRIC_FORMAT, in.getFrametimeP95()), ""));
        cards.append(HudRenderSupport.hudCard("Drops / s", HudRenderSupport.fmtDoubleOrPlaceholder(in.getDropsPerSec(), "%.3f", "PENDING"), HudRenderSupport.hudToneClass(in.getTone())));
        cards.append(HudRenderSupport.hudCard("Drops total", String.valueOf(in.getDrops()), ""));

        StringBuilder details = new StringBuilder();
        details.append(HudRenderSupport.hudDetailRow("Adaptive", "L" + in.getAdaptiveLevel() + " " + HudRenderSupport.safeText(in.getAdaptiveAction())));
        details.append(HudRenderSupport.hudDetailRow("Transport queue", in.getQT() + "/" + in.getQTMax()));
        details.append(HudRenderSupport.hudDetailRow("Decode queue", in.getQD() + "/" + in.getQDMax()));
        details.append(HudRenderSupport.hudDetailRow("Render queue", in.getQR() + "/" + in.getQRMax()));
        details.append(HudRenderSupport.hudDetailRow("Backpressure", in.getBpHigh() + "/" + in.getBpRecover()));
        details.append(HudRenderSupport.hudDetailRow("Connection mode", "runtime"));
        details.append(HudRenderSupport.hudDetailRow("Reason", HudRenderSupport.safeText(in.getReason())));
        if (in.isTuningActive()) {
            details.append(HudRenderSupport.hudDetailRow("Tuning", HudRenderSupport.safeText(in.getTuningLine())));
        }
        details.append(HudRenderSupport.hudDetailRow("Host error", HudRenderSupport.safeText(in.getDaemonLastError())));
        details.append(HudRenderSupport.hudDetailRow("App build", HudRenderSupport.safeText(in.getAppBuildRevision())));
        details.append(HudRenderSupport.hudDetailRow("Daemon build", HudRenderSupport.safeText(in.getDaemonBuildRevision())));

        String trend = "runtime health=" + in.getTone().toUpperCase(Locale.US)
                + " | recv=" + String.format(Locale.US, "%.1f", in.getRecvFps())
                + " decode=" + String.format(Locale.US, "%.1f", in.getDecodeFps())
                + " present=" + String.format(Locale.US, "%.1f", in.getPresentFps())
                + " | drops=" + in.getDrops()
                + (in.isTuningActive() ? " | tune=" + HudRenderSupport.safeText(in.getTuningLine()) : "");

        RuntimeHudShellRenderer.HtmlContent content = new RuntimeHudShellRenderer.HtmlContent();
        content.setChipsHtml(chips.toString());
        content.setCardsHtml(cards.toString());
        content.setChartsHtml(in.getMetricChartsHtml() == null ? "" : in.getMetricChartsHtml());
        content.setTrendText(trend);
        content.setDetailsRowsHtml(details.toString());
        content.setResourceRowsHtml(in.getResourceRowsHtml());
        content.setScaleClass("scale-1x");
        return RuntimeHudShellRenderer.buildHtml(content);
    }
}
