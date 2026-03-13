package com.wbeam.startup;

public final class StartupOverlayInputFactory {
    public static final class State {
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

    private StartupOverlayInputFactory() {
    }

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
        input.daemonReachable = state.daemonReachable;
        input.daemonHostName = state.daemonHostName;
        input.daemonService = state.daemonService;
        input.daemonBuildRevision = state.daemonBuildRevision;
        input.daemonState = state.daemonState;
        input.daemonLastError = state.daemonLastError;
        input.handshakeResolved = state.handshakeResolved;
        input.buildMismatch = state.buildMismatch;
        input.requiresTransportProbe = state.requiresTransportProbe;
        input.probeOk = state.probeOk;
        input.probeInFlight = state.probeInFlight;
        input.probeInfo = state.probeInfo;
        input.apiImpl = state.apiImpl;
        input.apiBase = state.apiBase;
        input.apiHost = state.apiHost;
        input.streamHost = state.streamHost;
        input.streamPort = state.streamPort;
        input.appBuildRevision = state.appBuildRevision;
        input.lastUiInfo = state.lastUiInfo;
        input.effectiveDaemonState = state.effectiveDaemonState;
        input.latestPresentFps = state.latestPresentFps;
        input.startupBeganAtMs = state.startupBeganAtMs;
        input.controlRetryCount = state.controlRetryCount;
        input.nowMs = state.nowMs;
        input.lastStatsLine = state.lastStatsLine;
        input.daemonErrCompact = state.daemonErrCompact;
        return input;
    }
}
