package com.wbeam.hud;

public final class RuntimeHudOverlayRenderer {
    public interface ResourceRowsProvider {
        String buildRows(double targetFps, double renderP95Ms);
    }

    public static final class Input {
        public boolean daemonReachable;
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
                input.daemonReachable,
                input.targetFps,
                input.presentFps,
                input.frametimeP95,
                input.decodeP95,
                input.renderP95,
                input.e2eP95,
                input.qT,
                input.qD,
                input.qR,
                input.qTMax,
                input.qDMax,
                input.qRMax,
                input.adaptiveLevel,
                input.adaptiveAction,
                input.drops,
                input.bpHigh,
                input.bpRecover,
                input.reason
        );
        return Rendered.create(html, textFallback);
    }

    private static RuntimeHudWebPayloadBuilder.Input copyToPayload(Input input, ResourceRowsProvider resourceRowsProvider) {
        RuntimeHudWebPayloadBuilder.Input payload = new RuntimeHudWebPayloadBuilder.Input();
        payload.setSelectedProfile(input.selectedProfile);
        payload.setSelectedEncoder(input.selectedEncoder);
        payload.setStreamWidth(input.streamWidth);
        payload.setStreamHeight(input.streamHeight);
        payload.setDaemonHostName(input.daemonHostName);
        payload.setDaemonStateUi(input.daemonStateUi);
        payload.setDaemonBuildRevision(input.daemonBuildRevision);
        payload.setAppBuildRevision(input.appBuildRevision);
        payload.setDaemonLastError(input.daemonLastError);
        payload.setTone(input.tone);
        payload.setTargetFps(input.targetFps);
        payload.setPresentFps(input.presentFps);
        payload.setRecvFps(input.recvFps);
        payload.setDecodeFps(input.decodeFps);
        payload.setLiveMbps(input.liveMbps);
        payload.setE2eP95(input.e2eP95);
        payload.setDecodeP95(input.decodeP95);
        payload.setRenderP95(input.renderP95);
        payload.setFrametimeP95(input.frametimeP95);
        payload.setDropsPerSec(input.dropsPerSec);
        payload.setQT(input.qT);
        payload.setQD(input.qD);
        payload.setQR(input.qR);
        payload.setQTMax(input.qTMax);
        payload.setQDMax(input.qDMax);
        payload.setQRMax(input.qRMax);
        payload.setAdaptiveLevel(input.adaptiveLevel);
        payload.setAdaptiveAction(input.adaptiveAction);
        payload.setDrops(input.drops);
        payload.setBpHigh(input.bpHigh);
        payload.setBpRecover(input.bpRecover);
        payload.setReason(input.reason);
        payload.setTuningActive(input.tuningActive);
        payload.setTuningLine(input.tuningLine);
        payload.setMetricChartsHtml(input.metricChartsHtml);
        payload.setResourceRowsHtml(resourceRowsProvider.buildRows(input.targetFps, input.renderP95));
        return payload;
    }
}
