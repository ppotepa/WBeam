package com.wbeam.startup;

@SuppressWarnings("java:S107")
public final class StartupOverlayInputFactory {
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

        public boolean isDaemonReachable() {
            return daemonReachable;
        }

        public void setDaemonReachable(boolean daemonReachable) {
            this.daemonReachable = daemonReachable;
        }

        public String getDaemonHostName() {
            return daemonHostName;
        }

        public void setDaemonHostName(String daemonHostName) {
            this.daemonHostName = daemonHostName;
        }

        public String getDaemonService() {
            return daemonService;
        }

        public void setDaemonService(String daemonService) {
            this.daemonService = daemonService;
        }

        public String getDaemonBuildRevision() {
            return daemonBuildRevision;
        }

        public void setDaemonBuildRevision(String daemonBuildRevision) {
            this.daemonBuildRevision = daemonBuildRevision;
        }

        public String getDaemonState() {
            return daemonState;
        }

        public void setDaemonState(String daemonState) {
            this.daemonState = daemonState;
        }

        public String getDaemonLastError() {
            return daemonLastError;
        }

        public void setDaemonLastError(String daemonLastError) {
            this.daemonLastError = daemonLastError;
        }

        public boolean isHandshakeResolved() {
            return handshakeResolved;
        }

        public void setHandshakeResolved(boolean handshakeResolved) {
            this.handshakeResolved = handshakeResolved;
        }

        public boolean isBuildMismatch() {
            return buildMismatch;
        }

        public void setBuildMismatch(boolean buildMismatch) {
            this.buildMismatch = buildMismatch;
        }

        public boolean isRequiresTransportProbe() {
            return requiresTransportProbe;
        }

        public void setRequiresTransportProbe(boolean requiresTransportProbe) {
            this.requiresTransportProbe = requiresTransportProbe;
        }

        public boolean isProbeOk() {
            return probeOk;
        }

        public void setProbeOk(boolean probeOk) {
            this.probeOk = probeOk;
        }

        public boolean isProbeInFlight() {
            return probeInFlight;
        }

        public void setProbeInFlight(boolean probeInFlight) {
            this.probeInFlight = probeInFlight;
        }

        public String getProbeInfo() {
            return probeInfo;
        }

        public void setProbeInfo(String probeInfo) {
            this.probeInfo = probeInfo;
        }

        public String getApiImpl() {
            return apiImpl;
        }

        public void setApiImpl(String apiImpl) {
            this.apiImpl = apiImpl;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public String getApiHost() {
            return apiHost;
        }

        public void setApiHost(String apiHost) {
            this.apiHost = apiHost;
        }

        public String getStreamHost() {
            return streamHost;
        }

        public void setStreamHost(String streamHost) {
            this.streamHost = streamHost;
        }

        public int getStreamPort() {
            return streamPort;
        }

        public void setStreamPort(int streamPort) {
            this.streamPort = streamPort;
        }

        public String getAppBuildRevision() {
            return appBuildRevision;
        }

        public void setAppBuildRevision(String appBuildRevision) {
            this.appBuildRevision = appBuildRevision;
        }

        public String getLastUiInfo() {
            return lastUiInfo;
        }

        public void setLastUiInfo(String lastUiInfo) {
            this.lastUiInfo = lastUiInfo;
        }

        public String getEffectiveDaemonState() {
            return effectiveDaemonState;
        }

        public void setEffectiveDaemonState(String effectiveDaemonState) {
            this.effectiveDaemonState = effectiveDaemonState;
        }

        public double getLatestPresentFps() {
            return latestPresentFps;
        }

        public void setLatestPresentFps(double latestPresentFps) {
            this.latestPresentFps = latestPresentFps;
        }

        public long getStartupBeganAtMs() {
            return startupBeganAtMs;
        }

        public void setStartupBeganAtMs(long startupBeganAtMs) {
            this.startupBeganAtMs = startupBeganAtMs;
        }

        public int getControlRetryCount() {
            return controlRetryCount;
        }

        public void setControlRetryCount(int controlRetryCount) {
            this.controlRetryCount = controlRetryCount;
        }

        public long getNowMs() {
            return nowMs;
        }

        public void setNowMs(long nowMs) {
            this.nowMs = nowMs;
        }

        public String getLastStatsLine() {
            return lastStatsLine;
        }

        public void setLastStatsLine(String lastStatsLine) {
            this.lastStatsLine = lastStatsLine;
        }

        public String getDaemonErrCompact() {
            return daemonErrCompact;
        }

        public void setDaemonErrCompact(String daemonErrCompact) {
            this.daemonErrCompact = daemonErrCompact;
        }
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
        state.setDaemonReachable(daemonReachable);
        state.setDaemonHostName(daemonHostName);
        state.setDaemonService(daemonService);
        state.setDaemonBuildRevision(daemonBuildRevision);
        state.setDaemonState(daemonState);
        state.setDaemonLastError(daemonLastError);
        state.setHandshakeResolved(handshakeResolved);
        state.setBuildMismatch(buildMismatch);
        state.setRequiresTransportProbe(requiresTransportProbe);
        state.setProbeOk(probeOk);
        state.setProbeInFlight(probeInFlight);
        state.setProbeInfo(probeInfo);
        state.setApiImpl(apiImpl);
        state.setApiBase(apiBase);
        state.setApiHost(apiHost);
        state.setStreamHost(streamHost);
        state.setStreamPort(streamPort);
        state.setAppBuildRevision(appBuildRevision);
        state.setLastUiInfo(lastUiInfo);
        state.setEffectiveDaemonState(effectiveDaemonState);
        state.setLatestPresentFps(latestPresentFps);
        state.setStartupBeganAtMs(startupBeganAtMs);
        state.setControlRetryCount(controlRetryCount);
        state.setNowMs(nowMs);
        state.setLastStatsLine(lastStatsLine);
        state.setDaemonErrCompact(daemonErrCompact);
        return build(state);
    }

    public static StartupOverlayModelBuilder.Input build(State state) {
        StartupOverlayModelBuilder.Input input = new StartupOverlayModelBuilder.Input();
        input.setDaemonReachable(state.isDaemonReachable());
        input.setDaemonHostName(state.getDaemonHostName());
        input.setDaemonService(state.getDaemonService());
        input.setDaemonBuildRevision(state.getDaemonBuildRevision());
        input.setDaemonState(state.getDaemonState());
        input.setDaemonLastError(state.getDaemonLastError());
        input.setHandshakeResolved(state.isHandshakeResolved());
        input.setBuildMismatch(state.isBuildMismatch());
        input.setRequiresTransportProbe(state.isRequiresTransportProbe());
        input.setProbeOk(state.isProbeOk());
        input.setProbeInFlight(state.isProbeInFlight());
        input.setProbeInfo(state.getProbeInfo());
        input.setApiImpl(state.getApiImpl());
        input.setApiBase(state.getApiBase());
        input.setApiHost(state.getApiHost());
        input.setStreamHost(state.getStreamHost());
        input.setStreamPort(state.getStreamPort());
        input.setAppBuildRevision(state.getAppBuildRevision());
        input.setLastUiInfo(state.getLastUiInfo());
        input.setEffectiveDaemonState(state.getEffectiveDaemonState());
        input.setLatestPresentFps(state.getLatestPresentFps());
        input.setStartupBeganAtMs(state.getStartupBeganAtMs());
        input.setControlRetryCount(state.getControlRetryCount());
        input.setNowMs(state.getNowMs());
        input.setLastStatsLine(state.getLastStatsLine());
        input.setDaemonErrCompact(state.getDaemonErrCompact());
        return input;
    }
}
