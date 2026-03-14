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

    /**
     * Plain data carrier for startup coordinator input.
     */
    public static final class Input {
        View preflightOverlay;
        StartupOverlayController startupOverlayController;
        StartupOverlayViewRenderer.Views startupOverlayViews;
        VideoTestController videoTestController;
        int startupVideoTestHintColor;

        TransportProbeCoordinator transportProbe;
        ExecutorService ioExecutor;
        Handler uiHandler;

        boolean daemonReachable;
        String daemonHostName;
        String daemonService;
        String daemonBuildRevision;
        String daemonState;
        String daemonLastError;
        boolean handshakeResolved;

        String apiImpl;
        String apiBase;
        String apiHost;
        String streamHost;
        int streamPort;
        String appBuildRevision;

        String lastUiInfo;
        double latestPresentFps;
        String lastStatsLine;
        String daemonErrorCompact;
        int preflightAnimTick;

        long startupBeganAtMs;
        int controlRetryCount;
        boolean startupDismissed;
        boolean preflightComplete;

        BuildMismatchProvider buildMismatchProvider;
        EffectiveDaemonStateProvider effectiveDaemonStateProvider;
        OverlayChangedHandler overlayChangedHandler;
        LineHandler infoLogHandler;
        LineHandler warnLogHandler;
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
        current.setStartupBeganAtMs(input.startupBeganAtMs);
        current.setControlRetryCount(input.controlRetryCount);
        current.setStartupDismissed(input.startupDismissed);
        current.setPreflightComplete(input.preflightComplete);

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
