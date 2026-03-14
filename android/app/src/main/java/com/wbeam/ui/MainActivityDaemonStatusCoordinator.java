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
        final boolean reachable;
        final boolean wasReachable;
        final String hostName;
        final String state;
        final String lastError;
        final boolean errorChanged;
        final String service;
        final JSONObject metrics;
        final boolean handshakeResolved;
        final boolean requiresTransportProbeNow;

        Input(
                boolean reachable,
                boolean wasReachable,
                String hostName,
                String state,
                String lastError,
                boolean errorChanged,
                String service,
                JSONObject metrics,
                boolean handshakeResolved,
                boolean requiresTransportProbeNow
        ) {
            this.reachable = reachable;
            this.wasReachable = wasReachable;
            this.hostName = hostName;
            this.state = state;
            this.lastError = lastError;
            this.errorChanged = errorChanged;
            this.service = service;
            this.metrics = metrics;
            this.handshakeResolved = handshakeResolved;
            this.requiresTransportProbeNow = requiresTransportProbeNow;
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
