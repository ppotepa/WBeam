package com.wbeam.startup;

import java.util.Locale;

/**
 * Builds preflight/startup overlay model from current runtime snapshot.
 */
public final class StartupOverlayModelBuilder {
    private static final String SERVICE_PREFIX = "service=";
    private static final String BULLET = " \u00b7 ";

    private StartupOverlayModelBuilder() {}

    /**
     * Plain data carrier for startup overlay input.
     */
    public static final class Input {
        private boolean daemonReachable;
        private String daemonHostName;
        private String daemonService;
        private String daemonBuildRevision;
        private String daemonState;
        private String daemonLastError;
        private boolean handshakeResolved;
        private boolean buildMismatch;
        private boolean requiresTransportProbe;
        private boolean probeOk;
        private boolean probeInFlight;
        private String probeInfo;
        private String apiImpl;
        private String apiBase;
        private String streamHost;
        private int streamPort;
        private String appBuildRevision;
        private String lastUiInfo;
        private String effectiveDaemonState;
        private double latestPresentFps;
        private long startupBeganAtMs;
        private int controlRetryCount;
        private long nowMs;
        private String lastStatsLine;
        private String daemonErrCompact;

        Input setDaemonReachable(boolean daemonReachable) {
            this.daemonReachable = daemonReachable;
            return this;
        }

        Input setDaemonHostName(String daemonHostName) {
            this.daemonHostName = daemonHostName;
            return this;
        }

        Input setDaemonService(String daemonService) {
            this.daemonService = daemonService;
            return this;
        }

        Input setDaemonBuildRevision(String daemonBuildRevision) {
            this.daemonBuildRevision = daemonBuildRevision;
            return this;
        }

        Input setDaemonState(String daemonState) {
            this.daemonState = daemonState;
            return this;
        }

        Input setDaemonLastError(String daemonLastError) {
            this.daemonLastError = daemonLastError;
            return this;
        }

        Input setHandshakeResolved(boolean handshakeResolved) {
            this.handshakeResolved = handshakeResolved;
            return this;
        }

        Input setBuildMismatch(boolean buildMismatch) {
            this.buildMismatch = buildMismatch;
            return this;
        }

        Input setRequiresTransportProbe(boolean requiresTransportProbe) {
            this.requiresTransportProbe = requiresTransportProbe;
            return this;
        }

        Input setProbeOk(boolean probeOk) {
            this.probeOk = probeOk;
            return this;
        }

        Input setProbeInFlight(boolean probeInFlight) {
            this.probeInFlight = probeInFlight;
            return this;
        }

        Input setProbeInfo(String probeInfo) {
            this.probeInfo = probeInfo;
            return this;
        }

        Input setApiImpl(String apiImpl) {
            this.apiImpl = apiImpl;
            return this;
        }

        Input setApiBase(String apiBase) {
            this.apiBase = apiBase;
            return this;
        }

        Input setApiHost(String ignoredApiHost) {
            return this;
        }

        Input setStreamHost(String streamHost) {
            this.streamHost = streamHost;
            return this;
        }

        Input setStreamPort(int streamPort) {
            this.streamPort = streamPort;
            return this;
        }

        Input setAppBuildRevision(String appBuildRevision) {
            this.appBuildRevision = appBuildRevision;
            return this;
        }

        Input setLastUiInfo(String lastUiInfo) {
            this.lastUiInfo = lastUiInfo;
            return this;
        }

        Input setEffectiveDaemonState(String effectiveDaemonState) {
            this.effectiveDaemonState = effectiveDaemonState;
            return this;
        }

        Input setLatestPresentFps(double latestPresentFps) {
            this.latestPresentFps = latestPresentFps;
            return this;
        }

        Input setStartupBeganAtMs(long startupBeganAtMs) {
            this.startupBeganAtMs = startupBeganAtMs;
            return this;
        }

        Input setControlRetryCount(int controlRetryCount) {
            this.controlRetryCount = controlRetryCount;
            return this;
        }

        Input setNowMs(long nowMs) {
            this.nowMs = nowMs;
            return this;
        }

        Input setLastStatsLine(String lastStatsLine) {
            this.lastStatsLine = lastStatsLine;
            return this;
        }

        Input setDaemonErrCompact(String daemonErrCompact) {
            this.daemonErrCompact = daemonErrCompact;
            return this;
        }
    }

    public static final class Model {
        public static final int SS_PENDING = 0;
        public static final int SS_ACTIVE = 1;
        public static final int SS_OK = 2;
        public static final int SS_ERROR = 3;

        /**
         * Plain data carrier for startup overlay output model.
         */
        private int step1State;
        private int step2State;
        private int step3State;
        private String step1Detail;
        private String step2Detail;
        private String step3Detail;
        private String subtitle;
        private String infoLog;
        private boolean allOk;
        private long updatedStartupBeganAtMs;
        private int updatedControlRetryCount;
        private long elapsedMs;

