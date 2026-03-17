package com.wbeam.ui;

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

    public static String formatBytesPerSec(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "-";
        }
        if (bytesPerSec >= 1024L * 1024L) {
            return formatFixed(bytesPerSec / (1024.0 * 1024.0), 2) + " MB/s";
        }
        if (bytesPerSec >= 1024L) {
            return formatFixed(bytesPerSec / 1024.0, 1) + " KB/s";
        }
        return bytesPerSec + " B/s";
    }

    public static String formatBitsPerSec(long bitsPerSec) {
        if (bitsPerSec <= 0) {
            return "-";
        }
        if (bitsPerSec >= 1_000_000L) {
            return formatFixed(bitsPerSec / 1_000_000.0, 2) + " Mb/s";
        }
        if (bitsPerSec >= 1_000L) {
            return formatFixed(bitsPerSec / 1_000.0, 1) + " Kb/s";
        }
        return bitsPerSec + " b/s";
    }

    public static String formatBps(long bps) {
        return formatBytesPerSec(bps);
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private static String formatFixed(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        int safeDecimals = Math.max(0, Math.min(3, decimals));
        long factor;
        switch (safeDecimals) {
            case 0:
                factor = 1L;
                break;
            case 1:
                factor = 10L;
                break;
            case 2:
                factor = 100L;
                break;
            default:
                factor = 1000L;
                break;
        }
        long rounded = Math.round(value * factor);
        long whole = Math.abs(rounded) / factor;
        long fraction = Math.abs(rounded) % factor;
        StringBuilder out = new StringBuilder();
        if (rounded < 0L) {
            out.append('-');
        }
        out.append(whole);
        if (safeDecimals == 0) {
            return out.toString();
        }
        out.append('.');
        if (safeDecimals >= 3 && fraction < 100L) {
            out.append('0');
        }
        if (safeDecimals >= 2 && fraction < 10L) {
            out.append('0');
        }
        out.append(fraction);
        return out.toString();
    }
}
