package com.wbeam.ui;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

/**
 * Holds and trims colored live log lines for UI display.
 */
public final class LiveLogBuffer {
    private final int maxLines;
    private final SpannableStringBuilder buffer = new SpannableStringBuilder();

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

        int linesToTrim = countLines(buffer) - maxLines;
        while (linesToTrim > 0) {
            int newline = buffer.toString().indexOf('\n');
            if (newline < 0) {
                break;
            }
            buffer.delete(0, newline + 1);
            linesToTrim--;
        }
        return buffer;
    }

    private int colorForLevel(String level) {
        if ("I".equals(level)) {
            return Color.parseColor("#166534");
        }
        if ("W".equals(level)) {
            return Color.parseColor("#F59E0B");
        }
        if ("E".equals(level)) {
            return Color.parseColor("#7F1D1D");
        }
        return Color.parseColor("#9CA3AF");
    }

    private int countLines(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
