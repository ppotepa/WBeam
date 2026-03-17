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

    public static final class RenderStatusInput {
        private TextView statusText;
        private TextView detailText;
        private TextView bpsText;
        private View statusLed;
        private String state;
        private String info;
        private long bps;
        private boolean daemonReachable;
        private String daemonHostName;
        private String daemonStateUi;
        private String streamingState;
        private String connectingState;

        public RenderStatusInput setStatusText(TextView statusText) { this.statusText = statusText; return this; }
        public RenderStatusInput setDetailText(TextView detailText) { this.detailText = detailText; return this; }
        public RenderStatusInput setBpsText(TextView bpsText) { this.bpsText = bpsText; return this; }
        public RenderStatusInput setStatusLed(View statusLed) { this.statusLed = statusLed; return this; }
        public RenderStatusInput setState(String state) { this.state = state; return this; }
        public RenderStatusInput setInfo(String info) { this.info = info; return this; }
        public RenderStatusInput setBps(long bps) { this.bps = bps; return this; }
        public RenderStatusInput setDaemonReachable(boolean daemonReachable) { this.daemonReachable = daemonReachable; return this; }
        public RenderStatusInput setDaemonHostName(String daemonHostName) { this.daemonHostName = daemonHostName; return this; }
        public RenderStatusInput setDaemonStateUi(String daemonStateUi) { this.daemonStateUi = daemonStateUi; return this; }
        public RenderStatusInput setStreamingState(String streamingState) { this.streamingState = streamingState; return this; }
        public RenderStatusInput setConnectingState(String connectingState) { this.connectingState = connectingState; return this; }
    }

    public static void renderStatus(RenderStatusInput input) {
        int color = StatusColorResolver.ledColorForState(input.state, input.streamingState, input.connectingState);
        if (input.statusText != null) {
            input.statusText.setText(input.state.toUpperCase(Locale.US));
        }
        if (input.detailText != null) {
            input.detailText.setText(StatusTextFormatter.buildTransportDetail(
                    input.info,
                    input.daemonReachable,
                    input.daemonHostName,
                    input.daemonStateUi
            ));
        }
        if (input.bpsText != null) {
            input.bpsText.setText("throughput: " + StatusTextFormatter.formatBps(input.bps));
        }

        if (input.statusLed == null) {
            return;
        }
        if (input.statusLed.getBackground() instanceof GradientDrawable) {
            GradientDrawable drawable = (GradientDrawable) input.statusLed.getBackground().mutate();
            drawable.setColor(color);
        } else {
            input.statusLed.setBackgroundColor(color);
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
