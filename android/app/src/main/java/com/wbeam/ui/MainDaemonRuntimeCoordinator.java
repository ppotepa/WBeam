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
        boolean reachable;
        boolean wasReachable;
        String hostName;
        String state;
        long runId;
        String lastError;
        boolean errorChanged;
        long uptimeSec;
        String service;
        String buildRevision;
        JSONObject metrics;
    }

    public static final class StatusContext {
        MainDaemonState daemon;
        MainUiState uiState;
        BoolProvider requiresTransportProbeNowProvider;
        ProbeStarter probeStarter;
        HostConnectedNotifier hostConnectedNotifier;
        LineLogger lineLogger;
        UiTask stopLiveViewTask;
        UiTask refreshUiTask;
        StatsSink statsSink;
        PerfHudSink perfHudSink;
    }

    public static final class OfflineContext {
        MainDaemonState daemon;
        MainUiState uiState;
        TransportProbeCoordinator transportProbe;
        String stateError;
        String apiBase;
        UiTask stopLiveViewTask;
        UiTask updateActionButtonsTask;
        UiTask updateHostHintTask;
        UiTask updatePerfHudUnavailableTask;
        UiTask refreshStatusTextTask;
        UiTask updatePreflightOverlayTask;
        UiStatusSink uiStatusSink;
        LineLogger lineLogger;
        UiMessageSink toastSink;
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
        daemonInput.handshakeResolved = context.uiState.isHandshakeResolved();
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
