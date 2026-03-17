package com.wbeam.startup;

import android.view.View;

import com.wbeam.stream.VideoTestController;

@SuppressWarnings("java:S107")
public final class StartupOverlayHookBuilder {
    private StartupOverlayHookBuilder() {
    }

    public static StartupOverlayCoordinator.Hooks create(
            View preflightOverlay,
            StartupOverlayViewRenderer.Views startupOverlayViews,
            VideoTestController videoTestController,
            int startupVideoTestHintColor,
            StartupOverlayHooksFactory.ProbeHooks probeHooks,
            boolean daemonReachable,
            String daemonHostName,
            String daemonService,
            String daemonBuildRevision,
            String daemonState,
            String daemonLastError,
            boolean handshakeResolved,
            boolean buildMismatch,
            TransportProbeCoordinator transportProbe,
            String apiImpl,
            String apiBase,
            String apiHost,
            String streamHost,
            int streamPort,
            String appBuildRevision,
            String lastUiInfo,
            String effectiveDaemonState,
            double latestPresentFps,
            long startupBeganAtMs,
            int controlRetryCount,
            long nowMs,
            String lastStatsLine,
            String daemonErrCompact,
            int preflightAnimTick,
            StartupOverlayHooksFactory.VisibilityHooks visibilityHooks,
            StartupOverlayHooksFactory.Scheduler scheduler
    ) {
        return StartupOverlayHooksFactory.create(
                preflightOverlay,
                startupOverlayViews,
                videoTestController,
                startupVideoTestHintColor,
                probeHooks,
                shouldProbe -> StartupOverlayInputFactory.fromRuntimeState(
                        daemonReachable,
                        daemonHostName,
                        daemonService,
                        daemonBuildRevision,
                        daemonState,
                        daemonLastError,
                        handshakeResolved,
                        buildMismatch,
                        shouldProbe,
                        transportProbe.isProbeOk(),
                        transportProbe.isProbeInFlight(),
                        transportProbe.getProbeInfo(),
                        apiImpl,
                        apiBase,
                        apiHost,
                        streamHost,
                        streamPort,
                        appBuildRevision,
                        lastUiInfo,
                        effectiveDaemonState,
                        latestPresentFps,
                        startupBeganAtMs,
                        controlRetryCount,
                        nowMs,
                        lastStatsLine,
                        daemonErrCompact
                ),
                model -> StartupOverlayViewRenderer.applyModel(
                        model,
                        preflightAnimTick,
                        startupOverlayViews
                ),
                visibilityHooks,
                scheduler
        );
    }
}
