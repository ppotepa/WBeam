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
    private static final String SEPARATOR = " · ";
    private static final String SERVICE_PREFIX = "service=";

    private StartupOverlayModelBuilder() {}

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
        private String apiHost;
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

        public boolean isDaemonReachable() { return daemonReachable; }
        public void setDaemonReachable(boolean value) { daemonReachable = value; }
        public String getDaemonHostName() { return daemonHostName; }
        public void setDaemonHostName(String value) { daemonHostName = value; }
        public String getDaemonService() { return daemonService; }
        public void setDaemonService(String value) { daemonService = value; }
        public String getDaemonBuildRevision() { return daemonBuildRevision; }
        public void setDaemonBuildRevision(String value) { daemonBuildRevision = value; }
        public String getDaemonState() { return daemonState; }
        public void setDaemonState(String value) { daemonState = value; }
        public String getDaemonLastError() { return daemonLastError; }
        public void setDaemonLastError(String value) { daemonLastError = value; }
        public boolean isHandshakeResolved() { return handshakeResolved; }
        public void setHandshakeResolved(boolean value) { handshakeResolved = value; }
        public boolean isBuildMismatch() { return buildMismatch; }
        public void setBuildMismatch(boolean value) { buildMismatch = value; }
        public boolean isRequiresTransportProbe() { return requiresTransportProbe; }
        public void setRequiresTransportProbe(boolean value) { requiresTransportProbe = value; }
        public boolean isProbeOk() { return probeOk; }
        public void setProbeOk(boolean value) { probeOk = value; }
        public boolean isProbeInFlight() { return probeInFlight; }
        public void setProbeInFlight(boolean value) { probeInFlight = value; }
        public String getProbeInfo() { return probeInfo; }
        public void setProbeInfo(String value) { probeInfo = value; }
        public String getApiImpl() { return apiImpl; }
        public void setApiImpl(String value) { apiImpl = value; }
        public String getApiBase() { return apiBase; }
        public void setApiBase(String value) { apiBase = value; }
        public String getApiHost() { return apiHost; }
        public void setApiHost(String value) { apiHost = value; }
        public String getStreamHost() { return streamHost; }
        public void setStreamHost(String value) { streamHost = value; }
        public int getStreamPort() { return streamPort; }
        public void setStreamPort(int value) { streamPort = value; }
        public String getAppBuildRevision() { return appBuildRevision; }
        public void setAppBuildRevision(String value) { appBuildRevision = value; }
        public String getLastUiInfo() { return lastUiInfo; }
        public void setLastUiInfo(String value) { lastUiInfo = value; }
        public String getEffectiveDaemonState() { return effectiveDaemonState; }
        public void setEffectiveDaemonState(String value) { effectiveDaemonState = value; }
        public double getLatestPresentFps() { return latestPresentFps; }
        public void setLatestPresentFps(double value) { latestPresentFps = value; }
        public long getStartupBeganAtMs() { return startupBeganAtMs; }
        public void setStartupBeganAtMs(long value) { startupBeganAtMs = value; }
        public int getControlRetryCount() { return controlRetryCount; }
        public void setControlRetryCount(int value) { controlRetryCount = value; }
        public long getNowMs() { return nowMs; }
        public void setNowMs(long value) { nowMs = value; }
        public String getLastStatsLine() { return lastStatsLine; }
        public void setLastStatsLine(String value) { lastStatsLine = value; }
        public String getDaemonErrCompact() { return daemonErrCompact; }
        public void setDaemonErrCompact(String value) { daemonErrCompact = value; }
    }

    public static final class Model {
        public static final int SS_PENDING = 0;
        public static final int SS_ACTIVE = 1;
        public static final int SS_OK = 2;
        public static final int SS_ERROR = 3;

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

        public int getStep1State() { return step1State; }
        public void setStep1State(int value) { step1State = value; }
        public int getStep2State() { return step2State; }
        public void setStep2State(int value) { step2State = value; }
        public int getStep3State() { return step3State; }
        public void setStep3State(int value) { step3State = value; }
        public String getStep1Detail() { return step1Detail; }
        public void setStep1Detail(String value) { step1Detail = value; }
        public String getStep2Detail() { return step2Detail; }
        public void setStep2Detail(String value) { step2Detail = value; }
        public String getStep3Detail() { return step3Detail; }
        public void setStep3Detail(String value) { step3Detail = value; }
        public String getSubtitle() { return subtitle; }
        public void setSubtitle(String value) { subtitle = value; }
        public String getInfoLog() { return infoLog; }
        public void setInfoLog(String value) { infoLog = value; }
        public boolean isAllOk() { return allOk; }
        public void setAllOk(boolean value) { allOk = value; }
        public long getUpdatedStartupBeganAtMs() { return updatedStartupBeganAtMs; }
        public void setUpdatedStartupBeganAtMs(long value) { updatedStartupBeganAtMs = value; }
        public int getUpdatedControlRetryCount() { return updatedControlRetryCount; }
        public void setUpdatedControlRetryCount(int value) { updatedControlRetryCount = value; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long value) { elapsedMs = value; }
    }

    public static Model build(Input in) {
        Model out = new Model();
        TimingState timing = calculateTiming(in);
        out.setUpdatedStartupBeganAtMs(timing.startupBeganAtMs);
        out.setUpdatedControlRetryCount(timing.controlRetryCount);
        out.setElapsedMs(timing.elapsedMs);

        Step1State step1 = buildStep1(in, timing);
        out.setStep1State(step1.state);
        out.setStep1Detail(step1.detail);

        Step2State step2 = buildStep2(in, step1.state);
        out.setStep2State(step2.state);
        out.setStep2Detail(step2.detail);

        Step3Context ctx = createStep3Context(in, timing.elapsedMs);
        Step3State step3 = buildStep3(in, step2.state, ctx);
        out.setStep3State(step3.state);
        out.setStep3Detail(step3.detail);

        out.setSubtitle(buildSubtitle(in, step1.state, step2.state, step3.state, ctx, timing));
        out.setInfoLog(buildInfoLog(in));
        out.setAllOk(step1.state == Model.SS_OK && step2.state == Model.SS_OK && step3.state == Model.SS_OK);
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
        long elapsedMs;

        Step3Context(boolean streamFlowing, int streamReconnects, String streamAddr,
                     boolean daemonStartFailure, String streamFixHint) {
            this.streamFlowing = streamFlowing;
            this.streamReconnects = streamReconnects;
            this.streamAddr = streamAddr;
            this.daemonStartFailure = daemonStartFailure;
            this.streamFixHint = streamFixHint;
            this.elapsedMs = 0;
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
        int state = in.isDaemonReachable() ? Model.SS_OK : Model.SS_ACTIVE;
        String detail;
        if (state == Model.SS_OK) {
            boolean isLocalImpl = LOCAL_API.equalsIgnoreCase(in.getApiImpl());
            detail = isLocalImpl
                    ? "on-device (local api) \u00b7 no host connection needed"
                    : "reachable " + SEPARATOR + "api_impl=" + safe(in.getApiImpl()) + SEPARATOR + safe(in.getDaemonHostName());
        } else if (timing.controlRetryCount == 0) {
            detail = "polling " + safe(in.getApiBase())
                    + " \u2026 (" + (timing.elapsedMs / 1000L) + "s)"
                    + " \u00b7 install/start desktop service if this does not recover";
        } else {
            detail = "no response \u00b7 retry #" + timing.controlRetryCount
                    + " \u00b7 polling " + safe(in.getApiBase())
                    + " (" + (timing.elapsedMs / 1000L) + "s)"
                    + " \u00b7 check desktop service status";
        }
        return new Step1State(state, detail);
    }

    private static Step2State buildStep2(Input in, int step1State) {
        if (step1State != Model.SS_OK) {
            return new Step2State(Model.SS_PENDING, "waiting for control link");
        }
        if (!in.isHandshakeResolved()) {
            return new Step2State(Model.SS_ACTIVE, "resolving service / api version\u2026");
        }
        if (in.isBuildMismatch()) {
            String detail = "build mismatch · app=" + safe(in.getAppBuildRevision())
                    + " · host=" + safe(in.getDaemonBuildRevision());
            return new Step2State(Model.SS_ERROR, detail);
        }
        String detail = buildStep2OkDetail(in);
        return new Step2State(Model.SS_OK, detail);
    }

    private static String buildStep2OkDetail(Input in) {
        if (in.isRequiresTransportProbe()) {
            if (in.isProbeOk()) {
                return SERVICE_PREFIX + safe(in.getDaemonService()) + " · transport test OK";
            } else if (in.isProbeInFlight()) {
                return SERVICE_PREFIX + safe(in.getDaemonService()) + " · transport test in progress…";
            } else {
                return SERVICE_PREFIX + safe(in.getDaemonService()) + " · transport test pending";
            }
        }
        return SERVICE_PREFIX + safe(in.getDaemonService()) + " · " + safe(in.getApiImpl());
    }

    private static Step3Context createStep3Context(Input in, long elapsedMs) {
        boolean streamFlowing = STREAMING_STATE.equalsIgnoreCase(safe(in.getEffectiveDaemonState()));
        int streamReconnects = parseReconnectCount(in.getLastStatsLine());
        String streamAddr = streamAddress(in.getStreamHost());
        boolean daemonStartFailure = safe(in.getDaemonErrCompact())
                .toLowerCase(Locale.US)
                .contains(STREAM_START_ABORTED);
        boolean streamIsLoopback = isLoopbackHost(in.getStreamHost());
        String streamFixHint = streamIsLoopback
                ? "check ADB reverse for stream/control ports \u00b7 ensure desktop service is running"
                : "check USB tethering / host IP / LAN \u00b7 ensure desktop service is running";
        Step3Context ctx = new Step3Context(streamFlowing, streamReconnects, streamAddr, daemonStartFailure, streamFixHint);
        ctx.elapsedMs = elapsedMs;
        return ctx;
    }

    private static Step3State buildStep3(Input in, int step2State, Step3Context ctx) {
        if (step2State != Model.SS_OK) {
            String detail = (step2State == Model.SS_ERROR && in.isBuildMismatch())
                    ? "blocked by build mismatch"
                    : "waiting for handshake";
            return new Step3State(Model.SS_PENDING, detail);
        }
        if (in.isRequiresTransportProbe() && !in.isProbeOk()) {
            int state = Model.SS_ACTIVE;
            String detail = in.isProbeInFlight()
                    ? "testing transport I/O… " + safe(in.getProbeInfo())
                    : "transport test retrying… " + safe(in.getProbeInfo());
            return new Step3State(state, detail);
        }
        if (ctx.streamFlowing) {
            String detail = "live \u00b7 fps=" + String.format(Locale.US, "%.0f", in.getLatestPresentFps())
                    + " \u00b7 " + safe(in.getEffectiveDaemonState()).toLowerCase(Locale.US);
            return new Step3State(Model.SS_OK, detail);
        }
        return buildStep3Active(in, ctx);
    }

    private static Step3State buildStep3Active(Input in, Step3Context ctx) {
        boolean hasWaited = ctx.elapsedMs > 5_000L;
        if (ctx.daemonStartFailure && hasWaited) {
            return new Step3State(Model.SS_ERROR, "host stream start failed \u00b7 " + safe(in.getDaemonErrCompact()));
        }
        String detail = buildStep3ActiveDetail(in, ctx, hasWaited);
        return new Step3State(Model.SS_ACTIVE, detail);
    }

    private static String buildStep3ActiveDetail(Input in, Step3Context ctx, boolean hasWaited) {
        if (ctx.streamReconnects > 0 && hasWaited) {
            return "retry #" + ctx.streamReconnects
                    + " \u00b7 " + ctx.streamAddr + ":" + in.getStreamPort()
                    + " unreachable \u00b7 " + ctx.streamFixHint
                    + (safe(in.getDaemonErrCompact()).isEmpty() ? "" : " \u00b7 host error: " + safe(in.getDaemonErrCompact()));
        }
        if (ctx.streamReconnects > 0) {
            return "reconnecting \u00b7 " + ATTEMPT_PREFIX + ctx.streamReconnects + " \u00b7 awaiting frames\u2026";
        }
        if (hasWaited) {
            return "connecting to " + ctx.streamAddr + ":" + in.getStreamPort()
                    + " \u00b7 " + ctx.streamFixHint
                    + (safe(in.getDaemonErrCompact()).isEmpty() ? "" : " \u00b7 host error: " + safe(in.getDaemonErrCompact()));
        }
        return "decoder started \u00b7 awaiting frames\u2026";
    }

    private static String buildSubtitle(Input in, int step1State, int step2State, int step3State,
                                        Step3Context ctx, TimingState timing) {
        if (step1State != Model.SS_OK) {
            return buildSubtitleStep1NotOk(timing);
        }
        if (step2State != Model.SS_OK) {
            return (step2State == Model.SS_ERROR && in.isBuildMismatch())
                    ? "build mismatch \u00b7 redeploy APK or rebuild host"
                    : "handshake in progress\u2026";
        }
        if (step3State != Model.SS_OK) {
            if (step3State == Model.SS_ERROR && ctx.daemonStartFailure) {
                return "host stream start failed \u00b7 check host logs";
            }
            return buildSubtitleStep3NotOk(ctx, ctx.elapsedMs);
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
        info.append("api=").append(safe(in.getApiBase()))
                .append("  impl=").append(safe(in.getApiImpl())).append('\n')
                .append("stream=").append(safe(in.getStreamHost())).append(':')
                .append(in.getStreamPort()).append('\n')
                .append("app=").append(safe(in.getAppBuildRevision()))
                .append("  host=").append(safe(in.getDaemonBuildRevision())).append('\n')
                .append("state=").append(safe(in.getDaemonState()));
        if (!safe(in.getDaemonLastError()).isEmpty()) {
            info.append('\n').append("error=").append(safe(in.getDaemonLastError()));
        } else if (!safe(in.getLastUiInfo()).isEmpty()) {
            info.append('\n').append("hint=").append(safe(in.getLastUiInfo()));
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
