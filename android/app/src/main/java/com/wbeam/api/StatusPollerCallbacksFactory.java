package com.wbeam.api;

import org.json.JSONObject;

@SuppressWarnings("java:S107") 
public final class StatusPollerCallbacksFactory {
    @SuppressWarnings("java:S107")
    public interface DaemonStatusUpdate {
        @SuppressWarnings("java:java:S107")
        void onDaemonStatusUpdate(
                boolean reachable,
                boolean wasReachable,
                String hostName,
                String state,
                long runId,
                String lastError,
                boolean errorChanged,
                long uptimeSec,
                String service,
                String buildRevision,
                JSONObject metrics
        );
    }

    @SuppressWarnings("java:S107") // Suppress false positives
    public interface DaemonOffline {
        void onDaemonOffline(boolean wasReachable, Exception e);
    }

    public interface Action {
        void run();
    }

    private StatusPollerCallbacksFactory() {
    }

    @SuppressWarnings("java:S107")
    public static StatusPoller.Callbacks create(
            final DaemonStatusUpdate daemonStatusUpdate,
            final DaemonOffline daemonOffline,
            final Action autoStartRequired,
            final Action autoStartFailed,
            final Action ensureDecoderRunning
    ) {
        return new StatusPoller.Callbacks() {
            @Override
            public void onDaemonStatusUpdate(
                    boolean reachable,
                    boolean wasReachable,
                    String hostName,
                    String state,
                    long runId,
                    String lastError,
                    boolean errorChanged,
                    long uptimeSec,
                    String service,
                    String buildRevision,
                    JSONObject metrics
            ) {
                daemonStatusUpdate.onDaemonStatusUpdate(
                        reachable,
                        wasReachable,
                        hostName,
                        state,
                        runId,
                        lastError,
                        errorChanged,
                        uptimeSec,
                        service,
                        buildRevision,
                        metrics
                );
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
