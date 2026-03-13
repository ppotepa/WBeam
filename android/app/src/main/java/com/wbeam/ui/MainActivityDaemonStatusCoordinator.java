package com.wbeam.ui;

import org.json.JSONObject;

public final class MainActivityDaemonStatusCoordinator {
    public interface TransportProbeStarter {
        void start();
    }

    /**
     * Plain data carrier for daemon status coordinator input.
     */
    @SuppressWarnings("java:S1104")
    public static final class Input {
        public boolean reachable;
        public boolean wasReachable;
        public String hostName;
        public String state;
        public String lastError;
        public boolean errorChanged;
        public String service;
        public JSONObject metrics;
        public boolean handshakeResolved;
        public boolean requiresTransportProbeNow;
    }

    /**
     * Plain data carrier for daemon status coordinator output.
     */
    @SuppressWarnings("java:S1104")
    public static final class Output {
        public boolean handshakeResolved;
        public String hostStatsLine;
    }

    private MainActivityDaemonStatusCoordinator() {
    }

    public static Output process(
            Input input,
            TransportProbeStarter transportProbeStarter,
            StatusPollerUiUpdateCoordinator.TransitionHooks statusTransitionHooks
    ) {
        Output output = new Output();
        output.handshakeResolved = StatusPollerUiUpdateCoordinator.resolveHandshake(
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
        output.hostStatsLine = StatusPollerUiUpdateCoordinator.buildStatsLine(
                input.metrics,
                input.lastError
        );
        return output;
    }
}
