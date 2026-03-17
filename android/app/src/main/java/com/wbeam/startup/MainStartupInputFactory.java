package com.wbeam.startup;

import android.os.Handler;
import android.view.View;

import com.wbeam.stream.VideoTestController;

import java.util.concurrent.ExecutorService;

@SuppressWarnings("java:S107")
public final class MainStartupInputFactory {
    private MainStartupInputFactory() {
    }

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
        input.setPreflightOverlay(preflightOverlay);
        input.setStartupOverlayController(startupOverlayController);
        input.setStartupOverlayViews(startupOverlayViews);
        input.setVideoTestController(videoTestController);
        input.setStartupVideoTestHintColor(startupVideoTestHintColor);
        input.setTransportProbe(transportProbe);
        input.setIoExecutor(ioExecutor);
        input.setUiHandler(uiHandler);
        input.setDaemonReachable(daemonReachable);
        input.setDaemonHostName(daemonHostName);
        input.setDaemonService(daemonService);
        input.setDaemonBuildRevision(daemonBuildRevision);
        input.setDaemonState(daemonState);
        input.setDaemonLastError(daemonLastError);
        input.setHandshakeResolved(handshakeResolved);
        input.setApiImpl(apiImpl);
        input.setApiBase(apiBase);
        input.setApiHost(apiHost);
        input.setStreamHost(streamHost);
        input.setStreamPort(streamPort);
        input.setAppBuildRevision(appBuildRevision);
        input.setLastUiInfo(lastUiInfo);
        input.setLatestPresentFps(latestPresentFps);
        input.setLastStatsLine(lastStatsLine);
        input.setDaemonErrorCompact(daemonErrorCompact);
        input.setPreflightAnimTick(preflightAnimTick);
        input.setStartupBeganAtMs(startupBeganAtMs);
        input.setControlRetryCount(controlRetryCount);
        input.setStartupDismissed(startupDismissed);
        input.setPreflightComplete(preflightComplete);
        input.setBuildMismatchProvider(buildMismatchProvider);
        input.setEffectiveDaemonStateProvider(effectiveDaemonStateProvider);
        input.setOverlayChangedHandler(overlayChangedHandler);
        input.setInfoLogHandler(infoLogHandler);
        input.setWarnLogHandler(warnLogHandler);
        return input;
    }
}
