package com.wbeam.ui;

import org.json.JSONObject;

public final class MainActivityDaemonStatusCoordinator {
    public interface TransportProbeStarter {
        void start();
    }

    public static final class Input {
        private boolean reachable;
        private boolean wasReachable;
        private String hostName;
        private String state;
        private String lastError;
        private boolean errorChanged;
        private String service;
        private JSONObject metrics;
        private boolean handshakeResolved;
        private boolean requiresTransportProbeNow;

        public boolean isReachable() {
            return reachable;
        }

        public void setReachable(boolean reachable) {
            this.reachable = reachable;
        }

        public boolean isWasReachable() {
            return wasReachable;
        }

        public void setWasReachable(boolean wasReachable) {
            this.wasReachable = wasReachable;
        }

        public String getHostName() {
            return hostName;
        }

        public void setHostName(String hostName) {
            this.hostName = hostName;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public String getLastError() {
            return lastError;
        }

        public void setLastError(String lastError) {
            this.lastError = lastError;
        }

        public boolean isErrorChanged() {
            return errorChanged;
        }

        public void setErrorChanged(boolean errorChanged) {
            this.errorChanged = errorChanged;
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public JSONObject getMetrics() {
            return metrics;
        }

        public void setMetrics(JSONObject metrics) {
            this.metrics = metrics;
        }

        public boolean isHandshakeResolved() {
            return handshakeResolved;
        }

        public void setHandshakeResolved(boolean handshakeResolved) {
            this.handshakeResolved = handshakeResolved;
        }

        public boolean isRequiresTransportProbeNow() {
            return requiresTransportProbeNow;
        }

        public void setRequiresTransportProbeNow(boolean requiresTransportProbeNow) {
            this.requiresTransportProbeNow = requiresTransportProbeNow;
        }
    }

    public static final class Output {
        private boolean handshakeResolved;
        private String hostStatsLine;

        public boolean isHandshakeResolved() {
            return handshakeResolved;
        }

        public void setHandshakeResolved(boolean handshakeResolved) {
            this.handshakeResolved = handshakeResolved;
        }

        public String getHostStatsLine() {
            return hostStatsLine;
        }

        public void setHostStatsLine(String hostStatsLine) {
            this.hostStatsLine = hostStatsLine;
        }
    }

    private MainActivityDaemonStatusCoordinator() {
    }

    public static Output process(
            Input input,
            TransportProbeStarter transportProbeStarter,
            StatusPollerUiUpdateCoordinator.TransitionHooks statusTransitionHooks
    ) {
        Output output = new Output();
        output.setHandshakeResolved(StatusPollerUiUpdateCoordinator.resolveHandshake(
                input.isHandshakeResolved(),
                input.getService()
        ));
        StatusPollerUiUpdateCoordinator.maybeStartTransportProbe(
                input.isRequiresTransportProbeNow(),
                transportProbeStarter::start
        );
        StatusPollerUiUpdateCoordinator.handleStatusTransition(
                input.isWasReachable(),
                input.getHostName(),
                input.isErrorChanged(),
                input.getLastError(),
                StatusPollerUiUpdateCoordinator.shouldStopLiveViewForDaemonState(input.getState()),
                statusTransitionHooks
        );
        output.setHostStatsLine(
                StatusPollerUiUpdateCoordinator.buildStatsLine(input.getMetrics(), input.getLastError())
        );
        return output;
    }
}
