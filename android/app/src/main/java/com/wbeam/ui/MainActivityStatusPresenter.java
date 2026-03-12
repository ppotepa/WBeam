package com.wbeam.ui;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

public final class MainActivityStatusPresenter {
    public static final String DEFAULT_STATS_LINE =
            "fps in/out: - | drops: - | late: - | q(t/d/r): -/-/- | reconnects: -";

    private MainActivityStatusPresenter() {
    }

    public static String normalizeState(String state, String idleState) {
        return state == null ? idleState : state;
    }

    public static String normalizeInfo(String info) {
        return info == null ? "-" : info;
    }

    public static String normalizeStatsLine(String line) {
        return line == null || line.trim().isEmpty() ? DEFAULT_STATS_LINE : line;
    }

    public static void renderStatus(
            TextView statusText,
            TextView detailText,
            TextView bpsText,
            View statusLed,
            String state,
            String info,
            long bps,
            boolean daemonReachable,
            String daemonHostName,
            String daemonStateUi,
            String streamingState,
            String connectingState
    ) {
        int color = StatusColorResolver.ledColorForState(state, streamingState, connectingState);
        if (statusText != null) {
            statusText.setText(state.toUpperCase(Locale.US));
        }
        if (detailText != null) {
            detailText.setText(StatusTextFormatter.buildTransportDetail(
                    info,
                    daemonReachable,
                    daemonHostName,
                    daemonStateUi
            ));
        }
        if (bpsText != null) {
            bpsText.setText("throughput: " + StatusTextFormatter.formatBps(bps));
        }

        if (statusLed == null) {
            return;
        }
        if (statusLed.getBackground() instanceof GradientDrawable) {
            GradientDrawable drawable = (GradientDrawable) statusLed.getBackground().mutate();
            drawable.setColor(color);
        } else {
            statusLed.setBackgroundColor(color);
        }
    }

    public static String buildHostStatsLine(
            long frameIn,
            long frameOut,
            long drops,
            long reconnects,
            long bitrateBps,
            String lastError
    ) {
        String errCompact = compactError(lastError, 80);
        return "host in/out: " + frameIn + "/" + frameOut
                + " | drops: " + drops + " | reconnects: " + reconnects
                + " | bitrate: " + StatusTextFormatter.formatBps(bitrateBps)
                + (errCompact.isEmpty() ? "" : " | last_error: " + errCompact);
    }

    private static String compactError(String text, int maxLen) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
