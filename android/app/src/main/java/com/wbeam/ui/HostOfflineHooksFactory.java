package com.wbeam.ui;

public final class HostOfflineHooksFactory {
    @FunctionalInterface
    public interface DisconnectedStateHandler {
        void apply();
    }

    @FunctionalInterface
    public interface OfflineUiHandler {
        void apply(boolean wasReachable);
    }

    @FunctionalInterface
    public interface StatusRefreshHandler {
        void refresh();
    }

    @FunctionalInterface
    public interface StatusUpdateHandler {
        void update(String state, String info, long bps);
    }

    @FunctionalInterface
    public interface ErrorLogHandler {
        void append(String line);
    }

    @FunctionalInterface
    public interface ToastHandler {
        void show(String message);
    }

    private HostOfflineHooksFactory() {
    }

    public static HostOfflineFlowCoordinator.Hooks create(
            DisconnectedStateHandler disconnectedStateHandler,
            OfflineUiHandler offlineUiHandler,
            StatusRefreshHandler statusRefreshHandler,
            StatusUpdateHandler statusUpdateHandler,
            ErrorLogHandler errorLogHandler,
            ToastHandler toastHandler
    ) {
        return new HostOfflineFlowCoordinator.Hooks() {
            @Override
            public void applyDisconnectedDaemonState() {
                disconnectedStateHandler.apply();
            }

            @Override
            public void updateOfflineUiState(boolean wasReachable) {
                offlineUiHandler.apply(wasReachable);
            }

            @Override
            public void refreshStatusText() {
                statusRefreshHandler.refresh();
            }

            @Override
            public void updateStatus(String state, String info, long bps) {
                statusUpdateHandler.update(state, info, bps);
            }

            @Override
            public void appendLiveLogError(String line) {
                errorLogHandler.append(line);
            }

            @Override
            public void showLongToast(String message) {
                toastHandler.show(message);
            }
        };
    }
}
