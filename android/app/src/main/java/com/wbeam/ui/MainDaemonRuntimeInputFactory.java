package com.wbeam.ui;

import com.wbeam.api.HostApiClient;
import com.wbeam.startup.TransportProbeCoordinator;
import com.wbeam.ui.state.MainDaemonState;
import com.wbeam.ui.state.MainUiState;

import org.json.JSONObject;

public final class MainDaemonRuntimeInputFactory {
    private MainDaemonRuntimeInputFactory() {
    }

    public static MainDaemonRuntimeCoordinator.StatusInput createStatusInput(
            boolean reachable,
            boolean wasReachable,
            String hostName,
            String state,
            long runId,
            String lastError,
            boolean errorChanged,
            long uptimeSec,
            String service,
            String buildRevision,
            JSONObject metrics
    ) {
        MainDaemonRuntimeCoordinator.StatusInput input = new MainDaemonRuntimeCoordinator.StatusInput();
        input.reachable = reachable;
        input.wasReachable = wasReachable;
        input.hostName = hostName;
        input.state = state;
        input.runId = runId;
        input.lastError = lastError;
        input.errorChanged = errorChanged;
        input.uptimeSec = uptimeSec;
        input.service = service;
        input.buildRevision = buildRevision;
        input.metrics = metrics;
        return input;
    }

    public static MainDaemonRuntimeCoordinator.StatusContext createStatusContext(
            MainDaemonState daemon,
            MainUiState uiState,
            MainDaemonRuntimeCoordinator.BoolProvider requiresTransportProbeNowProvider,
            MainDaemonRuntimeCoordinator.ProbeStarter probeStarter,
            MainDaemonRuntimeCoordinator.HostConnectedNotifier hostConnectedNotifier,
            MainDaemonRuntimeCoordinator.LineLogger lineLogger,
            MainDaemonRuntimeCoordinator.UiTask stopLiveViewTask,
            MainDaemonRuntimeCoordinator.UiTask refreshUiTask,
            MainDaemonRuntimeCoordinator.StatsSink statsSink,
            MainDaemonRuntimeCoordinator.PerfHudSink perfHudSink
    ) {
        MainDaemonRuntimeCoordinator.StatusContext context = new MainDaemonRuntimeCoordinator.StatusContext();
        context.daemon = daemon;
        context.uiState = uiState;
        context.requiresTransportProbeNowProvider = requiresTransportProbeNowProvider;
        context.probeStarter = probeStarter;
        context.hostConnectedNotifier = hostConnectedNotifier;
        context.lineLogger = lineLogger;
        context.stopLiveViewTask = stopLiveViewTask;
        context.refreshUiTask = refreshUiTask;
        context.statsSink = statsSink;
        context.perfHudSink = perfHudSink;
        return context;
    }

    public static MainDaemonRuntimeCoordinator.OfflineContext createOfflineContext(
            MainDaemonState daemon,
            MainUiState uiState,
            TransportProbeCoordinator transportProbe,
            String stateError,
            MainDaemonRuntimeCoordinator.UiTask stopLiveViewTask,
            MainDaemonRuntimeCoordinator.UiTask updateActionButtonsTask,
            MainDaemonRuntimeCoordinator.UiTask updateHostHintTask,
            MainDaemonRuntimeCoordinator.UiTask updatePerfHudUnavailableTask,
            MainDaemonRuntimeCoordinator.UiTask refreshStatusTextTask,
            MainDaemonRuntimeCoordinator.UiTask updatePreflightOverlayTask,
            MainDaemonRuntimeCoordinator.UiStatusSink uiStatusSink,
            MainDaemonRuntimeCoordinator.LineLogger lineLogger,
            MainDaemonRuntimeCoordinator.UiMessageSink toastSink
    ) {
        MainDaemonRuntimeCoordinator.OfflineContext context = new MainDaemonRuntimeCoordinator.OfflineContext();
        context.daemon = daemon;
        context.uiState = uiState;
        context.transportProbe = transportProbe;
        context.stateError = stateError;
        context.apiBase = HostApiClient.API_BASE;
        context.stopLiveViewTask = stopLiveViewTask;
        context.updateActionButtonsTask = updateActionButtonsTask;
        context.updateHostHintTask = updateHostHintTask;
        context.updatePerfHudUnavailableTask = updatePerfHudUnavailableTask;
        context.refreshStatusTextTask = refreshStatusTextTask;
        context.updatePreflightOverlayTask = updatePreflightOverlayTask;
        context.uiStatusSink = uiStatusSink;
        context.lineLogger = lineLogger;
        context.toastSink = toastSink;
        return context;
    }
}
