package com.wbeam.ui;

import android.content.Context;
import android.widget.Toast;

import android.util.Log;

@SuppressWarnings("java:S107")
public final class HostApiFailureNotifier {
    @FunctionalInterface
    public interface StatusSink {
        void onStatus(String state, String info, long bps);
    }

    @FunctionalInterface
    public interface LogSink {
        void onError(String line);
    }

    private HostApiFailureNotifier() {
    }

    public static void handle(
            Context context,
            String tag,
            String errorState,
            String prefix,
            boolean userAction,
            Exception e,
            String apiBase,
            StatusSink statusSink,
            LogSink liveLogSink
    ) {
        String reason = ErrorTextUtil.shortError(e);
        String line = prefix + ": " + reason;
        statusSink.onStatus(errorState, line, 0);
        liveLogSink.onError(line);
        Log.e(tag, line, e);
        if (userAction) {
            Toast.makeText(
                    context,
                    "Host API unreachable (" + reason
                            + "). Check USB tethering/LAN and host IP: " + apiBase,
                    Toast.LENGTH_LONG
            ).show();
        }
    }
}
