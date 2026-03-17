package com.wbeam.startup;

import android.os.Handler;
import android.os.SystemClock;
import android.view.View;

import com.wbeam.stream.VideoTestController;

import java.util.concurrent.ExecutorService;

public final class MainStartupCoordinator {
    public interface BuildMismatchProvider {
        boolean isBuildMismatch();
    }

    public interface EffectiveDaemonStateProvider {
        String getEffectiveDaemonState();
    }

    public interface OverlayChangedHandler {
        void onOverlayChanged();
    }

    public interface LineHandler {
        void onLine(String line);
    }

    public static final class Input {
        private View preflightOverlay;
        private StartupOverlayController startupOverlayController;
        private StartupOverlayViewRenderer.Views startupOverlayViews;
        private VideoTestController videoTestController;
        private int startupVideoTestHintColor;

        private TransportProbeCoordinator transportProbe;
        private ExecutorService ioExecutor;
        private Handler uiHandler;

        private boolean daemonReachable;
        private String daemonHostName;
        private String daemonService;
        private String daemonBuildRevision;
        private String daemonState;
        private String daemonLastError;
        private boolean handshakeResolved;

        private String apiImpl;
        private String apiBase;
        private String apiHost;
        private String streamHost;
        private int streamPort;
        private String appBuildRevision;

        private String lastUiInfo;
        private double latestPresentFps;
        private String lastStatsLine;
        private String daemonErrorCompact;
        private int preflightAnimTick;

        private long startupBeganAtMs;
        private int controlRetryCount;
        private boolean startupDismissed;
        private boolean preflightComplete;

        private BuildMismatchProvider buildMismatchProvider;
        private EffectiveDaemonStateProvider effectiveDaemonStateProvider;
        private OverlayChangedHandler overlayChangedHandler;
        private LineHandler infoLogHandler;
        private LineHandler warnLogHandler;

        public View getPreflightOverlay() {
            return preflightOverlay;
        }

        public void setPreflightOverlay(View preflightOverlay) {
            this.preflightOverlay = preflightOverlay;
        }

        public StartupOverlayController getStartupOverlayController() {
            return startupOverlayController;
        }

        public void setStartupOverlayController(StartupOverlayController startupOverlayController) {
            this.startupOverlayController = startupOverlayController;
        }

        public StartupOverlayViewRenderer.Views getStartupOverlayViews() {
            return startupOverlayViews;
        }

        public void setStartupOverlayViews(StartupOverlayViewRenderer.Views startupOverlayViews) {
            this.startupOverlayViews = startupOverlayViews;
        }

        public VideoTestController getVideoTestController() {
            return videoTestController;
        }

        public void setVideoTestController(VideoTestController videoTestController) {
            this.videoTestController = videoTestController;
        }

        public int getStartupVideoTestHintColor() {
            return startupVideoTestHintColor;
        }

        public void setStartupVideoTestHintColor(int startupVideoTestHintColor) {
            this.startupVideoTestHintColor = startupVideoTestHintColor;
        }

        public TransportProbeCoordinator getTransportProbe() {
            return transportProbe;
        }

        public void setTransportProbe(TransportProbeCoordinator transportProbe) {
            this.transportProbe = transportProbe;
        }

        public ExecutorService getIoExecutor() {
            return ioExecutor;
        }

        public void setIoExecutor(ExecutorService ioExecutor) {
            this.ioExecutor = ioExecutor;
        }

        public Handler getUiHandler() {
            return uiHandler;
        }

        public void setUiHandler(Handler uiHandler) {
            this.uiHandler = uiHandler;
        }

        public boolean isDaemonReachable() {
            return daemonReachable;
        }

        public void setDaemonReachable(boolean daemonReachable) {
            this.daemonReachable = daemonReachable;
        }

        public String getDaemonHostName() {
            return daemonHostName;
        }

        public void setDaemonHostName(String daemonHostName) {
            this.daemonHostName = daemonHostName;
        }

        public String getDaemonService() {
            return daemonService;
        }

        public void setDaemonService(String daemonService) {
            this.daemonService = daemonService;
        }

        public String getDaemonBuildRevision() {
            return daemonBuildRevision;
        }

        public void setDaemonBuildRevision(String daemonBuildRevision) {
            this.daemonBuildRevision = daemonBuildRevision;
        }

