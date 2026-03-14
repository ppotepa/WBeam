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
        return new MainDaemonRuntimeCoordinator.StatusInput(
                reachable,
                wasReachable,
                hostName,
                state,
                runId,
                lastError,
                errorChanged,
                uptimeSec,
                service,
                buildRevision,
                metrics
        );
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
        return new MainDaemonRuntimeCoordinator.StatusContext(
                daemon,
                uiState,
                requiresTransportProbeNowProvider,
                probeStarter,
                hostConnectedNotifier,
                lineLogger,
                stopLiveViewTask,
                refreshUiTask,
                statsSink,
                perfHudSink
        );
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
        return new MainDaemonRuntimeCoordinator.OfflineContext(
                daemon,
                uiState,
                transportProbe,
                stateError,
                HostApiClient.API_BASE,
                stopLiveViewTask,
                updateActionButtonsTask,
                updateHostHintTask,
                updatePerfHudUnavailableTask,
                refreshStatusTextTask,
                updatePreflightOverlayTask,
                uiStatusSink,
                lineLogger,
                toastSink
        );
    }
}
