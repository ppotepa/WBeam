package com.wbeam.ui;

import org.json.JSONObject;

public final class StatusPollerUiUpdateCoordinator {
    public interface TransitionHooks {
        void onConnectedHost(String hostName);
        void onHostErrorChanged(String lastError);
        void onShouldStopLiveView();
    }

    private StatusPollerUiUpdateCoordinator() {
    }

    public static boolean resolveHandshake(boolean handshakeResolved, String service) {
        return handshakeResolved || !"-".equals(service);
    }

    public static void maybeStartTransportProbe(boolean requiresProbe, Runnable starter) {
        if (requiresProbe) {
            starter.run();
        }
    }

    public static void handleStatusTransition(
            boolean wasReachable,
            String hostName,
            boolean errorChanged,
            String lastError,
            boolean shouldStopLiveView,
            TransitionHooks hooks
    ) {
        if (!wasReachable) {
            hooks.onConnectedHost(hostName);
        }
        if (errorChanged && lastError != null && !lastError.isEmpty()) {
            hooks.onHostErrorChanged(lastError);
        }
        if (shouldStopLiveView) {
            hooks.onShouldStopLiveView();
        }
    }

    public static String buildStatsLine(JSONObject metrics, String lastError) {
        if (metrics == null) {
            return null;
        }
        return MainActivityStatusPresenter.buildHostStatsLine(
                metrics.optLong("frame_in", 0),
                metrics.optLong("frame_out", 0),
                metrics.optLong("drops", 0),
                metrics.optLong("reconnects", 0),
                metrics.optLong("bitrate_actual_bps", 0),
                lastError
        );
    }
}
