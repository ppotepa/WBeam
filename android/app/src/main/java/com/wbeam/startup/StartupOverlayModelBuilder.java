package com.wbeam.startup;

import java.util.Locale;

/**
 * Builds preflight/startup overlay model from current runtime snapshot.
 */
public final class StartupOverlayModelBuilder {
    private static final String ATTEMPT_PREFIX = "attempt #";
    private static final String RECONNECTS_PREFIX = "reconnects: ";
    private static final String LOCAL_API = "local";
    private static final String STREAMING_STATE = "STREAMING";
    private static final String STREAM_START_ABORTED = "stream start aborted";

    private StartupOverlayModelBuilder() {}

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

    public static final class Model {
        public static final int SS_PENDING = 0;
        public static final int SS_ACTIVE = 1;
        public static final int SS_OK = 2;
        public static final int SS_ERROR = 3;

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
    }

    public static Model build(Input in) {
        Model out = new Model();
        TimingState timing = calculateTiming(in);
        out.updatedStartupBeganAtMs = timing.startupBeganAtMs;
        out.updatedControlRetryCount = timing.controlRetryCount;
        out.elapsedMs = timing.elapsedMs;

        Step1State step1 = buildStep1(in, timing);
        out.step1State = step1.state;
        out.step1Detail = step1.detail;

        Step2State step2 = buildStep2(in, step1.state);
        out.step2State = step2.state;
        out.step2Detail = step2.detail;

        Step3Context ctx = createStep3Context(in, timing.elapsedMs);
        Step3State step3 = buildStep3(in, step2.state, ctx, timing.elapsedMs);
        out.step3State = step3.state;
        out.step3Detail = step3.detail;

        out.subtitle = buildSubtitle(in, step1.state, step2.state, step3.state, ctx, timing);
        out.infoLog = buildInfoLog(in);
        out.allOk = step1.state == Model.SS_OK && step2.state == Model.SS_OK && step3.state == Model.SS_OK;
        return out;
    }

    private static class TimingState {
        long elapsedMs;
        long startupBeganAtMs;
        int controlRetryCount;

        TimingState(long elapsedMs, long startupBeganAtMs, int controlRetryCount) {
            this.elapsedMs = elapsedMs;
            this.startupBeganAtMs = startupBeganAtMs;
            this.controlRetryCount = controlRetryCount;
        }
    }

    private static class Step1State {
        int state;
        String detail;

        Step1State(int state, String detail) {
            this.state = state;
            this.detail = detail;
        }
    }

    private static class Step2State {
        int state;
        String detail;

        Step2State(int state, String detail) {
            this.state = state;
            this.detail = detail;
        }
    }

    private static class Step3State {
        int state;
        String detail;

        Step3State(int state, String detail) {
            this.state = state;
            this.detail = detail;
        }
    }

    private static class Step3Context {
        boolean streamFlowing;
        int streamReconnects;
        String streamAddr;
        boolean daemonStartFailure;
        String streamFixHint;

        Step3Context(boolean streamFlowing, int streamReconnects, String streamAddr,
                     boolean daemonStartFailure, String streamFixHint) {
            this.streamFlowing = streamFlowing;
            this.streamReconnects = streamReconnects;
            this.streamAddr = streamAddr;
            this.daemonStartFailure = daemonStartFailure;
            this.streamFixHint = streamFixHint;
        }
    }

    private static TimingState calculateTiming(Input in) {
        long elapsedMs = in.startupBeganAtMs > 0L
                ? Math.max(0L, in.nowMs - in.startupBeganAtMs)
                : 0L;
        long startupBeganAtMs = in.startupBeganAtMs;
        int controlRetryCount = in.controlRetryCount;

        if (!in.daemonReachable && elapsedMs > 20_000L) {
            controlRetryCount++;
            startupBeganAtMs = in.nowMs;
            elapsedMs = 0L;
        }
        if (in.daemonReachable && controlRetryCount > 0) {
            controlRetryCount = 0;
        }
        return new TimingState(elapsedMs, startupBeganAtMs, controlRetryCount);
    }

