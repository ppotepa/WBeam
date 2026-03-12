package com.wbeam.startup;

import android.os.Handler;

import java.util.concurrent.ExecutorService;

public final class TransportProbeRuntimeCoordinator {
    private TransportProbeRuntimeCoordinator() {
    }

    public static boolean requiresProbe(
            TransportProbeCoordinator transportProbe,
            boolean daemonReachable,
            boolean handshakeResolved,
            String apiImpl,
            String apiHost
    ) {
        return transportProbe.requiresProbe(
                daemonReachable,
                handshakeResolved,
                apiImpl,
                apiHost
        );
    }

    public static void maybeStartProbe(
            TransportProbeCoordinator transportProbe,
            boolean requiresProbe,
            ExecutorService ioExecutor,
            Handler uiHandler,
            TransportProbeCoordinator.Callbacks callbacks
    ) {
        transportProbe.maybeStartProbe(
                requiresProbe,
                ioExecutor,
                uiHandler,
                callbacks
        );
    }
}
