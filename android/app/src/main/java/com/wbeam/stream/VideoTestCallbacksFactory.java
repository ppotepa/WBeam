package com.wbeam.stream;

import android.view.Surface;

public final class VideoTestCallbacksFactory {
    @FunctionalInterface
    public interface SurfaceProvider {
        Surface get();
    }

    @FunctionalInterface
    public interface DaemonStateProvider {
        String get();
    }

    @FunctionalInterface
    public interface BoolProvider {
        boolean get();
    }

    @FunctionalInterface
    public interface StatusHandler {
        void onStatus(String state, String info, long bps);
    }

    @FunctionalInterface
    public interface LineHandler {
        void onLine(String line);
    }

    @FunctionalInterface
    public interface MessageHandler {
        void onMessage(String message);
    }

    @FunctionalInterface
    public interface OverlayChangedHandler {
        void onChanged();
    }

    @FunctionalInterface
    public interface BoolHandler {
        void onChanged(boolean value);
    }

    @FunctionalInterface
    public interface ToastHandler {
        void show(String message, boolean longToast);
    }

    @FunctionalInterface
    public interface TestConfigProvider {
        VideoTestController.TestConfig get();
    }

    @FunctionalInterface
    public interface Action {
        void run();
    }

    private VideoTestCallbacksFactory() {
    }

    public static VideoTestController.Callbacks create(
            SurfaceProvider surfaceProvider,
            Action stopVideoPlayer,
            DaemonStateProvider daemonStateProvider,
            BoolProvider daemonReachableProvider,
            StatusHandler statusHandler,
            LineHandler statsLineHandler,
            MessageHandler infoLogger,
            MessageHandler warnLogger,
            MessageHandler errorLogger,
            OverlayChangedHandler overlayChangedHandler,
            BoolHandler liveLogVisibilityHandler,
            ToastHandler toastHandler,
            TestConfigProvider testConfigProvider
    ) {
        return new VideoTestController.Callbacks() {
            @Override public Surface getSurface() { return surfaceProvider.get(); }
            @Override public void stopVideoPlayer() { stopVideoPlayer.run(); }
            @Override public String getDaemonState() { return daemonStateProvider.get(); }
            @Override public boolean isDaemonReachable() { return daemonReachableProvider.get(); }
            @Override public void onStatus(String state, String info, long bps) {
                statusHandler.onStatus(state, info, bps);
            }
            @Override public void onStatsLine(String line) {
                statsLineHandler.onLine(line);
            }
            @Override public void logInfo(String msg) { infoLogger.onMessage(msg); }
            @Override public void logWarn(String msg) { warnLogger.onMessage(msg); }
            @Override public void logError(String msg) { errorLogger.onMessage(msg); }
            @Override public void onOverlayChanged() { overlayChangedHandler.onChanged(); }
            @Override public void setLiveLogVisible(boolean visible) {
                liveLogVisibilityHandler.onChanged(visible);
            }
            @Override public void showToast(String msg, boolean longT) {
                toastHandler.show(msg, longT);
            }
            @Override public VideoTestController.TestConfig getTestConfig() {
                return testConfigProvider.get();
            }
        };
    }
}