        public String getDaemonState() {
            return daemonState;
        }

        public void setDaemonState(String daemonState) {
            this.daemonState = daemonState;
        }

        public String getDaemonLastError() {
            return daemonLastError;
        }

        public void setDaemonLastError(String daemonLastError) {
            this.daemonLastError = daemonLastError;
        }

        public boolean isHandshakeResolved() {
            return handshakeResolved;
        }

        public void setHandshakeResolved(boolean handshakeResolved) {
            this.handshakeResolved = handshakeResolved;
        }

        public String getApiImpl() {
            return apiImpl;
        }

        public void setApiImpl(String apiImpl) {
            this.apiImpl = apiImpl;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public String getApiHost() {
            return apiHost;
        }

        public void setApiHost(String apiHost) {
            this.apiHost = apiHost;
        }

        public String getStreamHost() {
            return streamHost;
        }

        public void setStreamHost(String streamHost) {
            this.streamHost = streamHost;
        }

        public int getStreamPort() {
            return streamPort;
        }

        public void setStreamPort(int streamPort) {
            this.streamPort = streamPort;
        }

        public String getAppBuildRevision() {
            return appBuildRevision;
        }

        public void setAppBuildRevision(String appBuildRevision) {
            this.appBuildRevision = appBuildRevision;
        }

        public String getLastUiInfo() {
            return lastUiInfo;
        }

        public void setLastUiInfo(String lastUiInfo) {
            this.lastUiInfo = lastUiInfo;
        }

        public double getLatestPresentFps() {
            return latestPresentFps;
        }

        public void setLatestPresentFps(double latestPresentFps) {
            this.latestPresentFps = latestPresentFps;
        }

        public String getLastStatsLine() {
            return lastStatsLine;
        }

        public void setLastStatsLine(String lastStatsLine) {
            this.lastStatsLine = lastStatsLine;
        }

        public String getDaemonErrorCompact() {
            return daemonErrorCompact;
        }

        public void setDaemonErrorCompact(String daemonErrorCompact) {
            this.daemonErrorCompact = daemonErrorCompact;
        }

        public int getPreflightAnimTick() {
            return preflightAnimTick;
        }

        public void setPreflightAnimTick(int preflightAnimTick) {
            this.preflightAnimTick = preflightAnimTick;
        }

        public long getStartupBeganAtMs() {
            return startupBeganAtMs;
        }

        public void setStartupBeganAtMs(long startupBeganAtMs) {
            this.startupBeganAtMs = startupBeganAtMs;
        }

        public int getControlRetryCount() {
            return controlRetryCount;
        }

        public void setControlRetryCount(int controlRetryCount) {
            this.controlRetryCount = controlRetryCount;
        }

        public boolean isStartupDismissed() {
            return startupDismissed;
        }

        public void setStartupDismissed(boolean startupDismissed) {
            this.startupDismissed = startupDismissed;
        }

        public boolean isPreflightComplete() {
            return preflightComplete;
        }

        public void setPreflightComplete(boolean preflightComplete) {
            this.preflightComplete = preflightComplete;
        }

        public BuildMismatchProvider getBuildMismatchProvider() {
            return buildMismatchProvider;
        }

        public void setBuildMismatchProvider(BuildMismatchProvider buildMismatchProvider) {
            this.buildMismatchProvider = buildMismatchProvider;
        }

        public EffectiveDaemonStateProvider getEffectiveDaemonStateProvider() {
            return effectiveDaemonStateProvider;
        }

        public void setEffectiveDaemonStateProvider(EffectiveDaemonStateProvider effectiveDaemonStateProvider) {
            this.effectiveDaemonStateProvider = effectiveDaemonStateProvider;
        }

        public OverlayChangedHandler getOverlayChangedHandler() {
            return overlayChangedHandler;
        }

        public void setOverlayChangedHandler(OverlayChangedHandler overlayChangedHandler) {
            this.overlayChangedHandler = overlayChangedHandler;
        }

        public LineHandler getInfoLogHandler() {
            return infoLogHandler;
        }

        public void setInfoLogHandler(LineHandler infoLogHandler) {
            this.infoLogHandler = infoLogHandler;
        }

        public LineHandler getWarnLogHandler() {
            return warnLogHandler;
        }

        public void setWarnLogHandler(LineHandler warnLogHandler) {
            this.warnLogHandler = warnLogHandler;
        }
    }

