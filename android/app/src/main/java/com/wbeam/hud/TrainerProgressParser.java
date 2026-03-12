package com.wbeam.hud;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses trainer trial progress from HUD text snapshots.
 */
public final class TrainerProgressParser {
    private static final Pattern TRAINER_TRIAL_PROGRESS_RE =
            Pattern.compile("TRIAL\\s+[^\\[]*\\[(\\d+)/(\\d+)]");

    private TrainerProgressParser() {}

    public static String buildProgressLine(String hudText) {
        Matcher matcher = TRAINER_TRIAL_PROGRESS_RE.matcher(hudText == null ? "" : hudText);
        if (!matcher.find()) {
            return "";
        }
        int cur;
        int total;
        try {
            cur = Integer.parseInt(matcher.group(1));
            total = Integer.parseInt(matcher.group(2));
        } catch (Exception ignored) {
            return "";
        }
        if (total <= 0 || cur <= 0) {
            return "";
        }
        int pct = Math.max(0, Math.min(100, (int) Math.round((cur * 100.0) / total)));
        return String.format(Locale.US, "TRAINING PROGRESS %d%%  (trial %d/%d)", pct, cur, total);
    }

    public static int parseProgressPercent(String hudText) {
        Matcher matcher = TRAINER_TRIAL_PROGRESS_RE.matcher(hudText == null ? "" : hudText);
        if (!matcher.find()) {
            return -1;
        }
        try {
            int cur = Integer.parseInt(matcher.group(1));
            int total = Integer.parseInt(matcher.group(2));
            if (cur <= 0 || total <= 0) {
                return -1;
            }
            return Math.max(0, Math.min(100, (int) Math.round((cur * 100.0) / total)));
        } catch (Exception ignored) {
            return -1;
        }
    }
}
