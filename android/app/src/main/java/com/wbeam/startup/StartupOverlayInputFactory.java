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
