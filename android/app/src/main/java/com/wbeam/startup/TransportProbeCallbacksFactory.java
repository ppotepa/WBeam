package com.wbeam.startup;

import com.wbeam.ui.ErrorTextUtil;

public final class TransportProbeCallbacksFactory {
    public interface MessageSink {
        void onMessage(String message);
    }

    public interface StateSink {
        void onStateChanged();
    }

    private TransportProbeCallbacksFactory() {
    }

    public static TransportProbeCoordinator.Callbacks create(
            final MessageSink infoSink,
            final MessageSink warnSink,
            final StateSink stateSink
    ) {
        return new TransportProbeCoordinator.Callbacks() {
            @Override
            public String shortError(Exception e) {
                return ErrorTextUtil.shortError(e);
            }

            @Override
            public void onProbeLogInfo(String msg) {
                infoSink.onMessage(msg);
            }

            @Override
            public void onProbeLogWarn(String msg) {
                warnSink.onMessage(msg);
            }

            @Override
            public void onProbeStateChanged() {
                stateSink.onStateChanged();
            }
        };
    }
}
