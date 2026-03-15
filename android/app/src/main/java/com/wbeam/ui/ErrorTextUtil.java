package com.wbeam.ui;

import java.util.Locale;

/**
 * Error/info text helpers used by UI components.
 */
public final class ErrorTextUtil {
    private ErrorTextUtil() {}

    public static String shortError(Exception e) {
        if (e == null) {
            return "Error";
        }
        String msg = e.getMessage();
        if (msg != null) {
            msg = msg.trim();
            if (!msg.isEmpty()) {
                if (msg.length() > 120) {
                    return msg.substring(0, 120);
                }
                return msg;
            }
        }
        return e.getClass().getSimpleName();
    }

    public static String compactDaemonErrorForUi(String raw) {
        if (raw == null) {
            return "";
        }
        String compact = raw.replace('\n', ' ').replace('\r', ' ').trim();
        while (compact.contains("  ")) {
            compact = compact.replace("  ", " ");
        }
        if (compact.length() > 120) {
            return compact.substring(0, 120) + "...";
        }
        return compact;
    }

    public static boolean isCriticalUiInfo(String info) {
        if (info == null) {
            return false;
        }
        String text = info.toLowerCase(Locale.US);
        return text.contains("offline")
                || text.contains("ioexception")
                || text.contains("stream error")
                || text.contains("failed")
                || text.contains("disconnected");
    }
}