        public int getStep1State() {
            return step1State;
        }

        public int getStep2State() {
            return step2State;
        }

        public int getStep3State() {
            return step3State;
        }

        public String getStep1Detail() {
            return step1Detail;
        }

        public String getStep2Detail() {
            return step2Detail;
        }

        public String getStep3Detail() {
            return step3Detail;
        }

        public String getSubtitle() {
            return subtitle;
        }

        public String getInfoLog() {
            return infoLog;
        }

        public boolean isAllOk() {
            return allOk;
        }

        public long getUpdatedStartupBeganAtMs() {
            return updatedStartupBeganAtMs;
        }

        public int getUpdatedControlRetryCount() {
            return updatedControlRetryCount;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }
    }

    private static final String ATTEMPT_PREFIX = "attempt #";
    private static final String RECONNECTS_SUFFIX = "reconnects: ";

    public static Model build(Input in) {
        Model out = new Model();
        StartupProgress progress = updateStartupProgress(in);
        applyProgress(out, progress);

        StreamDiagnostics diagnostics = analyzeStream(in);

        StepInfo step1Info = determineStep1(in, progress.elapsedMs, progress.updatedControlRetryCount);
        StepInfo step2Info = determineStep2(in, step1Info.state);
        StepInfo step3Info = determineStep3(in, step2Info.state, diagnostics, progress.elapsedMs);
        String subtitle = buildSubtitle(
                step1Info,
                step2Info,
                step3Info,
                progress,
                diagnostics,
                in.buildMismatch
        );

        applyStepResults(out, step1Info, step2Info, step3Info, subtitle, buildInfoLog(in));
        return out;
    }

    private static StartupProgress updateStartupProgress(Input in) {
        long elapsedMs = in.startupBeganAtMs > 0L
                ? Math.max(0L, in.nowMs - in.startupBeganAtMs)
                : 0L;
        long updatedStartupBeganAtMs = in.startupBeganAtMs;
        int updatedControlRetryCount = in.controlRetryCount;

        if (!in.daemonReachable && elapsedMs > 20_000L) {
            updatedControlRetryCount++;
            updatedStartupBeganAtMs = in.nowMs;
            elapsedMs = 0L;
        }
        if (in.daemonReachable && updatedControlRetryCount > 0) {
            updatedControlRetryCount = 0;
        }

        return new StartupProgress(elapsedMs, updatedStartupBeganAtMs, updatedControlRetryCount);
    }

    private static void applyProgress(Model out, StartupProgress progress) {
        out.updatedStartupBeganAtMs = progress.updatedStartupBeganAtMs;
        out.updatedControlRetryCount = progress.updatedControlRetryCount;
        out.elapsedMs = progress.elapsedMs;
    }

    private static StreamDiagnostics analyzeStream(Input in) {
        boolean streamFlowing = "STREAMING".equalsIgnoreCase(safe(in.effectiveDaemonState));
        int streamReconnects = parseReconnectCount(in.lastStatsLine);
        String streamAddr = streamAddress(in.streamHost);
        boolean daemonStartFailure = safe(in.daemonErrCompact)
                .toLowerCase(Locale.US)
                .contains("stream start aborted");
        boolean streamIsLoopback = isLoopbackHost(in.streamHost);
        String streamFixHint = streamIsLoopback
                ? "check ADB reverse for stream/control ports" + BULLET + "ensure desktop service is running"
                : "check USB tethering / host IP / LAN" + BULLET + "ensure desktop service is running";
        return new StreamDiagnostics(
                streamFlowing,
                streamReconnects,
                streamAddr,
                daemonStartFailure,
                streamFixHint
        );
    }

    private static String buildInfoLog(Input in) {
        StringBuilder info = new StringBuilder();
        info.append("api=").append(safe(in.apiBase))
                .append("  impl=").append(safe(in.apiImpl)).append('\n')
                .append("stream=").append(safe(in.streamHost)).append(':')
                .append(in.streamPort).append('\n')
                .append("app=").append(safe(in.appBuildRevision))
                .append("  host=").append(safe(in.daemonBuildRevision)).append('\n')
                .append("state=").append(safe(in.daemonState));
        appendInfoDetail(info, "error=", in.daemonLastError);
        if (safe(in.daemonLastError).isEmpty()) {
            appendInfoDetail(info, "hint=", in.lastUiInfo);
        }
        return info.toString();
    }

    private static void appendInfoDetail(StringBuilder info, String prefix, String value) {
        String safeValue = safe(value);
        if (!safeValue.isEmpty()) {
            info.append('\n').append(prefix).append(safeValue);
        }
    }

