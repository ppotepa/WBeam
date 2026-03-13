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
    @SuppressWarnings("java:S1104")
    public static final class StatusInput {
        public boolean reachable;
        public boolean wasReachable;
        public String hostName;
        public String state;
        public long runId;
        public String lastError;
        public boolean errorChanged;
        public long uptimeSec;
        public String service;
        public String buildRevision;
        public JSONObject metrics;
    }

    public static final class StatusContext {
        public MainDaemonState daemon;
        public MainUiState uiState;
        public BoolProvider requiresTransportProbeNowProvider;
        public ProbeStarter probeStarter;
        public HostConnectedNotifier hostConnectedNotifier;
        public LineLogger lineLogger;
        public UiTask stopLiveViewTask;
        public UiTask refreshUiTask;
        public StatsSink statsSink;
        public PerfHudSink perfHudSink;
    }

    public static final class OfflineContext {
        public MainDaemonState daemon;
        public MainUiState uiState;
        public TransportProbeCoordinator transportProbe;
        public String stateError;
        public String apiBase;
        public UiTask stopLiveViewTask;
        public UiTask updateActionButtonsTask;
        public UiTask updateHostHintTask;
        public UiTask updatePerfHudUnavailableTask;
        public UiTask refreshStatusTextTask;
        public UiTask updatePreflightOverlayTask;
        public UiStatusSink uiStatusSink;
        public LineLogger lineLogger;
        public UiMessageSink toastSink;
    }

    private MainDaemonRuntimeCoordinator() {
    }

    public static void onStatusUpdate(StatusInput input, StatusContext context) {
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
                new MainActivityDaemonStatusCoordinator.Input();
        daemonInput.reachable = input.reachable;
        daemonInput.wasReachable = input.wasReachable;
        daemonInput.hostName = input.hostName;
        daemonInput.state = input.state;
        daemonInput.lastError = input.lastError;
        daemonInput.errorChanged = input.errorChanged;
        daemonInput.service = input.service;
        daemonInput.metrics = input.metrics;
        daemonInput.handshakeResolved = context.uiState.handshakeResolved;
        daemonInput.requiresTransportProbeNow = context.requiresTransportProbeNowProvider.get();

        MainActivityDaemonStatusCoordinator.Output output =
                MainActivityDaemonStatusCoordinator.process(
                        daemonInput,
                        () -> context.probeStarter.start(
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
        context.uiState.handshakeResolved = output.handshakeResolved;
        context.refreshUiTask.run();
        if (output.hostStatsLine != null) {
            context.statsSink.onStats(output.hostStatsLine);
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
                            context.uiState.handshakeResolved = false;
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
        uiState.preflightComplete = false;
        uiState.startupDismissed = false;
        if (wasReachable) {
            uiState.startupBeganAtMs = SystemClock.elapsedRealtime();
            uiState.controlRetryCount = 0;
        }
    }
}
