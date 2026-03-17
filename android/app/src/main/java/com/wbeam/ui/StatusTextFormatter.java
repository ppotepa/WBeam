package com.wbeam.ui;

import java.util.Locale;

/**
 * Formats user-facing status and host hint text.
 */
public final class StatusTextFormatter {
    private StatusTextFormatter() {}

    @SuppressWarnings("java:S107")
    public static String buildHostHintText(
            boolean daemonReachable,
            String apiBase,
            String daemonHostName,
            String daemonStateUi,
            String daemonService,
            String profile,
            int width,
            int height,
            int fps,
            int bitrateMbps,
            String encoder,
            boolean intraOnlyEnabled,
            String cursorMode
    ) {
        String line1 = "Control API " + (daemonReachable ? "connected" : "waiting")
                + ": " + safe( apiBase, "-" );
        String line2 = "Host: " + safe(daemonHostName, "-")
                + " | Daemon: " + safe(daemonStateUi, "-")
                + " (" + safe(daemonService, "-") + ")";
        String line3 = "Outgoing config: " + safe(profile, "-")
                + ", " + width + "x" + height
                + ", " + fps + "fps, "
                + bitrateMbps + "Mbps, "
                + safe(encoder, "-") + (intraOnlyEnabled ? "+intra" : "")
                + ", cursor " + safe(cursorMode, "-");
        return line1 + "\n" + line2 + "\n" + line3;
    }

    public static String buildTransportDetail(
            String uiInfo,
            boolean daemonReachable,
            String daemonHostName,
            String daemonStateUi
    ) {
        String host = safe(daemonHostName, "-");
        String transport = "USB:" + (daemonReachable ? "Connected" : "Disconnected")
                + " | Host:" + host
                + " | Stream:" + safe(daemonStateUi, "-");
        if (uiInfo == null || uiInfo.trim().isEmpty()) {
            return transport;
        }
        return uiInfo + " | " + transport;
    }

    public static String formatBps(long bps) {
        if (bps <= 0) {
            return "-";
        }
        if (bps >= 1024L * 1024L) {
            return String.format(Locale.US, "%.2f MB/s", bps / (1024.0 * 1024.0));
        }
        if (bps >= 1024L) {
            return String.format(Locale.US, "%.1f KB/s", bps / 1024.0);
        }
        return bps + " B/s";
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }
}
