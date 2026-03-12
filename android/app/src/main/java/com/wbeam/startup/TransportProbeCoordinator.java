package com.wbeam.startup;

import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import com.wbeam.api.HostApiClient;

import java.util.concurrent.ExecutorService;

/**
 * Owns transport-probe state and retry policy for legacy remote API paths.
 */
public final class TransportProbeCoordinator {
    private boolean probeOk = false;
    private boolean probeInFlight = false;
    private long probeRetryAfterMs = 0L;
    private String probeInfo = "not started";

    public interface Callbacks {
        String shortError(Exception e);

        void onProbeLogInfo(String msg);

        void onProbeLogWarn(String msg);

        void onProbeStateChanged();
    }

    public boolean isProbeOk() {
        return probeOk;
    }

    public boolean isProbeInFlight() {
        return probeInFlight;
    }

    public String getProbeInfo() {
        return probeInfo;
    }

    public void markWaitingForControlLink() {
        probeOk = false;
        probeInFlight = false;
        probeRetryAfterMs = 0L;
        probeInfo = "waiting for control link";
    }

    public boolean requiresProbe(
            boolean daemonReachable,
            boolean handshakeResolved,
            String apiImpl,
            String apiHost
    ) {
        if (!daemonReachable || !handshakeResolved) {
            return false;
        }
        if ("local".equalsIgnoreCase(apiImpl)) {
            return false;
        }
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return false;
        }
        return !isLoopbackHost(apiHost);
    }

    public void maybeStartProbe(
            boolean shouldProbe,
            ExecutorService ioExecutor,
            Handler uiHandler,
            Callbacks callbacks
    ) {
        if (!shouldProbe) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (probeOk || probeInFlight || now < probeRetryAfterMs) {
            return;
        }

        probeInFlight = true;
        probeInfo = "starting";

        ioExecutor.execute(() -> {
            try {
                long elapsed = HostApiClient.runTransportProbeMs(1);
                uiHandler.post(() -> {
                    probeOk = true;
                    probeInFlight = false;
                    probeRetryAfterMs = 0L;
                    probeInfo = "1MB in " + elapsed + "ms";
                    callbacks.onProbeLogInfo("transport probe OK: " + probeInfo);
                    callbacks.onProbeStateChanged();
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    probeOk = false;
                    probeInFlight = false;
                    probeRetryAfterMs = SystemClock.elapsedRealtime() + 4_000L;
                    probeInfo = callbacks.shortError(e);
                    callbacks.onProbeLogWarn("transport probe failed: " + probeInfo);
                    callbacks.onProbeStateChanged();
                });
            }
        });
    }

    private boolean isLoopbackHost(String host) {
        if (host == null) {
            return true;
        }
        String trimmed = host.trim();
        return trimmed.isEmpty()
                || "127.0.0.1".equals(trimmed)
                || "localhost".equalsIgnoreCase(trimmed);
    }
}
