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
        public View preflightOverlay;
        public StartupOverlayController startupOverlayController;
        public StartupOverlayViewRenderer.Views startupOverlayViews;
        public VideoTestController videoTestController;
        public int startupVideoTestHintColor;

        public TransportProbeCoordinator transportProbe;
        public ExecutorService ioExecutor;
        public Handler uiHandler;

        public boolean daemonReachable;
        public String daemonHostName;
        public String daemonService;
        public String daemonBuildRevision;
        public String daemonState;
        public String daemonLastError;
        public boolean handshakeResolved;

        public String apiImpl;
        public String apiBase;
        public String apiHost;
        public String streamHost;
        public int streamPort;
        public String appBuildRevision;

        public String lastUiInfo;
        public double latestPresentFps;
        public String lastStatsLine;
        public String daemonErrorCompact;
        public int preflightAnimTick;

        public long startupBeganAtMs;
        public int controlRetryCount;
        public boolean startupDismissed;
        public boolean preflightComplete;

        public BuildMismatchProvider buildMismatchProvider;
        public EffectiveDaemonStateProvider effectiveDaemonStateProvider;
        public OverlayChangedHandler overlayChangedHandler;
        public LineHandler infoLogHandler;
        public LineHandler warnLogHandler;
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
        current.startupBeganAtMs = input.startupBeganAtMs;
        current.controlRetryCount = input.controlRetryCount;
        current.startupDismissed = input.startupDismissed;
        current.preflightComplete = input.preflightComplete;

        StartupOverlayCoordinator.State next = StartupOverlayCoordinator.update(
                StartupOverlayHookBuilder.create(
                        input.preflightOverlay,
                        input.startupOverlayViews,
                        input.videoTestController,
                        input.startupVideoTestHintColor,
                        StartupOverlayProbeHooksFactory.create(
                                () -> requiresTransportProbeNow(
                                        input.transportProbe,
                                        input.daemonReachable,
                                        input.handshakeResolved,
                                        input.apiImpl,
                                        input.apiHost
                                ),
                                () -> {
                                    if (input.daemonReachable
                                            && input.handshakeResolved
                                            && !input.buildMismatchProvider.isBuildMismatch()) {
                                        maybeStartTransportProbeNow(
                                                input.transportProbe,
                                                requiresTransportProbeNow(
                                                        input.transportProbe,
                                                        input.daemonReachable,
                                                        input.handshakeResolved,
                                                        input.apiImpl,
                                                        input.apiHost
                                                ),
                                                input.ioExecutor,
                                                input.uiHandler,
                                                input.infoLogHandler,
                                                input.warnLogHandler,
                                                input.overlayChangedHandler
                                        );
                                    }
                                }
                        ),
                        input.daemonReachable,
                        input.daemonHostName,
                        input.daemonService,
                        input.daemonBuildRevision,
                        input.daemonState,
                        input.daemonLastError,
                        input.handshakeResolved,
                        input.buildMismatchProvider.isBuildMismatch(),
                        input.transportProbe,
                        input.apiImpl,
                        input.apiBase,
                        input.apiHost,
                        input.streamHost,
                        input.streamPort,
                        input.appBuildRevision,
                        input.lastUiInfo,
                        input.effectiveDaemonStateProvider.getEffectiveDaemonState(),
                        input.latestPresentFps,
                        input.startupBeganAtMs,
                        input.controlRetryCount,
                        SystemClock.elapsedRealtime(),
                        input.lastStatsLine,
                        input.daemonErrorCompact,
                        input.preflightAnimTick,
                        visible -> StartupOverlayControllerGuard.setVisible(
                                input.startupOverlayController,
                                visible
                        ),
                        (delayMs, action) -> input.uiHandler.postDelayed(action, delayMs)
                ),
                StartupOverlayStateSync.snapshot(current)
        );
        return StartupOverlayStateSync.fromState(next);
    }
}