    private MainStartupCoordinator() {
    }

    public static boolean requiresTransportProbeNow(
            TransportProbeCoordinator transportProbe,
            boolean daemonReachable,
            boolean handshakeResolved,
            String apiImpl,
            String apiHost
    ) {
        return TransportProbeRuntimeCoordinator.requiresProbe(
                transportProbe,
                daemonReachable,
                handshakeResolved,
                apiImpl,
                apiHost
        );
    }

    public static void maybeStartTransportProbeNow(
            TransportProbeCoordinator transportProbe,
            boolean requiresProbe,
            ExecutorService ioExecutor,
            Handler uiHandler,
            LineHandler infoLogHandler,
            LineHandler warnLogHandler,
            OverlayChangedHandler overlayChangedHandler
    ) {
        TransportProbeRuntimeCoordinator.maybeStartProbe(
                transportProbe,
                requiresProbe,
                ioExecutor,
                uiHandler,
                TransportProbeCallbacksFactory.create(
                        infoLogHandler::onLine,
                        warnLogHandler::onLine,
                        overlayChangedHandler::onOverlayChanged
                )
        );
    }

    public static StartupOverlayStateSync.StateValues updatePreflightOverlay(Input input) {
        StartupOverlayStateSync.StateValues current = new StartupOverlayStateSync.StateValues();
        current.startupBeganAtMs = input.getStartupBeganAtMs();
        current.controlRetryCount = input.getControlRetryCount();
        current.startupDismissed = input.isStartupDismissed();
        current.preflightComplete = input.isPreflightComplete();

        StartupOverlayCoordinator.State next = StartupOverlayCoordinator.update(
                StartupOverlayHookBuilder.create(
                        input.getPreflightOverlay(),
                        input.getStartupOverlayViews(),
                        input.getVideoTestController(),
                        input.getStartupVideoTestHintColor(),
                        StartupOverlayProbeHooksFactory.create(
                                () -> requiresTransportProbeNow(
                                        input.getTransportProbe(),
                                        input.isDaemonReachable(),
                                        input.isHandshakeResolved(),
                                        input.getApiImpl(),
                                        input.getApiHost()
                                ),
                                () -> {
                                    if (input.isDaemonReachable()
                                            && input.isHandshakeResolved()
                                            && !input.getBuildMismatchProvider().isBuildMismatch()) {
                                        maybeStartTransportProbeNow(
                                                input.getTransportProbe(),
                                                requiresTransportProbeNow(
                                                        input.getTransportProbe(),
                                                        input.isDaemonReachable(),
                                                        input.isHandshakeResolved(),
                                                        input.getApiImpl(),
                                                        input.getApiHost()
                                                ),
                                                input.getIoExecutor(),
                                                input.getUiHandler(),
                                                input.getInfoLogHandler(),
                                                input.getWarnLogHandler(),
                                                input.getOverlayChangedHandler()
                                        );
                                    }
                                }
                        ),
                        input.isDaemonReachable(),
                        input.getDaemonHostName(),
                        input.getDaemonService(),
                        input.getDaemonBuildRevision(),
                        input.getDaemonState(),
                        input.getDaemonLastError(),
                        input.isHandshakeResolved(),
                        input.getBuildMismatchProvider().isBuildMismatch(),
                        input.getTransportProbe(),
                        input.getApiImpl(),
                        input.getApiBase(),
                        input.getApiHost(),
                        input.getStreamHost(),
                        input.getStreamPort(),
                        input.getAppBuildRevision(),
                        input.getLastUiInfo(),
                        input.getEffectiveDaemonStateProvider().getEffectiveDaemonState(),
                        input.getLatestPresentFps(),
                        input.getStartupBeganAtMs(),
                        input.getControlRetryCount(),
                        SystemClock.elapsedRealtime(),
                        input.getLastStatsLine(),
                        input.getDaemonErrorCompact(),
                        input.getPreflightAnimTick(),
                        visible -> StartupOverlayControllerGuard.setVisible(
                                input.getStartupOverlayController(),
                                visible
                        ),
                        (delayMs, action) -> input.getUiHandler().postDelayed(action, delayMs)
                ),
                StartupOverlayStateSync.snapshot(current)
        );
        return StartupOverlayStateSync.fromState(next);
    }
}
