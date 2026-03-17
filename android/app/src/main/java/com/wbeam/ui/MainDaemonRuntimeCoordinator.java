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

        public boolean isReachable() {
            return reachable;
        }

        public void setReachable(boolean reachable) {
            this.reachable = reachable;
        }

        public boolean isWasReachable() {
            return wasReachable;
        }

        public void setWasReachable(boolean wasReachable) {
            this.wasReachable = wasReachable;
        }

        public String getHostName() {
            return hostName;
        }

        public void setHostName(String hostName) {
            this.hostName = hostName;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public long getRunId() {
            return runId;
        }

        public void setRunId(long runId) {
            this.runId = runId;
        }

        public String getLastError() {
            return lastError;
        }

        public void setLastError(String lastError) {
            this.lastError = lastError;
        }

        public boolean isErrorChanged() {
            return errorChanged;
        }

        public void setErrorChanged(boolean errorChanged) {
            this.errorChanged = errorChanged;
        }

        public long getUptimeSec() {
            return uptimeSec;
        }

        public void setUptimeSec(long uptimeSec) {
            this.uptimeSec = uptimeSec;
        }

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getBuildRevision() {
            return buildRevision;
        }

        public void setBuildRevision(String buildRevision) {
            this.buildRevision = buildRevision;
        }

        public JSONObject getMetrics() {
            return metrics;
        }

        public void setMetrics(JSONObject metrics) {
            this.metrics = metrics;
        }
    }

    public static final class StatusContext {
        private MainDaemonState daemon;
        private MainUiState uiState;
        private BoolProvider requiresTransportProbeNowProvider;
        private ProbeStarter probeStarter;
        private HostConnectedNotifier hostConnectedNotifier;
        private LineLogger lineLogger;
        private UiTask stopLiveViewTask;
        private UiTask refreshUiTask;
        private StatsSink statsSink;
        private PerfHudSink perfHudSink;

        public MainDaemonState getDaemon() {
            return daemon;
        }

        public void setDaemon(MainDaemonState daemon) {
            this.daemon = daemon;
        }

        public MainUiState getUiState() {
            return uiState;
        }

        public void setUiState(MainUiState uiState) {
            this.uiState = uiState;
        }

        public BoolProvider getRequiresTransportProbeNowProvider() {
            return requiresTransportProbeNowProvider;
        }

        public void setRequiresTransportProbeNowProvider(BoolProvider requiresTransportProbeNowProvider) {
            this.requiresTransportProbeNowProvider = requiresTransportProbeNowProvider;
        }

        public ProbeStarter getProbeStarter() {
            return probeStarter;
        }

        public void setProbeStarter(ProbeStarter probeStarter) {
            this.probeStarter = probeStarter;
        }

        public HostConnectedNotifier getHostConnectedNotifier() {
            return hostConnectedNotifier;
        }

        public void setHostConnectedNotifier(HostConnectedNotifier hostConnectedNotifier) {
            this.hostConnectedNotifier = hostConnectedNotifier;
        }

        public LineLogger getLineLogger() {
            return lineLogger;
        }

        public void setLineLogger(LineLogger lineLogger) {
            this.lineLogger = lineLogger;
        }

        public UiTask getStopLiveViewTask() {
            return stopLiveViewTask;
        }

        public void setStopLiveViewTask(UiTask stopLiveViewTask) {
            this.stopLiveViewTask = stopLiveViewTask;
        }

        public UiTask getRefreshUiTask() {
            return refreshUiTask;
        }

        public void setRefreshUiTask(UiTask refreshUiTask) {
            this.refreshUiTask = refreshUiTask;
        }

        public StatsSink getStatsSink() {
            return statsSink;
        }

        public void setStatsSink(StatsSink statsSink) {
            this.statsSink = statsSink;
        }

        public PerfHudSink getPerfHudSink() {
            return perfHudSink;
        }

        public void setPerfHudSink(PerfHudSink perfHudSink) {
            this.perfHudSink = perfHudSink;
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

        public MainDaemonState getDaemon() {
            return daemon;
        }

        public void setDaemon(MainDaemonState daemon) {
            this.daemon = daemon;
        }

        public MainUiState getUiState() {
            return uiState;
        }

        public void setUiState(MainUiState uiState) {
            this.uiState = uiState;
        }

        public TransportProbeCoordinator getTransportProbe() {
            return transportProbe;
        }

        public void setTransportProbe(TransportProbeCoordinator transportProbe) {
            this.transportProbe = transportProbe;
        }

        public String getStateError() {
            return stateError;
        }

        public void setStateError(String stateError) {
            this.stateError = stateError;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public UiTask getStopLiveViewTask() {
            return stopLiveViewTask;
        }

        public void setStopLiveViewTask(UiTask stopLiveViewTask) {
            this.stopLiveViewTask = stopLiveViewTask;
        }

        public UiTask getUpdateActionButtonsTask() {
            return updateActionButtonsTask;
        }

        public void setUpdateActionButtonsTask(UiTask updateActionButtonsTask) {
            this.updateActionButtonsTask = updateActionButtonsTask;
        }

        public UiTask getUpdateHostHintTask() {
            return updateHostHintTask;
        }

        public void setUpdateHostHintTask(UiTask updateHostHintTask) {
            this.updateHostHintTask = updateHostHintTask;
        }

        public UiTask getUpdatePerfHudUnavailableTask() {
            return updatePerfHudUnavailableTask;
        }

        public void setUpdatePerfHudUnavailableTask(UiTask updatePerfHudUnavailableTask) {
            this.updatePerfHudUnavailableTask = updatePerfHudUnavailableTask;
        }

        public UiTask getRefreshStatusTextTask() {
            return refreshStatusTextTask;
        }

        public void setRefreshStatusTextTask(UiTask refreshStatusTextTask) {
            this.refreshStatusTextTask = refreshStatusTextTask;
        }

        public UiTask getUpdatePreflightOverlayTask() {
            return updatePreflightOverlayTask;
        }

        public void setUpdatePreflightOverlayTask(UiTask updatePreflightOverlayTask) {
            this.updatePreflightOverlayTask = updatePreflightOverlayTask;
        }

        public UiStatusSink getUiStatusSink() {
            return uiStatusSink;
        }

        public void setUiStatusSink(UiStatusSink uiStatusSink) {
            this.uiStatusSink = uiStatusSink;
        }

        public LineLogger getLineLogger() {
            return lineLogger;
        }

        public void setLineLogger(LineLogger lineLogger) {
            this.lineLogger = lineLogger;
        }

        public UiMessageSink getToastSink() {
            return toastSink;
        }

        public void setToastSink(UiMessageSink toastSink) {
            this.toastSink = toastSink;
        }
    }

    private MainDaemonRuntimeCoordinator() {
    }

    public static void onStatusUpdate(StatusInput input, StatusContext context) {
        context.getDaemon().applySnapshot(
                input.isReachable(),
                input.getHostName(),
                input.getState(),
                input.getLastError(),
                input.getRunId(),
                input.getUptimeSec(),
                input.getService(),
                input.getBuildRevision()
        );
        MainActivityDaemonStatusCoordinator.Input daemonInput =
                new MainActivityDaemonStatusCoordinator.Input();
        daemonInput.reachable = input.isReachable();
        daemonInput.wasReachable = input.isWasReachable();
        daemonInput.hostName = input.getHostName();
        daemonInput.state = input.getState();
        daemonInput.lastError = input.getLastError();
        daemonInput.errorChanged = input.isErrorChanged();
        daemonInput.service = input.getService();
        daemonInput.metrics = input.getMetrics();
        daemonInput.handshakeResolved = context.uiState.handshakeResolved;
        daemonInput.requiresTransportProbeNow = context.requiresTransportProbeNowProvider.get();

        MainActivityDaemonStatusCoordinator.Output output =
                MainActivityDaemonStatusCoordinator.process(
                        daemonInput,
                        () -> context.getProbeStarter().start(
                                context.getRequiresTransportProbeNowProvider().get()
                        ),
                        StatusTransitionHooksFactory.create(
                                context.getHostConnectedNotifier()::notify,
                                changedLastError -> context.getLineLogger().log(
                                        "E",
                                        "host last_error: " + changedLastError
                                ),
                                context.getStopLiveViewTask()::run
                        )
                );
        context.getUiState().handshakeResolved = output.handshakeResolved;
        context.getRefreshUiTask().run();
        if (output.hostStatsLine != null) {
            context.getStatsSink().onStats(output.hostStatsLine);
        }
        context.getPerfHudSink().onMetrics(input.getMetrics());
    }

    public static void onOffline(boolean wasReachable, Exception error, OfflineContext context) {
        HostOfflineFlowCoordinator.handle(
                wasReachable,
                error,
                context.getStateError(),
                context.getApiBase(),
                HostOfflineHooksFactory.create(
                        () -> {
                            context.getDaemon().markDisconnected();
                            context.getStopLiveViewTask().run();
                            context.getUiState().handshakeResolved = false;
                            context.getTransportProbe().markWaitingForControlLink();
                        },
                        reachableBeforeDrop -> {
                            context.getUpdateActionButtonsTask().run();
                            context.getUpdateHostHintTask().run();
                            context.getUpdatePerfHudUnavailableTask().run();
                            resetStartupAfterDisconnect(context.getUiState(), reachableBeforeDrop);
                            context.getUpdatePreflightOverlayTask().run();
                        },
                        context.getRefreshStatusTextTask()::run,
                        context.getUiStatusSink()::onStatus,
                        line -> context.getLineLogger().log("E", line),
                        context.getToastSink()::show
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
