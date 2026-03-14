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
    @SuppressWarnings("java:S1104")
    public static final class Input {
        public boolean daemonReachable;
        public String daemonHostName;
        public String daemonService;
        public String daemonBuildRevision;
        public String daemonState;
        public String daemonLastError;
        public boolean handshakeResolved;
        public boolean buildMismatch;
        public boolean requiresTransportProbe;
        public boolean probeOk;
        public boolean probeInFlight;
        public String probeInfo;
        public String apiImpl;
        public String apiBase;
        public String apiHost;
        public String streamHost;
        public int streamPort;
        public String appBuildRevision;
        public String lastUiInfo;
        public String effectiveDaemonState;
        public double latestPresentFps;
        public long startupBeganAtMs;
        public int controlRetryCount;
        public long nowMs;
        public String lastStatsLine;
        public String daemonErrCompact;
    }

    @SuppressWarnings("java:S1104")
    public static final class Model {
        public static final int SS_PENDING = 0;
        public static final int SS_ACTIVE = 1;
        public static final int SS_OK = 2;
        public static final int SS_ERROR = 3;

        /**
         * Plain data carrier for startup overlay output model.
         */
        public int step1State;
        public int step2State;
        public int step3State;
        public String step1Detail;
        public String step2Detail;
        public String step3Detail;
        public String subtitle;
        public String infoLog;
        public boolean allOk;
        public long updatedStartupBeganAtMs;
        public int updatedControlRetryCount;
        public long elapsedMs;

        static {
            // Suppress S1104 for all public fields in this data carrier
        }
    }

    private static final String ATTEMPT_PREFIX = "attempt #";
    private static final String RECONNECTS_SUFFIX = "reconnects: ";

    @SuppressWarnings({"java:S6541", "java:S3776"})
    public static Model build(Input in) {
        Model out = new Model();
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
        out.updatedStartupBeganAtMs = updatedStartupBeganAtMs;
        out.updatedControlRetryCount = updatedControlRetryCount;
        out.elapsedMs = elapsedMs;

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

        StepInfo step1Info = determineStep1(in, elapsedMs, updatedControlRetryCount);
        StepInfo step2Info = determineStep2(in, step1Info.state);
        StepInfo step3Info = determineStep3(
                in,
                step2Info.state,
                streamFlowing,
                daemonStartFailure,
                streamAddr,
                streamFixHint,
                streamReconnects,
                elapsedMs
        );
        String subtitle = buildSubtitle(
                step1Info.state,
                step2Info.state,
                step3Info.state,
                elapsedMs,
                updatedControlRetryCount,
                daemonStartFailure,
                streamReconnects,
                in.buildMismatch
        );

        StringBuilder info = new StringBuilder();
        info.append("api=").append(safe(in.apiBase))
                .append("  impl=").append(safe(in.apiImpl)).append('\n')
                .append("stream=").append(safe(in.streamHost)).append(':')
                .append(in.streamPort).append('\n')
                .append("app=").append(safe(in.appBuildRevision))
                .append("  host=").append(safe(in.daemonBuildRevision)).append('\n')
                .append("state=").append(safe(in.daemonState));
        if (!safe(in.daemonLastError).isEmpty()) {
            info.append('\n').append("error=").append(safe(in.daemonLastError));
        } else if (!safe(in.lastUiInfo).isEmpty()) {
            info.append('\n').append("hint=").append(safe(in.lastUiInfo));
        }

        out.step1State = step1Info.state;
        out.step1Detail = step1Info.detail;
        out.step2State = step2Info.state;
        out.step2Detail = step2Info.detail;
        out.step3State = step3Info.state;
        out.step3Detail = step3Info.detail;
        out.subtitle = subtitle;
        out.infoLog = info.toString();
        out.allOk = step1Info.state == Model.SS_OK
                && step2Info.state == Model.SS_OK
                && step3Info.state == Model.SS_OK;
        return out;
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
            boolean streamFlowing,
            boolean daemonStartFailure,
            String streamAddr,
            String streamFixHint,
            int streamReconnects,
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
        if (streamFlowing) {
            return new StepInfo(Model.SS_OK,
                    "live" + BULLET + "fps=" + String.format(Locale.US, "%.0f", in.latestPresentFps)
                            + BULLET + safe(in.effectiveDaemonState).toLowerCase(Locale.US));
        }
        boolean hasWaited = elapsedMs > 5_000L;
        if (daemonStartFailure && hasWaited) {
            return new StepInfo(Model.SS_ERROR,
                    "host stream start failed" + BULLET + safe(in.daemonErrCompact));
        }
        if (streamReconnects > 0) {
            if (hasWaited) {
                return new StepInfo(Model.SS_ACTIVE,
                        "retry #" + streamReconnects
                                + BULLET + streamAddr + ":" + in.streamPort
                                + " unreachable" + BULLET + streamFixHint
                                + (safe(in.daemonErrCompact).isEmpty() ? "" : BULLET + "host error: "
                                        + safe(in.daemonErrCompact)));
            }
            return new StepInfo(Model.SS_ACTIVE,
                    "reconnecting" + BULLET + ATTEMPT_PREFIX + streamReconnects + BULLET + "awaiting frames\u2026");
        }
        if (hasWaited) {
            return new StepInfo(Model.SS_ACTIVE,
                    "connecting to " + streamAddr + ":" + in.streamPort
                            + BULLET + streamFixHint
                            + (safe(in.daemonErrCompact).isEmpty() ? "" : BULLET + "host error: "
                                    + safe(in.daemonErrCompact)));
        }
        return new StepInfo(Model.SS_ACTIVE, "decoder started" + BULLET + "awaiting frames\u2026");
    }

    private static String buildSubtitle(
            int step1,
            int step2,
            int step3,
            long elapsedMs,
            int updatedControlRetryCount,
            boolean daemonStartFailure,
            int streamReconnects,
            boolean buildMismatch
    ) {
        if (step1 != Model.SS_OK) {
            if (elapsedMs < 2000L && updatedControlRetryCount == 0) {
                return "starting up\u2026";
            }
            if (updatedControlRetryCount == 0) {
                return "awaiting control link" + BULLET + "start desktop service if needed\u2026";
            }
            return "retrying control link" + BULLET + ATTEMPT_PREFIX + updatedControlRetryCount
                    + BULLET + "check desktop service\u2026";
        }
        if (step2 != Model.SS_OK) {
            return (step2 == Model.SS_ERROR && buildMismatch)
                    ? "build mismatch" + BULLET + "redeploy APK or rebuild host"
                    : "handshake in progress\u2026";
        }
        if (step3 != Model.SS_OK) {
            if (step3 == Model.SS_ERROR && daemonStartFailure) {
                return "host stream start failed" + BULLET + "check host logs";
            }
            if (streamReconnects > 0) {
                return "stream reconnecting" + BULLET + ATTEMPT_PREFIX + streamReconnects + "\u2026";
            }
            if (elapsedMs > 5_000L) {
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
