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
        payload.tuningActive = input.tuningActive;
        payload.tuningLine = input.tuningLine;
        payload.metricChartsHtml = input.metricChartsHtml;
        payload.resourceRowsHtml = resourceRowsProvider.buildRows(input.targetFps, input.renderP95);
        return payload;
    }
}