    private static void applyStepResults(
            Model out,
            StepInfo step1Info,
            StepInfo step2Info,
            StepInfo step3Info,
            String subtitle,
            String infoLog
    ) {
        out.step1State = step1Info.state;
        out.step1Detail = step1Info.detail;
        out.step2State = step2Info.state;
        out.step2Detail = step2Info.detail;
        out.step3State = step3Info.state;
        out.step3Detail = step3Info.detail;
        out.subtitle = subtitle;
        out.infoLog = infoLog;
        out.allOk = step1Info.state == Model.SS_OK
                && step2Info.state == Model.SS_OK
                && step3Info.state == Model.SS_OK;
    }

    private static StepInfo determineStep1(Input in, long elapsedMs, int updatedControlRetryCount) {
        if (in.daemonReachable) {
            if ("local".equalsIgnoreCase(in.apiImpl)) {
                return new StepInfo(Model.SS_OK, "on-device (local api) \u00b7 no host connection needed");
            }
            return new StepInfo(Model.SS_OK,
                    "reachable" + BULLET + "api_impl=" + safe(in.apiImpl)
                            + BULLET + safe(in.daemonHostName));
        }
        if (updatedControlRetryCount == 0) {
            return new StepInfo(Model.SS_ACTIVE,
                    "polling " + safe(in.apiBase)
                            + " \u2026 (" + (elapsedMs / 1000L) + "s)"
                            + BULLET + "install/start desktop service if this does not recover");
        }
        return new StepInfo(Model.SS_ACTIVE,
                "no response" + BULLET + "retry #" + updatedControlRetryCount
                        + BULLET + "polling " + safe(in.apiBase)
                        + " (" + (elapsedMs / 1000L) + "s)"
                        + BULLET + "check desktop service status");
    }

    private static StepInfo determineStep2(Input in, int step1State) {
        if (step1State != Model.SS_OK) {
            return new StepInfo(Model.SS_PENDING, "waiting for control link");
        }
        if (!in.handshakeResolved) {
            return new StepInfo(Model.SS_ACTIVE, "resolving service / api version\u2026");
        }
        if (in.buildMismatch) {
            return new StepInfo(Model.SS_ERROR,
                    "build mismatch · app=" + safe(in.appBuildRevision)
                            + " · host=" + safe(in.daemonBuildRevision));
        }
        if (in.requiresTransportProbe) {
            if (in.probeOk) {
                return new StepInfo(Model.SS_OK,
                        SERVICE_PREFIX + safe(in.daemonService) + BULLET + "transport test OK");
            }
            if (in.probeInFlight) {
                return new StepInfo(Model.SS_OK,
                        SERVICE_PREFIX + safe(in.daemonService) + BULLET + "transport test in progress\u2026");
            }
            return new StepInfo(Model.SS_OK,
                    SERVICE_PREFIX + safe(in.daemonService) + BULLET + "transport test pending");
        }
        return new StepInfo(Model.SS_OK,
                SERVICE_PREFIX + safe(in.daemonService) + BULLET + safe(in.apiImpl));
    }

    private static StepInfo determineStep3(
            Input in,
            int step2State,
            StreamDiagnostics diagnostics,
            long elapsedMs
    ) {
        if (step2State != Model.SS_OK) {
            return new StepInfo(
                    Model.SS_PENDING,
                    step2State == Model.SS_ERROR ? "blocked by build mismatch" : "waiting for handshake");
        }
        if (in.requiresTransportProbe && !in.probeOk) {
            String detail = in.probeInFlight
                    ? "testing transport I/O\u2026 " + safe(in.probeInfo)
                    : "transport test retrying\u2026 " + safe(in.probeInfo);
            return new StepInfo(Model.SS_ACTIVE, detail);
        }
        if (diagnostics.streamFlowing) {
            return new StepInfo(Model.SS_OK,
                    "live" + BULLET + "fps=" + String.format(Locale.US, "%.0f", in.latestPresentFps)
                            + BULLET + safe(in.effectiveDaemonState).toLowerCase(Locale.US));
        }
        boolean hasWaited = elapsedMs > 5_000L;
        if (diagnostics.daemonStartFailure && hasWaited) {
            return new StepInfo(Model.SS_ERROR,
                    "host stream start failed" + BULLET + safe(in.daemonErrCompact));
        }
        if (diagnostics.streamReconnects > 0) {
            if (hasWaited) {
                return new StepInfo(Model.SS_ACTIVE,
                        ATTEMPT_PREFIX + diagnostics.streamReconnects
                                + BULLET + diagnostics.streamAddr + ":" + in.streamPort
                                + " unreachable" + BULLET + diagnostics.streamFixHint
                                + (safe(in.daemonErrCompact).isEmpty() ? "" : BULLET + "host error: "
                                        + safe(in.daemonErrCompact)));
            }
            return new StepInfo(Model.SS_ACTIVE,
                    "reconnecting" + BULLET + ATTEMPT_PREFIX + diagnostics.streamReconnects
                            + BULLET + "awaiting frames\u2026");
        }
        if (hasWaited) {
            return new StepInfo(Model.SS_ACTIVE,
                    "connecting to " + diagnostics.streamAddr + ":" + in.streamPort
                            + BULLET + diagnostics.streamFixHint
                            + (safe(in.daemonErrCompact).isEmpty() ? "" : BULLET + "host error: "
                                    + safe(in.daemonErrCompact)));
        }
        return new StepInfo(Model.SS_ACTIVE, "decoder started" + BULLET + "awaiting frames\u2026");
    }

