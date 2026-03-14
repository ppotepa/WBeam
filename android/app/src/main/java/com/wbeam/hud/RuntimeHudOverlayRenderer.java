package com.wbeam.hud;

public final class RuntimeHudOverlayRenderer {
    public interface ResourceRowsProvider {
        String buildRows(double targetFps, double renderP95Ms);
    }

    /**
     * Plain data carrier for overlay renderer input.
     */
    static final class Input {
        boolean daemonReachable;
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
    }

    public static final class Rendered {
        public final String html;
        public final String textFallback;

        private Rendered(String html, String textFallback) {
            this.html = html;
            this.textFallback = textFallback;
        }
    }

    private RuntimeHudOverlayRenderer() {}

    public static Rendered render(Input input, ResourceRowsProvider resourceRowsProvider) {
        RuntimeHudWebPayloadBuilder.Input payload = new RuntimeHudWebPayloadBuilder.Input();
        payload.selectedProfile = input.selectedProfile;
        payload.selectedEncoder = input.selectedEncoder;
        payload.streamWidth = input.streamWidth;
        payload.streamHeight = input.streamHeight;
        payload.daemonHostName = input.daemonHostName;
        payload.daemonStateUi = input.daemonStateUi;
        payload.daemonBuildRevision = input.daemonBuildRevision;
        payload.appBuildRevision = input.appBuildRevision;
        payload.daemonLastError = input.daemonLastError;
        payload.tone = input.tone;
        payload.targetFps = input.targetFps;
        payload.presentFps = input.presentFps;
        payload.recvFps = input.recvFps;
        payload.decodeFps = input.decodeFps;
        payload.liveMbps = input.liveMbps;
        payload.e2eP95 = input.e2eP95;
        payload.decodeP95 = input.decodeP95;
        payload.renderP95 = input.renderP95;
        payload.frametimeP95 = input.frametimeP95;
        payload.dropsPerSec = input.dropsPerSec;
        payload.qT = input.qT;
        payload.qD = input.qD;
        payload.qR = input.qR;
        payload.qTMax = input.qTMax;
        payload.qDMax = input.qDMax;
        payload.qRMax = input.qRMax;
        payload.adaptiveLevel = input.adaptiveLevel;
        payload.adaptiveAction = input.adaptiveAction;
        payload.drops = input.drops;
        payload.bpHigh = input.bpHigh;
        payload.bpRecover = input.bpRecover;
        payload.reason = input.reason;
        payload.metricChartsHtml = input.metricChartsHtml;
        payload.resourceRowsHtml = resourceRowsProvider.buildRows(input.targetFps, input.renderP95);

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
        return new Rendered(html, textFallback);
    }
}
