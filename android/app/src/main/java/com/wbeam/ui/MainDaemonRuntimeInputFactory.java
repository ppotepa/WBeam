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
        MainDaemonRuntimeCoordinator.StatusInput input =
                new MainDaemonRuntimeCoordinator.StatusInput();
        input.setReachable(reachable);
        input.setWasReachable(wasReachable);
        input.setHostName(hostName);
        input.setState(state);
        input.setRunId(runId);
        input.setLastError(lastError);
        input.setErrorChanged(errorChanged);
        input.setUptimeSec(uptimeSec);
        input.setService(service);
        input.setBuildRevision(buildRevision);
        input.setMetrics(metrics);
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
        MainDaemonRuntimeCoordinator.StatusContext context =
                new MainDaemonRuntimeCoordinator.StatusContext();
        context.setDaemon(daemon);
        context.setUiState(uiState);
        context.setRequiresTransportProbeNowProvider(requiresTransportProbeNowProvider);
        context.setProbeStarter(probeStarter);
        context.setHostConnectedNotifier(hostConnectedNotifier);
        context.setLineLogger(lineLogger);
        context.setStopLiveViewTask(stopLiveViewTask);
        context.setRefreshUiTask(refreshUiTask);
        context.setStatsSink(statsSink);
        context.setPerfHudSink(perfHudSink);
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
        MainDaemonRuntimeCoordinator.OfflineContext context =
                new MainDaemonRuntimeCoordinator.OfflineContext();
        context.setDaemon(daemon);
        context.setUiState(uiState);
        context.setTransportProbe(transportProbe);
        context.setStateError(stateError);
        context.setApiBase(HostApiClient.API_BASE);
        context.setStopLiveViewTask(stopLiveViewTask);
        context.setUpdateActionButtonsTask(updateActionButtonsTask);
        context.setUpdateHostHintTask(updateHostHintTask);
        context.setUpdatePerfHudUnavailableTask(updatePerfHudUnavailableTask);
        context.setRefreshStatusTextTask(refreshStatusTextTask);
        context.setUpdatePreflightOverlayTask(updatePreflightOverlayTask);
        context.setUiStatusSink(uiStatusSink);
        context.setLineLogger(lineLogger);
        context.setToastSink(toastSink);
        return context;
    }
}