    private static String buildSubtitle(
            StepInfo step1Info,
            StepInfo step2Info,
            StepInfo step3Info,
            StartupProgress progress,
            StreamDiagnostics diagnostics,
            boolean buildMismatch
    ) {
        int step1 = step1Info.state;
        int step2 = step2Info.state;
        int step3 = step3Info.state;
        if (step1 != Model.SS_OK) {
            if (progress.elapsedMs < 2000L && progress.updatedControlRetryCount == 0) {
                return "starting up\u2026";
            }
            if (progress.updatedControlRetryCount == 0) {
                return "awaiting control link" + BULLET + "start desktop service if needed\u2026";
            }
            return "retrying control link" + BULLET + ATTEMPT_PREFIX + progress.updatedControlRetryCount
                    + BULLET + "check desktop service\u2026";
        }
        if (step2 != Model.SS_OK) {
            return (step2 == Model.SS_ERROR && buildMismatch)
                    ? "build mismatch" + BULLET + "redeploy APK or rebuild host"
                    : "handshake in progress\u2026";
        }
        if (step3 != Model.SS_OK) {
            if (step3 == Model.SS_ERROR && diagnostics.daemonStartFailure) {
                return "host stream start failed" + BULLET + "check host logs";
            }
            if (diagnostics.streamReconnects > 0) {
                return "stream reconnecting" + BULLET + ATTEMPT_PREFIX + diagnostics.streamReconnects + "\u2026";
            }
            if (progress.elapsedMs > 5_000L) {
                return "stream unreachable" + BULLET + "retrying\u2026";
            }
            return "waiting for video frames\u2026";
        }
        return "all systems ready";
    }

    private static final class StepInfo {
        private final int state;
        private final String detail;

        private StepInfo(int state, String detail) {
            this.state = state;
            this.detail = detail;
        }
    }

    private static final class StartupProgress {
        private final long elapsedMs;
        private final long updatedStartupBeganAtMs;
        private final int updatedControlRetryCount;

        private StartupProgress(long elapsedMs, long updatedStartupBeganAtMs, int updatedControlRetryCount) {
            this.elapsedMs = elapsedMs;
            this.updatedStartupBeganAtMs = updatedStartupBeganAtMs;
            this.updatedControlRetryCount = updatedControlRetryCount;
        }
    }

    private static final class StreamDiagnostics {
        private final boolean streamFlowing;
        private final int streamReconnects;
        private final String streamAddr;
        private final boolean daemonStartFailure;
        private final String streamFixHint;

        private StreamDiagnostics(
                boolean streamFlowing,
                int streamReconnects,
                String streamAddr,
                boolean daemonStartFailure,
                String streamFixHint
        ) {
            this.streamFlowing = streamFlowing;
            this.streamReconnects = streamReconnects;
            this.streamAddr = streamAddr;
            this.daemonStartFailure = daemonStartFailure;
            this.streamFixHint = streamFixHint;
        }
    }

    private static int parseReconnectCount(String statsLine) {
        if (statsLine == null) {
            return 0;
        }
        try {
            int rIdx = statsLine.indexOf(RECONNECTS_SUFFIX);
            if (rIdx < 0) {
                return 0;
            }
            String rPart = statsLine.substring(rIdx + RECONNECTS_SUFFIX.length()).trim();
            int end = 0;
            while (end < rPart.length() && Character.isDigit(rPart.charAt(end))) {
                end++;
            }
            if (end <= 0) {
                return 0;
            }
            return Integer.parseInt(rPart.substring(0, end));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return true;
        }
        String trimmed = host.trim();
        return trimmed.isEmpty()
                || "127.0.0.1".equals(trimmed)
                || "localhost".equalsIgnoreCase(trimmed);
    }

    private static String streamAddress(String host) {
        if (host != null && !host.trim().isEmpty()) {
            return host.trim();
        }
        return "127.0.0.1";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
