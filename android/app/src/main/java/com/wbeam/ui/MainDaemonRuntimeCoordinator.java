package com.wbeam.ui;

import android.os.SystemClock;

import com.wbeam.startup.TransportProbeCoordinator;
import com.wbeam.ui.state.MainDaemonState;
import com.wbeam.ui.state.MainUiState;

import org.json.JSONObject;

public final class MainDaemonRuntimeCoordinator {
    public interface BoolProvider {
        boolean get();
    }

    public interface ProbeStarter {
        void start(boolean requiresProbe);
    }

    public interface HostConnectedNotifier {
        void notify(String hostName);
    }

    public interface LineLogger {
        void log(String level, String line);
    }

    public interface UiMessageSink {
        void show(String message);
    }

    public interface UiStatusSink {
        void onStatus(String state, String info, long bps);
    }

    public interface UiTask {
        void run();
    }

    public interface StatsSink {
        void onStats(String line);
    }

    public interface PerfHudSink {
        void onMetrics(JSONObject metrics);
    }

    /**
     * Plain data carrier for daemon runtime coordinator status input.
     */
    public static final class StatusInput {
        private boolean reachable;
        private boolean wasReachable;
        private String hostName;
        private String state;
        private long runId;
        private String lastError;
        private boolean errorChanged;
        private long uptimeSec;
        private String service;
        private String buildRevision;
        private JSONObject metrics;

        StatusInput setReachable(boolean reachable) {
            this.reachable = reachable;
            return this;
        }

        StatusInput setWasReachable(boolean wasReachable) {
            this.wasReachable = wasReachable;
            return this;
        }

        StatusInput setHostName(String hostName) {
            this.hostName = hostName;
            return this;
        }

        StatusInput setState(String state) {
            this.state = state;
            return this;
        }

        StatusInput setRunId(long runId) {
            this.runId = runId;
            return this;
        }

        StatusInput setLastError(String lastError) {
            this.lastError = lastError;
            return this;
        }

        StatusInput setErrorChanged(boolean errorChanged) {
            this.errorChanged = errorChanged;
            return this;
        }

        StatusInput setUptimeSec(long uptimeSec) {
            this.uptimeSec = uptimeSec;
            return this;
        }

        StatusInput setService(String service) {
            this.service = service;
            return this;
        }

        StatusInput setBuildRevision(String buildRevision) {
            this.buildRevision = buildRevision;
            return this;
        }

        StatusInput setMetrics(JSONObject metrics) {
            this.metrics = metrics;
            return this;
        }
    }

    public static final class StatusContext {
        private MainDaemonState daemon;
        private MainUiState uiState;
        private BoolProvider requiresTransportProbeNowProvider;
        private HostConnectedNotifier hostConnectedNotifier;
        private LineLogger lineLogger;
        private UiTask stopLiveViewTask;
        private UiTask refreshUiTask;
        private StatsSink statsSink;
        private PerfHudSink perfHudSink;

        StatusContext setDaemon(MainDaemonState daemon) {
            this.daemon = daemon;
            return this;
        }

        StatusContext setUiState(MainUiState uiState) {
            this.uiState = uiState;
            return this;
        }

        StatusContext setRequiresTransportProbeNowProvider(
                BoolProvider requiresTransportProbeNowProvider
        ) {
            this.requiresTransportProbeNowProvider = requiresTransportProbeNowProvider;
            return this;
        }

        StatusContext setHostConnectedNotifier(HostConnectedNotifier hostConnectedNotifier) {
            this.hostConnectedNotifier = hostConnectedNotifier;
            return this;
        }

        StatusContext setLineLogger(LineLogger lineLogger) {
            this.lineLogger = lineLogger;
            return this;
        }

        StatusContext setStopLiveViewTask(UiTask stopLiveViewTask) {
            this.stopLiveViewTask = stopLiveViewTask;
            return this;
        }

        StatusContext setRefreshUiTask(UiTask refreshUiTask) {
            this.refreshUiTask = refreshUiTask;
            return this;
        }

        StatusContext setStatsSink(StatsSink statsSink) {
            this.statsSink = statsSink;
            return this;
        }

        StatusContext setPerfHudSink(PerfHudSink perfHudSink) {
            this.perfHudSink = perfHudSink;
            return this;
        }
    }

    public static final class OfflineContext {
        private MainDaemonState daemon;
        private MainUiState uiState;
        private TransportProbeCoordinator transportProbe;
        private String stateError;
        private String apiBase;
        private UiTask stopLiveViewTask;
        private UiTask updateActionButtonsTask;
        private UiTask updateHostHintTask;
        private UiTask updatePerfHudUnavailableTask;
        private UiTask refreshStatusTextTask;
        private UiTask updatePreflightOverlayTask;
        private UiStatusSink uiStatusSink;
        private LineLogger lineLogger;
        private UiMessageSink toastSink;

        OfflineContext setDaemon(MainDaemonState daemon) {
            this.daemon = daemon;
            return this;
        }

        OfflineContext setUiState(MainUiState uiState) {
            this.uiState = uiState;
            return this;
        }

