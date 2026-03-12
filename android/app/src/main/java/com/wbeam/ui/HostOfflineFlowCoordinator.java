package com.wbeam.ui;

public final class HostOfflineFlowCoordinator {
    public interface Hooks {
        void applyDisconnectedDaemonState();
        void updateOfflineUiState(boolean wasReachable);
        void refreshStatusText();
        void updateStatus(String state, String info, long bps);
        void appendLiveLogError(String line);
        void showLongToast(String message);
    }

    private HostOfflineFlowCoordinator() {
    }

    public static void handle(
            boolean wasReachable,
            Exception e,
            String errorState,
            String apiBase,
            Hooks hooks
    ) {
        hooks.applyDisconnectedDaemonState();
        hooks.updateOfflineUiState(wasReachable);
        if (!wasReachable) {
            hooks.refreshStatusText();
            return;
        }
        String shortError = ErrorTextUtil.shortError(e);
        hooks.updateStatus(errorState, "Host API offline: " + shortError, 0);
        hooks.appendLiveLogError("daemon poll failed: " + shortError + " | api=" + apiBase);
        hooks.showLongToast(
                "Host API unreachable (" + shortError
                        + "). Check USB tethering/LAN and host IP: " + apiBase
        );
    }
}
