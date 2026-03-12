package com.wbeam.ui;

public final class StatusTransitionHooksFactory {
    @FunctionalInterface
    public interface HostConnectedHandler {
        void onConnected(String hostName);
    }

    @FunctionalInterface
    public interface ErrorChangedHandler {
        void onErrorChanged(String lastError);
    }

    @FunctionalInterface
    public interface StopLiveViewHandler {
        void onStopLiveView();
    }

    private StatusTransitionHooksFactory() {
    }

    public static StatusPollerUiUpdateCoordinator.TransitionHooks create(
            HostConnectedHandler hostConnectedHandler,
            ErrorChangedHandler errorChangedHandler,
            StopLiveViewHandler stopLiveViewHandler
    ) {
        return new StatusPollerUiUpdateCoordinator.TransitionHooks() {
            @Override
            public void onConnectedHost(String hostName) {
                hostConnectedHandler.onConnected(hostName);
            }

            @Override
            public void onHostErrorChanged(String lastError) {
                errorChangedHandler.onErrorChanged(lastError);
            }

            @Override
            public void onShouldStopLiveView() {
                stopLiveViewHandler.onStopLiveView();
            }
        };
    }
}