    private static Step1State buildStep1(Input in, TimingState timing) {
        int state = in.daemonReachable ? Model.SS_OK : Model.SS_ACTIVE;
        String detail;
        if (state == Model.SS_OK) {
            boolean isLocalImpl = LOCAL_API.equalsIgnoreCase(in.apiImpl);
            detail = isLocalImpl
                    ? "on-device (local api) \u00b7 no host connection needed"
                    : "reachable \u00b7 api_impl=" + safe(in.apiImpl) + " \u00b7 " + safe(in.daemonHostName);
        } else if (timing.controlRetryCount == 0) {
            detail = "polling " + safe(in.apiBase)
                    + " \u2026 (" + (timing.elapsedMs / 1000L) + "s)"
                    + " \u00b7 install/start desktop service if this does not recover";
        } else {
            detail = "no response \u00b7 retry #" + timing.controlRetryCount
                    + " \u00b7 polling " + safe(in.apiBase)
                    + " (" + (timing.elapsedMs / 1000L) + "s)"
                    + " \u00b7 check desktop service status";
        }
        return new Step1State(state, detail);
    }

    private static Step2State buildStep2(Input in, int step1State) {
        if (step1State != Model.SS_OK) {
            return new Step2State(Model.SS_PENDING, "waiting for control link");
        }
        if (!in.handshakeResolved) {
            return new Step2State(Model.SS_ACTIVE, "resolving service / api version\u2026");
        }
        if (in.buildMismatch) {
            String detail = "build mismatch · app=" + safe(in.appBuildRevision)
                    + " · host=" + safe(in.daemonBuildRevision);
            return new Step2State(Model.SS_ERROR, detail);
        }
        String detail = buildStep2OkDetail(in);
        return new Step2State(Model.SS_OK, detail);
    }

    private static String buildStep2OkDetail(Input in) {
        if (in.requiresTransportProbe) {
            if (in.probeOk) {
                return "service=" + safe(in.daemonService) + " · transport test OK";
            } else if (in.probeInFlight) {
                return "service=" + safe(in.daemonService) + " · transport test in progress…";
            } else {
                return "service=" + safe(in.daemonService) + " · transport test pending";
            }
        }
        return "service=" + safe(in.daemonService) + " · " + safe(in.apiImpl);
    }

    private static Step3Context createStep3Context(Input in, long elapsedMs) {
        boolean streamFlowing = STREAMING_STATE.equalsIgnoreCase(safe(in.effectiveDaemonState));
        int streamReconnects = parseReconnectCount(in.lastStatsLine);
        String streamAddr = streamAddress(in.streamHost);
        boolean daemonStartFailure = safe(in.daemonErrCompact)
                .toLowerCase(Locale.US)
                .contains(STREAM_START_ABORTED);
        boolean streamIsLoopback = isLoopbackHost(in.streamHost);
        String streamFixHint = streamIsLoopback
                ? "check ADB reverse for stream/control ports \u00b7 ensure desktop service is running"
                : "check USB tethering / host IP / LAN \u00b7 ensure desktop service is running";
        return new Step3Context(streamFlowing, streamReconnects, streamAddr, daemonStartFailure, streamFixHint);
    }

    private static Step3State buildStep3(Input in, int step2State, Step3Context ctx, long elapsedMs) {
        if (step2State != Model.SS_OK) {
            String detail = (step2State == Model.SS_ERROR && in.buildMismatch)
                    ? "blocked by build mismatch"
                    : "waiting for handshake";
            return new Step3State(Model.SS_PENDING, detail);
        }
        if (in.requiresTransportProbe && !in.probeOk) {
            int state = Model.SS_ACTIVE;
            String detail = in.probeInFlight
                    ? "testing transport I/O… " + safe(in.probeInfo)
                    : "transport test retrying… " + safe(in.probeInfo);
            return new Step3State(state, detail);
        }
        if (ctx.streamFlowing) {
            String detail = "live \u00b7 fps=" + String.format(Locale.US, "%.0f", in.latestPresentFps)
                    + " \u00b7 " + safe(in.effectiveDaemonState).toLowerCase(Locale.US);
            return new Step3State(Model.SS_OK, detail);
        }
        return buildStep3Active(in, ctx, elapsedMs);
    }

