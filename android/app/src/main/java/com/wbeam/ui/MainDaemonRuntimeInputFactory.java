package com.wbeam.ui;

import com.wbeam.api.HostApiClient;
import com.wbeam.api.StatusPoller;
import com.wbeam.startup.TransportProbeCoordinator;
import com.wbeam.ui.state.MainDaemonState;
import com.wbeam.ui.state.MainUiState;

public final class MainDaemonRuntimeInputFactory {
    public static final class StatusContextArgs {
        private MainDaemonState daemon;
        private MainUiState uiState;
        private MainDaemonRuntimeCoordinator.BoolProvider requiresTransportProbeNowProvider;
        private MainDaemonRuntimeCoordinator.ProbeStarter probeStarter;
        private MainDaemonRuntimeCoordinator.HostConnectedNotifier hostConnectedNotifier;
        private MainDaemonRuntimeCoordinator.LineLogger lineLogger;
        private MainDaemonRuntimeCoordinator.UiTask stopLiveViewTask;
        private MainDaemonRuntimeCoordinator.UiTask refreshUiTask;
        private MainDaemonRuntimeCoordinator.StatsSink statsSink;
        private MainDaemonRuntimeCoordinator.PerfHudSink perfHudSink;

        public StatusContextArgs setDaemon(MainDaemonState daemon) {
            this.daemon = daemon;
            return this;
        }

        public StatusContextArgs setUiState(MainUiState uiState) {
            this.uiState = uiState;
            return this;
        }

        public StatusContextArgs setRequiresTransportProbeNowProvider(
                MainDaemonRuntimeCoordinator.BoolProvider requiresTransportProbeNowProvider
        ) {
            this.requiresTransportProbeNowProvider = requiresTransportProbeNowProvider;
            return this;
        }

        public StatusContextArgs setProbeStarter(
                MainDaemonRuntimeCoordinator.ProbeStarter probeStarter
        ) {
            this.probeStarter = probeStarter;
            return this;
        }

        public StatusContextArgs setHostConnectedNotifier(
                MainDaemonRuntimeCoordinator.HostConnectedNotifier hostConnectedNotifier
        ) {
            this.hostConnectedNotifier = hostConnectedNotifier;
            return this;
        }

        public StatusContextArgs setLineLogger(
                MainDaemonRuntimeCoordinator.LineLogger lineLogger
        ) {
            this.lineLogger = lineLogger;
            return this;
        }

        public StatusContextArgs setStopLiveViewTask(
                MainDaemonRuntimeCoordinator.UiTask stopLiveViewTask
        ) {
            this.stopLiveViewTask = stopLiveViewTask;
            return this;
        }

        public StatusContextArgs setRefreshUiTask(
                MainDaemonRuntimeCoordinator.UiTask refreshUiTask
        ) {
            this.refreshUiTask = refreshUiTask;
            return this;
        }

        public StatusContextArgs setStatsSink(MainDaemonRuntimeCoordinator.StatsSink statsSink) {
            this.statsSink = statsSink;
            return this;
        }

        public StatusContextArgs setPerfHudSink(
                MainDaemonRuntimeCoordinator.PerfHudSink perfHudSink
        ) {
            this.perfHudSink = perfHudSink;
            return this;
        }
    }

    public static final class OfflineContextArgs {
        private MainDaemonState daemon;
        private MainUiState uiState;
        private TransportProbeCoordinator transportProbe;
        private String stateError;
        private MainDaemonRuntimeCoordinator.UiTask stopLiveViewTask;
        private MainDaemonRuntimeCoordinator.UiTask updateActionButtonsTask;
        private MainDaemonRuntimeCoordinator.UiTask updateHostHintTask;
        private MainDaemonRuntimeCoordinator.UiTask updatePerfHudUnavailableTask;
        private MainDaemonRuntimeCoordinator.UiTask refreshStatusTextTask;
        private MainDaemonRuntimeCoordinator.UiTask updatePreflightOverlayTask;
        private MainDaemonRuntimeCoordinator.UiStatusSink uiStatusSink;
        private MainDaemonRuntimeCoordinator.LineLogger lineLogger;
        private MainDaemonRuntimeCoordinator.UiMessageSink toastSink;

        public OfflineContextArgs setDaemon(MainDaemonState daemon) {
            this.daemon = daemon;
            return this;
        }

        public OfflineContextArgs setUiState(MainUiState uiState) {
            this.uiState = uiState;
            return this;
        }

        public OfflineContextArgs setTransportProbe(TransportProbeCoordinator transportProbe) {
            this.transportProbe = transportProbe;
            return this;
        }

        public OfflineContextArgs setStateError(String stateError) {
            this.stateError = stateError;
            return this;
        }

