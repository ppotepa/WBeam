package com.wbeam.hud;

public final class RuntimeHudOverlayRenderer {
    public interface ResourceRowsProvider {
        String buildRows(double targetFps, double renderP95Ms);
    }

    public static final class Input {
        private boolean daemonReachable;
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

        public boolean isDaemonReachable() {
            return daemonReachable;
        }

        public void setDaemonReachable(boolean daemonReachable) {
            this.daemonReachable = daemonReachable;
        }

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
    }

    public static final class Rendered {
        public final String html;
        public final String textFallback;

        private Rendered(String html, String textFallback) {
            this.html = html;
            this.textFallback = textFallback;
        }

        public static Rendered create(String html, String textFallback) {
            return new Rendered(html, textFallback);
        }
    }

    private RuntimeHudOverlayRenderer() {}

    public static Rendered render(Input input, ResourceRowsProvider resourceRowsProvider) {
        RuntimeHudWebPayloadBuilder.Input payload = copyToPayload(input, resourceRowsProvider);
        String html = RuntimeHudWebPayloadBuilder.build(payload);
        String textFallback = RuntimeHudFallbackFormatter.buildText(
                input.isDaemonReachable(),
                input.getTargetFps(),
                input.getPresentFps(),
                input.getFrametimeP95(),
                input.getDecodeP95(),
                input.getRenderP95(),
                input.getE2eP95(),
                input.getQT(),
                input.getQD(),
                input.getQR(),
                input.getQTMax(),
                input.getQDMax(),
                input.getQRMax(),
                input.getAdaptiveLevel(),
                input.getAdaptiveAction(),
                input.getDrops(),
                input.getBpHigh(),
                input.getBpRecover(),
                input.getReason()
        );
        return Rendered.create(html, textFallback);
    }

    private static RuntimeHudWebPayloadBuilder.Input copyToPayload(Input input, ResourceRowsProvider resourceRowsProvider) {
        RuntimeHudWebPayloadBuilder.Input payload = new RuntimeHudWebPayloadBuilder.Input();
        payload.setSelectedProfile(input.getSelectedProfile());
        payload.setSelectedEncoder(input.getSelectedEncoder());
        payload.setStreamWidth(input.getStreamWidth());
        payload.setStreamHeight(input.getStreamHeight());
        payload.setDaemonHostName(input.getDaemonHostName());
        payload.setDaemonStateUi(input.getDaemonStateUi());
        payload.setDaemonBuildRevision(input.getDaemonBuildRevision());
        payload.setAppBuildRevision(input.getAppBuildRevision());
        payload.setDaemonLastError(input.getDaemonLastError());
        payload.setTone(input.getTone());
        payload.setTargetFps(input.getTargetFps());
        payload.setPresentFps(input.getPresentFps());
        payload.setRecvFps(input.getRecvFps());
        payload.setDecodeFps(input.getDecodeFps());
        payload.setLiveMbps(input.getLiveMbps());
        payload.setE2eP95(input.getE2eP95());
        payload.setDecodeP95(input.getDecodeP95());
        payload.setRenderP95(input.getRenderP95());
        payload.setFrametimeP95(input.getFrametimeP95());
        payload.setDropsPerSec(input.getDropsPerSec());
        payload.setQT(input.getQT());
        payload.setQD(input.getQD());
        payload.setQR(input.getQR());
        payload.setQTMax(input.getQTMax());
        payload.setQDMax(input.getQDMax());
        payload.setQRMax(input.getQRMax());
        payload.setAdaptiveLevel(input.getAdaptiveLevel());
        payload.setAdaptiveAction(input.getAdaptiveAction());
        payload.setDrops(input.getDrops());
        payload.setBpHigh(input.getBpHigh());
        payload.setBpRecover(input.getBpRecover());
        payload.setReason(input.getReason());
        payload.setTuningActive(input.isTuningActive());
        payload.setTuningLine(input.getTuningLine());
        payload.setMetricChartsHtml(input.getMetricChartsHtml());
        payload.setResourceRowsHtml(resourceRowsProvider.buildRows(input.getTargetFps(), input.getRenderP95()));
        return payload;
    }
}
