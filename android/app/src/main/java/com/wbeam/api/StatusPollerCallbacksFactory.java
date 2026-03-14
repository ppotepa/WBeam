package com.wbeam.api;

public final class StatusPollerCallbacksFactory {
    public interface DaemonStatusUpdate {
        void onDaemonStatusUpdate(StatusPoller.DaemonStatusSnapshot snapshot);
    }

    public interface DaemonOffline {
        void onDaemonOffline(boolean wasReachable, Exception e);
    }

    public interface Action {
        void run();
    }

    private StatusPollerCallbacksFactory() {
    }

    public static StatusPoller.Callbacks create(
            final DaemonStatusUpdate daemonStatusUpdate,
            final DaemonOffline daemonOffline,
            final Action autoStartRequired,
            final Action autoStartFailed,
            final Action ensureDecoderRunning
    ) {
        return new StatusPoller.Callbacks() {
            @Override
            public void onDaemonStatusUpdate(StatusPoller.DaemonStatusSnapshot snapshot) {
                daemonStatusUpdate.onDaemonStatusUpdate(snapshot);
            }

            @Override
            public void onDaemonOffline(boolean wasReachable, Exception e) {
                daemonOffline.onDaemonOffline(wasReachable, e);
            }

            @Override
            public void onAutoStartRequired() {
                autoStartRequired.run();
            }

            @Override
            public void onAutoStartFailed() {
                autoStartFailed.run();
            }

            @Override
            public void ensureDecoderRunning() {
                ensureDecoderRunning.run();
            }
        };
    }
}
