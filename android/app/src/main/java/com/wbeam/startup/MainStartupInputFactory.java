package com.wbeam.startup;

import android.os.Handler;
import android.view.View;

import com.wbeam.stream.VideoTestController;

import java.util.concurrent.ExecutorService;

public final class MainStartupInputFactory {
    private MainStartupInputFactory() {
    }

    @SuppressWarnings("java:S107")
    public static MainStartupCoordinator.Input create(
            View preflightOverlay,
            StartupOverlayController startupOverlayController,
            StartupOverlayViewRenderer.Views startupOverlayViews,
            VideoTestController videoTestController,
            int startupVideoTestHintColor,
            TransportProbeCoordinator transportProbe,
            ExecutorService ioExecutor,
            Handler uiHandler,
            boolean daemonReachable,
            String daemonHostName,
            String daemonService,
            String daemonBuildRevision,
            String daemonState,
            String daemonLastError,
            boolean handshakeResolved,
            String apiImpl,
            String apiBase,
            String apiHost,
            String streamHost,
            int streamPort,
            String appBuildRevision,
            String lastUiInfo,
            double latestPresentFps,
            String lastStatsLine,
            String daemonErrorCompact,
            int preflightAnimTick,
            long startupBeganAtMs,
            int controlRetryCount,
            boolean startupDismissed,
            boolean preflightComplete,
            MainStartupCoordinator.BuildMismatchProvider buildMismatchProvider,
            MainStartupCoordinator.EffectiveDaemonStateProvider effectiveDaemonStateProvider,
            MainStartupCoordinator.OverlayChangedHandler overlayChangedHandler,
            MainStartupCoordinator.LineHandler infoLogHandler,
            MainStartupCoordinator.LineHandler warnLogHandler
    ) {
        MainStartupCoordinator.Input input = new MainStartupCoordinator.Input();
        input.preflightOverlay = preflightOverlay;
        input.startupOverlayController = startupOverlayController;
        input.startupOverlayViews = startupOverlayViews;
        input.videoTestController = videoTestController;
        input.startupVideoTestHintColor = startupVideoTestHintColor;
        input.transportProbe = transportProbe;
        input.ioExecutor = ioExecutor;
        input.uiHandler = uiHandler;
        input.daemonReachable = daemonReachable;
        input.daemonHostName = daemonHostName;
        input.daemonService = daemonService;
        input.daemonBuildRevision = daemonBuildRevision;
        input.daemonState = daemonState;
        input.daemonLastError = daemonLastError;
        input.handshakeResolved = handshakeResolved;
        input.apiImpl = apiImpl;
        input.apiBase = apiBase;
        input.apiHost = apiHost;
        input.streamHost = streamHost;
        input.streamPort = streamPort;
        input.appBuildRevision = appBuildRevision;
        input.lastUiInfo = lastUiInfo;
        input.latestPresentFps = latestPresentFps;
        input.lastStatsLine = lastStatsLine;
        input.daemonErrorCompact = daemonErrorCompact;
        input.preflightAnimTick = preflightAnimTick;
        input.startupBeganAtMs = startupBeganAtMs;
        input.controlRetryCount = controlRetryCount;
        input.startupDismissed = startupDismissed;
        input.preflightComplete = preflightComplete;
        input.buildMismatchProvider = buildMismatchProvider;
        input.effectiveDaemonStateProvider = effectiveDaemonStateProvider;
        input.overlayChangedHandler = overlayChangedHandler;
        input.infoLogHandler = infoLogHandler;
        input.warnLogHandler = warnLogHandler;
        return input;
    }
}
