package com.wbeam.stream;

import android.content.Context;
import android.widget.Toast;

import com.wbeam.api.StatusPoller;

import org.json.JSONObject;

/**
 * Bridges StreamSessionController callback contract with UI/domain delegates.
 */
public final class SessionUiBridge implements StreamSessionController.Callbacks {
    @FunctionalInterface
    public interface StatusUpdater {
        void update(String state, String info, long bps);
    }

    @FunctionalInterface
    public interface DaemonStatusConsumer {
        void accept(JSONObject status);
    }

    @FunctionalInterface
    public interface ApiFailureHandler {
        void onFailure(String prefix, boolean userAction, Exception e);
    }

    @FunctionalInterface
    public interface ConfigPayloadProvider {
        JSONObject get();
    }

    @FunctionalInterface
    public interface LiveLogWarnAppender {
        void append(String line);
    }

    private final Context context;
    private final StatusPoller statusPoller;
    private final StatusUpdater statusUpdater;
    private final DaemonStatusConsumer daemonStatusConsumer;
    private final Runnable ensureDecoderRunning;
    private final Runnable stopLiveView;
    private final LiveLogWarnAppender liveLogWarnAppender;
    private final ApiFailureHandler apiFailureHandler;
    private final ConfigPayloadProvider configPayloadProvider;

    @SuppressWarnings("java:S107")
    public SessionUiBridge(
            Context context,
            StatusPoller statusPoller,
            StatusUpdater statusUpdater,
            DaemonStatusConsumer daemonStatusConsumer,
            Runnable ensureDecoderRunning,
            Runnable stopLiveView,
            LiveLogWarnAppender liveLogWarnAppender,
            ApiFailureHandler apiFailureHandler,
            ConfigPayloadProvider configPayloadProvider
    ) {
        this.context = context;
        this.statusPoller = statusPoller;
        this.statusUpdater = statusUpdater;
        this.daemonStatusConsumer = daemonStatusConsumer;
        this.ensureDecoderRunning = ensureDecoderRunning;
        this.stopLiveView = stopLiveView;
        this.liveLogWarnAppender = liveLogWarnAppender;
        this.apiFailureHandler = apiFailureHandler;
        this.configPayloadProvider = configPayloadProvider;
    }

    @Override
    public void onStatus(String state, String info, long bps) {
        statusUpdater.update(state, info, bps);
    }

    @Override
    public void onDaemonStatusJson(JSONObject status) {
        daemonStatusConsumer.accept(status);
    }

    @Override
    public void ensureDecoderRunning() {
        ensureDecoderRunning.run();
    }

    @Override
    public void stopLiveView() {
        stopLiveView.run();
    }

    @Override
    public void showToast(String msg, boolean longToast) {
        Toast.makeText(
                context,
                msg,
                longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    public void appendLiveLogWarn(String msg) {
        liveLogWarnAppender.append(msg);
    }

    @Override
    public void handleApiFailure(String prefix, boolean userAction, Exception e) {
        apiFailureHandler.onFailure(prefix, userAction, e);
    }

    @Override
    public JSONObject buildConfigPayload() {
        return configPayloadProvider.get();
    }

    @Override
    public void suppressAutoStart(long durationMs) {
        if (durationMs <= 0) {
            statusPoller.clearAutoStartSuppression();
        } else {
            statusPoller.suppressAutoStart(durationMs);
        }
    }

    @Override
    public void recordAutoStartAttempt() {
        statusPoller.recordAutoStartAttempt();
    }

    @Override
    public void setAutoStartPending(boolean pending) {
        statusPoller.setAutoStartPending(pending);
    }
}
