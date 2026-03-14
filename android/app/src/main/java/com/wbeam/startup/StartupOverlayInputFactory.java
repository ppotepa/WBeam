package com.wbeam.startup;

public final class StartupOverlayInputFactory {
    /**
     * Plain data carrier for startup overlay input state.
     */
    public static final class State {
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
    }

    private StartupOverlayInputFactory() {
    }

    @SuppressWarnings("java:S107")
    public static StartupOverlayModelBuilder.Input fromRuntimeState(
            boolean daemonReachable,
            String daemonHostName,
            String daemonService,
            String daemonBuildRevision,
            String daemonState,
            String daemonLastError,
            boolean handshakeResolved,
            boolean buildMismatch,
            boolean requiresTransportProbe,
            boolean probeOk,
            boolean probeInFlight,
            String probeInfo,
            String apiImpl,
            String apiBase,
            String apiHost,
            String streamHost,
            int streamPort,
            String appBuildRevision,
            String lastUiInfo,
            String effectiveDaemonState,
            double latestPresentFps,
            long startupBeganAtMs,
            int controlRetryCount,
            long nowMs,
            String lastStatsLine,
            String daemonErrCompact
    ) {
        State state = new State();
        state.daemonReachable = daemonReachable;
        state.daemonHostName = daemonHostName;
        state.daemonService = daemonService;
        state.daemonBuildRevision = daemonBuildRevision;
        state.daemonState = daemonState;
        state.daemonLastError = daemonLastError;
        state.handshakeResolved = handshakeResolved;
        state.buildMismatch = buildMismatch;
        state.requiresTransportProbe = requiresTransportProbe;
        state.probeOk = probeOk;
        state.probeInFlight = probeInFlight;
        state.probeInfo = probeInfo;
        state.apiImpl = apiImpl;
        state.apiBase = apiBase;
        state.apiHost = apiHost;
        state.streamHost = streamHost;
        state.streamPort = streamPort;
        state.appBuildRevision = appBuildRevision;
        state.lastUiInfo = lastUiInfo;
        state.effectiveDaemonState = effectiveDaemonState;
        state.latestPresentFps = latestPresentFps;
        state.startupBeganAtMs = startupBeganAtMs;
        state.controlRetryCount = controlRetryCount;
        state.nowMs = nowMs;
        state.lastStatsLine = lastStatsLine;
        state.daemonErrCompact = daemonErrCompact;
        return build(state);
    }

    public static StartupOverlayModelBuilder.Input build(State state) {
        StartupOverlayModelBuilder.Input input = new StartupOverlayModelBuilder.Input();
        input.setDaemonReachable(state.daemonReachable);
        input.setDaemonHostName(state.daemonHostName);
        input.setDaemonService(state.daemonService);
        input.setDaemonBuildRevision(state.daemonBuildRevision);
        input.setDaemonState(state.daemonState);
        input.setDaemonLastError(state.daemonLastError);
        input.setHandshakeResolved(state.handshakeResolved);
        input.setBuildMismatch(state.buildMismatch);
        input.setRequiresTransportProbe(state.requiresTransportProbe);
        input.setProbeOk(state.probeOk);
        input.setProbeInFlight(state.probeInFlight);
        input.setProbeInfo(state.probeInfo);
        input.setApiImpl(state.apiImpl);
        input.setApiBase(state.apiBase);
        input.setApiHost(state.apiHost);
        input.setStreamHost(state.streamHost);
        input.setStreamPort(state.streamPort);
        input.setAppBuildRevision(state.appBuildRevision);
        input.setLastUiInfo(state.lastUiInfo);
        input.setEffectiveDaemonState(state.effectiveDaemonState);
        input.setLatestPresentFps(state.latestPresentFps);
        input.setStartupBeganAtMs(state.startupBeganAtMs);
        input.setControlRetryCount(state.controlRetryCount);
        input.setNowMs(state.nowMs);
        input.setLastStatsLine(state.lastStatsLine);
        input.setDaemonErrCompact(state.daemonErrCompact);
        return input;
    }
}