    private static Step3State buildStep3Active(Input in, Step3Context ctx, long elapsedMs) {
        boolean hasWaited = elapsedMs > 5_000L;
        if (ctx.daemonStartFailure && hasWaited) {
            return new Step3State(Model.SS_ERROR, "host stream start failed \u00b7 " + safe(in.daemonErrCompact));
        }
        String detail = buildStep3ActiveDetail(in, ctx, elapsedMs, hasWaited);
        return new Step3State(Model.SS_ACTIVE, detail);
    }

    private static String buildStep3ActiveDetail(Input in, Step3Context ctx, long elapsedMs, boolean hasWaited) {
        if (ctx.streamReconnects > 0 && hasWaited) {
            return "retry #" + ctx.streamReconnects
                    + " \u00b7 " + ctx.streamAddr + ":" + in.streamPort
                    + " unreachable \u00b7 " + ctx.streamFixHint
                    + (safe(in.daemonErrCompact).isEmpty() ? "" : " \u00b7 host error: " + safe(in.daemonErrCompact));
        }
        if (ctx.streamReconnects > 0) {
            return "reconnecting \u00b7 " + ATTEMPT_PREFIX + ctx.streamReconnects + " \u00b7 awaiting frames\u2026";
        }
        if (hasWaited) {
            return "connecting to " + ctx.streamAddr + ":" + in.streamPort
                    + " \u00b7 " + ctx.streamFixHint
                    + (safe(in.daemonErrCompact).isEmpty() ? "" : " \u00b7 host error: " + safe(in.daemonErrCompact));
        }
        return "decoder started \u00b7 awaiting frames\u2026";
    }

    private static String buildSubtitle(Input in, int step1State, int step2State, int step3State,
                                        Step3Context ctx, TimingState timing) {
        if (step1State != Model.SS_OK) {
            return buildSubtitleStep1NotOk(timing);
        }
        if (step2State != Model.SS_OK) {
            return (step2State == Model.SS_ERROR && in.buildMismatch)
                    ? "build mismatch \u00b7 redeploy APK or rebuild host"
                    : "handshake in progress\u2026";
        }
        if (step3State != Model.SS_OK) {
            if (step3State == Model.SS_ERROR && ctx.daemonStartFailure) {
                return "host stream start failed \u00b7 check host logs";
            }
            return buildSubtitleStep3NotOk(ctx, timing.elapsedMs);
        }
        return "all systems ready";
    }

    private static String buildSubtitleStep1NotOk(TimingState timing) {
        if (timing.elapsedMs < 2000L && timing.controlRetryCount == 0) {
            return "starting up\u2026";
        }
        if (timing.controlRetryCount == 0) {
            return "awaiting control link \u00b7 start desktop service if needed\u2026";
        }
        return "retrying control link \u00b7 " + ATTEMPT_PREFIX + timing.controlRetryCount
                + " \u00b7 check desktop service\u2026";
    }

    private static String buildSubtitleStep3NotOk(Step3Context ctx, long elapsedMs) {
        if (ctx.streamReconnects > 0) {
            return "stream reconnecting \u00b7 " + ATTEMPT_PREFIX + ctx.streamReconnects + "\u2026";
        }
        if (elapsedMs > 5_000L) {
            return "stream unreachable \u00b7 retrying\u2026";
        }
        return "waiting for video frames\u2026";
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
        if (!safe(in.daemonLastError).isEmpty()) {
            info.append('\n').append("error=").append(safe(in.daemonLastError));
        } else if (!safe(in.lastUiInfo).isEmpty()) {
            info.append('\n').append("hint=").append(safe(in.lastUiInfo));
        }
        return info.toString();
    }

    private static int parseReconnectCount(String statsLine) {
        if (statsLine == null) {
            return 0;
        }
        try {
            int rIdx = statsLine.indexOf(RECONNECTS_PREFIX);
            if (rIdx < 0) {
                return 0;
            }
            String rPart = statsLine.substring(rIdx + RECONNECTS_PREFIX.length()).trim();
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