        public OfflineContextArgs setStopLiveViewTask(
                MainDaemonRuntimeCoordinator.UiTask stopLiveViewTask
        ) {
            this.stopLiveViewTask = stopLiveViewTask;
            return this;
        }

        public OfflineContextArgs setUpdateActionButtonsTask(
                MainDaemonRuntimeCoordinator.UiTask updateActionButtonsTask
        ) {
            this.updateActionButtonsTask = updateActionButtonsTask;
            return this;
        }

        public OfflineContextArgs setUpdateHostHintTask(
                MainDaemonRuntimeCoordinator.UiTask updateHostHintTask
        ) {
            this.updateHostHintTask = updateHostHintTask;
            return this;
        }

        public OfflineContextArgs setUpdatePerfHudUnavailableTask(
                MainDaemonRuntimeCoordinator.UiTask updatePerfHudUnavailableTask
        ) {
            this.updatePerfHudUnavailableTask = updatePerfHudUnavailableTask;
            return this;
        }

        public OfflineContextArgs setRefreshStatusTextTask(
                MainDaemonRuntimeCoordinator.UiTask refreshStatusTextTask
        ) {
            this.refreshStatusTextTask = refreshStatusTextTask;
            return this;
        }

        public OfflineContextArgs setUpdatePreflightOverlayTask(
                MainDaemonRuntimeCoordinator.UiTask updatePreflightOverlayTask
        ) {
            this.updatePreflightOverlayTask = updatePreflightOverlayTask;
            return this;
        }

        public OfflineContextArgs setUiStatusSink(
                MainDaemonRuntimeCoordinator.UiStatusSink uiStatusSink
        ) {
            this.uiStatusSink = uiStatusSink;
            return this;
        }

        public OfflineContextArgs setLineLogger(
                MainDaemonRuntimeCoordinator.LineLogger lineLogger
        ) {
            this.lineLogger = lineLogger;
            return this;
        }

        public OfflineContextArgs setToastSink(
                MainDaemonRuntimeCoordinator.UiMessageSink toastSink
        ) {
            this.toastSink = toastSink;
            return this;
        }
    }

    private MainDaemonRuntimeInputFactory() {
    }

    public static MainDaemonRuntimeCoordinator.StatusInput createStatusInput(
            StatusPoller.DaemonStatusSnapshot snapshot
    ) {
        return new MainDaemonRuntimeCoordinator.StatusInput()
                .setReachable(snapshot.isReachable())
                .setWasReachable(snapshot.wasReachable())
                .setHostName(snapshot.getHostName())
                .setState(snapshot.getDaemonState())
                .setRunId(snapshot.getRunId())
                .setLastError(snapshot.getLastError())
                .setErrorChanged(snapshot.isErrorChanged())
                .setUptimeSec(snapshot.getUptimeSec())
                .setService(snapshot.getService())
                .setBuildRevision(snapshot.getBuildRevision())
                .setMetrics(snapshot.getMetrics());
    }

    public static MainDaemonRuntimeCoordinator.StatusContext createStatusContext(
            StatusContextArgs args
    ) {
        return new MainDaemonRuntimeCoordinator.StatusContext()
                .setDaemon(args.daemon)
                .setUiState(args.uiState)
                .setRequiresTransportProbeNowProvider(args.requiresTransportProbeNowProvider)
                .setProbeStarter(args.probeStarter)
                .setHostConnectedNotifier(args.hostConnectedNotifier)
                .setLineLogger(args.lineLogger)
                .setStopLiveViewTask(args.stopLiveViewTask)
                .setRefreshUiTask(args.refreshUiTask)
                .setStatsSink(args.statsSink)
                .setPerfHudSink(args.perfHudSink);
    }

    public static MainDaemonRuntimeCoordinator.OfflineContext createOfflineContext(
            OfflineContextArgs args
    ) {
        return new MainDaemonRuntimeCoordinator.OfflineContext()
                .setDaemon(args.daemon)
                .setUiState(args.uiState)
                .setTransportProbe(args.transportProbe)
                .setStateError(args.stateError)
                .setApiBase(HostApiClient.API_BASE)
                .setStopLiveViewTask(args.stopLiveViewTask)
                .setUpdateActionButtonsTask(args.updateActionButtonsTask)
                .setUpdateHostHintTask(args.updateHostHintTask)
                .setUpdatePerfHudUnavailableTask(args.updatePerfHudUnavailableTask)
                .setRefreshStatusTextTask(args.refreshStatusTextTask)
                .setUpdatePreflightOverlayTask(args.updatePreflightOverlayTask)
                .setUiStatusSink(args.uiStatusSink)
                .setLineLogger(args.lineLogger)
                .setToastSink(args.toastSink);
    }
}