        OfflineContext setTransportProbe(TransportProbeCoordinator transportProbe) {
            this.transportProbe = transportProbe;
            return this;
        }

        OfflineContext setStateError(String stateError) {
            this.stateError = stateError;
            return this;
        }

        OfflineContext setApiBase(String apiBase) {
            this.apiBase = apiBase;
            return this;
        }

        OfflineContext setStopLiveViewTask(UiTask stopLiveViewTask) {
            this.stopLiveViewTask = stopLiveViewTask;
            return this;
        }

        OfflineContext setUpdateActionButtonsTask(UiTask updateActionButtonsTask) {
            this.updateActionButtonsTask = updateActionButtonsTask;
            return this;
        }

        OfflineContext setUpdateHostHintTask(UiTask updateHostHintTask) {
            this.updateHostHintTask = updateHostHintTask;
            return this;
        }

        OfflineContext setUpdatePerfHudUnavailableTask(UiTask updatePerfHudUnavailableTask) {
            this.updatePerfHudUnavailableTask = updatePerfHudUnavailableTask;
            return this;
        }

        OfflineContext setRefreshStatusTextTask(UiTask refreshStatusTextTask) {
            this.refreshStatusTextTask = refreshStatusTextTask;
            return this;
        }

        OfflineContext setUpdatePreflightOverlayTask(UiTask updatePreflightOverlayTask) {
            this.updatePreflightOverlayTask = updatePreflightOverlayTask;
            return this;
        }

        OfflineContext setUiStatusSink(UiStatusSink uiStatusSink) {
            this.uiStatusSink = uiStatusSink;
            return this;
        }

        OfflineContext setLineLogger(LineLogger lineLogger) {
            this.lineLogger = lineLogger;
            return this;
        }

        OfflineContext setToastSink(UiMessageSink toastSink) {
            this.toastSink = toastSink;
            return this;
        }
    }

    private MainDaemonRuntimeCoordinator() {
    }

    public static void onStatusUpdate(
            StatusInput input,
            StatusContext context,
            ProbeStarter probeStarter
    ) {
        context.daemon.applySnapshot(
                input.reachable,
                input.hostName,
                input.state,
                input.lastError,
                input.runId,
                input.uptimeSec,
                input.service,
                input.buildRevision
        );
        MainActivityDaemonStatusCoordinator.Input daemonInput =
                new MainActivityDaemonStatusCoordinator.Input()
                        .setWasReachable(input.wasReachable)
                        .setHostName(input.hostName)
                        .setState(input.state)
                        .setLastError(input.lastError)
                        .setErrorChanged(input.errorChanged)
                        .setService(input.service)
                        .setMetrics(input.metrics)
                        .setHandshakeResolved(context.uiState.isHandshakeResolved())
                        .setRequiresTransportProbeNow(
                                context.requiresTransportProbeNowProvider.get()
                        );

        MainActivityDaemonStatusCoordinator.Output output =
                MainActivityDaemonStatusCoordinator.process(
                        daemonInput,
                        () -> probeStarter.start(
                                context.requiresTransportProbeNowProvider.get()
                        ),
                        StatusTransitionHooksFactory.create(
                                context.hostConnectedNotifier::notify,
                                changedLastError -> context.lineLogger.log(
                                        "E",
                                        "host last_error: " + changedLastError
                                ),
                                context.stopLiveViewTask::run
                        )
                );
        context.uiState.setHandshakeResolved(output.isHandshakeResolved());
        context.refreshUiTask.run();
        if (output.getHostStatsLine() != null) {
            context.statsSink.onStats(output.getHostStatsLine());
        }
        context.perfHudSink.onMetrics(input.metrics);
    }

    public static void onOffline(boolean wasReachable, Exception error, OfflineContext context) {
        HostOfflineFlowCoordinator.handle(
                wasReachable,
                error,
                context.stateError,
                context.apiBase,
                HostOfflineHooksFactory.create(
                        () -> {
                            context.daemon.markDisconnected();
                            context.stopLiveViewTask.run();
                            context.uiState.setHandshakeResolved(false);
                            context.transportProbe.markWaitingForControlLink();
                        },
                        reachableBeforeDrop -> {
                            context.updateActionButtonsTask.run();
                            context.updateHostHintTask.run();
                            context.updatePerfHudUnavailableTask.run();
                            resetStartupAfterDisconnect(context.uiState, reachableBeforeDrop);
                            context.updatePreflightOverlayTask.run();
                        },
                        context.refreshStatusTextTask::run,
                        context.uiStatusSink::onStatus,
                        line -> context.lineLogger.log("E", line),
                        context.toastSink::show
                )
        );
    }

    private static void resetStartupAfterDisconnect(MainUiState uiState, boolean wasReachable) {
        uiState.setPreflightComplete(false);
        uiState.setStartupDismissed(false);
        if (wasReachable) {
            uiState.setStartupBeganAtMs(SystemClock.elapsedRealtime());
            uiState.setControlRetryCount(0);
        }
    }
}
