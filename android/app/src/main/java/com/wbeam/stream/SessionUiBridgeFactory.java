package com.wbeam.stream;

import android.content.Context;

import com.wbeam.api.StatusPoller;

import org.json.JSONObject;

public final class SessionUiBridgeFactory {
    public interface UiStatusSink {
        void onStatus(String state, String info, long bps);
    }

    public interface WarningLogSink {
        void onWarning(String line);
    }

    public interface ApiFailureHandler {
        void onFailure(String prefix, boolean userAction, Exception e);
    }

    public interface PayloadProvider {
        JSONObject payload();
    }

    private SessionUiBridgeFactory() {
    }

    @SuppressWarnings("java:S107")
    public static SessionUiBridge create(
            Context context,
            StatusPoller statusPoller,
            UiStatusSink uiStatusSink,
            SessionUiBridge.DaemonStatusConsumer daemonStatusConsumer,
            Runnable ensureDecoderRunning,
            Runnable stopLiveView,
            WarningLogSink warningLogSink,
            ApiFailureHandler apiFailureHandler,
            PayloadProvider payloadProvider
    ) {
        return new SessionUiBridge(
                context,
                statusPoller,
                uiStatusSink::onStatus,
                daemonStatusConsumer,
                ensureDecoderRunning,
                stopLiveView,
                warningLogSink::onWarning,
                apiFailureHandler::onFailure,
                payloadProvider::payload
        );
    }
}
