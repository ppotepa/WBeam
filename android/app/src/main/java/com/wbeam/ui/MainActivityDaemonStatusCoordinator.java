package com.wbeam.ui;

import org.json.JSONObject;

public final class MainActivityDaemonStatusCoordinator {
    public interface TransportProbeStarter {
        void start();
    }

    /**
     * Plain data carrier for daemon status coordinator input.
     */
    public static final class Input {
        private boolean wasReachable;
        private String hostName;
        private String state;
        private String lastError;
        private boolean errorChanged;
        private String service;
        private JSONObject metrics;
        private boolean handshakeResolved;
        private boolean requiresTransportProbeNow;

        Input setWasReachable(boolean wasReachable) {
            this.wasReachable = wasReachable;
            return this;
        }

        Input setHostName(String hostName) {
            this.hostName = hostName;
            return this;
        }

        Input setState(String state) {
            this.state = state;
            return this;
        }

        Input setLastError(String lastError) {
            this.lastError = lastError;
            return this;
        }

        Input setErrorChanged(boolean errorChanged) {
            this.errorChanged = errorChanged;
            return this;
        }

        Input setService(String service) {
            this.service = service;
            return this;
        }

        Input setMetrics(JSONObject metrics) {
            this.metrics = metrics;
            return this;
        }

        Input setHandshakeResolved(boolean handshakeResolved) {
            this.handshakeResolved = handshakeResolved;
            return this;
        }

        Input setRequiresTransportProbeNow(boolean requiresTransportProbeNow) {
            this.requiresTransportProbeNow = requiresTransportProbeNow;
            return this;
        }
    }

    /**
     * Plain data carrier for daemon status coordinator output.
     */
    public static final class Output {
        private final boolean handshakeResolved;
        private final String hostStatsLine;

        Output(boolean handshakeResolved, String hostStatsLine) {
            this.handshakeResolved = handshakeResolved;
            this.hostStatsLine = hostStatsLine;
        }

        public boolean isHandshakeResolved() {
            return handshakeResolved;
        }

        public String getHostStatsLine() {
            return hostStatsLine;
        }
    }

    private MainActivityDaemonStatusCoordinator() {
    }

    public static Output process(
            Input input,
            TransportProbeStarter transportProbeStarter,
            StatusPollerUiUpdateCoordinator.TransitionHooks statusTransitionHooks
    ) {
        boolean handshakeResolved = StatusPollerUiUpdateCoordinator.resolveHandshake(
                input.handshakeResolved,
                input.service
        );
        StatusPollerUiUpdateCoordinator.maybeStartTransportProbe(
                input.requiresTransportProbeNow,
                transportProbeStarter::start
        );
        StatusPollerUiUpdateCoordinator.handleStatusTransition(
                input.wasReachable,
                input.hostName,
                input.errorChanged,
                input.lastError,
                StatusPollerUiUpdateCoordinator.shouldStopLiveViewForDaemonState(input.state),
                statusTransitionHooks
        );
        String hostStatsLine = StatusPollerUiUpdateCoordinator.buildStatsLine(
                input.metrics,
                input.lastError
        );
        return new Output(handshakeResolved, hostStatsLine);
    }
}
