package com.wbeam.ui;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

/**
 * Holds and trims colored live log lines for UI display.
 */
public final class LiveLogBuffer {
    private static final int COLOR_INFO = Color.parseColor("#166534");
    private static final int COLOR_WARN = Color.parseColor("#F59E0B");
    private static final int COLOR_ERROR = Color.parseColor("#7F1D1D");
    private static final int COLOR_DEFAULT = Color.parseColor("#9CA3AF");

    private final int maxLines;
    private final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private int lineCount = 0;

    public LiveLogBuffer(int maxLines) {
        this.maxLines = Math.max(1, maxLines);
    }

    public CharSequence append(String level, String line) {
        int color = colorForLevel(level);
        String text = "[" + level + "] " + line + "\n";
        int start = buffer.length();
        buffer.append(text);
        buffer.setSpan(
                new ForegroundColorSpan(color),
                start,
                buffer.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        lineCount += countLineBreaks(text);
        while (lineCount > maxLines) {
            int newline = indexOfNewline(buffer, 0);
            if (newline < 0) {
                lineCount = buffer.length() == 0 ? 0 : 1;
                break;
            }
            buffer.delete(0, newline + 1);
            lineCount--;
        }
        return buffer;
    }

    private int colorForLevel(String level) {
        if ("I".equals(level)) {
            return COLOR_INFO;
        }
        if ("W".equals(level)) {
            return COLOR_WARN;
        }
        if ("E".equals(level)) {
            return COLOR_ERROR;
        }
        return COLOR_DEFAULT;
    }

    private static int countLineBreaks(CharSequence text) {
        if (text == null) {
            return 0;
        }
        int breaks = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                breaks++;
            }
        }
        return breaks;
    }

    private static int indexOfNewline(CharSequence text, int fromIndex) {
        int start = Math.max(0, fromIndex);
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }
}
