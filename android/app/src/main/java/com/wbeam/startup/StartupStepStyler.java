package com.wbeam.startup;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

/**
 * Applies visual state and text for startup/preflight steps.
 */
public final class StartupStepStyler {
    private StartupStepStyler() {}

    // Colors - OK state
    private static final String COLOR_OK_CARD_BG = "#0F2E25";
    private static final String COLOR_OK_BADGE_BG = "#14532D";
    private static final String COLOR_OK_GREEN = "#4ADE80";
    private static final String COLOR_OK_LIGHT_GREEN = "#86EFAC";

    // Colors - Error state
    private static final String COLOR_ERR_CARD_BG = "#361113";
    private static final String COLOR_ERR_BADGE_BG = "#7F1D1D";
    private static final String COLOR_ERR_RED = "#FCA5A5";

    // Colors - Active state
    private static final String COLOR_ACTIVE_CARD_BG_BLINK = "#3A2A0F";
    private static final String COLOR_ACTIVE_CARD_BG = "#2B1F0F";
    private static final String COLOR_ACTIVE_BADGE_BG_BLINK = "#A16207";
    private static final String COLOR_ACTIVE_BADGE_BG = "#854D0E";
    private static final String COLOR_ACTIVE_YELLOW = "#FEF08A";
    private static final String COLOR_ACTIVE_YELLOW_ALT = "#FDE68A";

    // Colors - Waiting state
    private static final String COLOR_WAIT_CARD_BG = "#111827";
    private static final String COLOR_WAIT_BADGE_BG = "#1E293B";
    private static final String COLOR_WAIT_GRAY = "#64748B";
    private static final String COLOR_WAIT_LIGHT_GRAY = "#94A3B8";

    // Status strings
    private static final String STATUS_OK = "OK";
    private static final String STATUS_ERR = "ERR";
    private static final String STATUS_BUSY = "BUSY";
    private static final String STATUS_WAIT = "WAIT";


    public static void applyStepState(StepStateConfig config) {
        TextView badge = config.badge;
        TextView label = config.label;
        TextView status = config.status;
        TextView detail = config.detail;
        View card = config.card;

        if (badge == null || label == null || status == null || detail == null || card == null) {
            return;
        }

        int badgeBg;
        int badgeFg;
        int labelColor;
        int statusColor;
        int cardBg;
        String statusStr;
        String icon = stepIconForNumber(config.number);
        boolean blink = (config.animTick % 2) == 0;

        if (config.state == config.ssOk) {
            cardBg = Color.parseColor(COLOR_OK_CARD_BG);
            badgeBg = Color.parseColor(COLOR_OK_BADGE_BG);
            badgeFg = Color.parseColor(COLOR_OK_GREEN);
            labelColor = Color.parseColor(COLOR_OK_LIGHT_GREEN);
            statusColor = Color.parseColor(COLOR_OK_GREEN);
            statusStr = STATUS_OK;
            badge.setText(icon);
            detail.setTextColor(Color.parseColor(COLOR_OK_LIGHT_GREEN));
        } else if (config.state == config.ssError) {
            cardBg = Color.parseColor(COLOR_ERR_CARD_BG);
            badgeBg = Color.parseColor(COLOR_ERR_BADGE_BG);
            badgeFg = Color.parseColor(COLOR_ERR_RED);
            labelColor = Color.parseColor(COLOR_ERR_RED);
            statusColor = Color.parseColor(COLOR_ERR_RED);
            statusStr = STATUS_ERR;
            badge.setText(icon);
            detail.setTextColor(Color.parseColor(COLOR_ERR_RED));
        } else if (config.state == config.ssActive) {
            cardBg = blink ? Color.parseColor(COLOR_ACTIVE_CARD_BG_BLINK) : Color.parseColor(COLOR_ACTIVE_CARD_BG);
            badgeBg = blink ? Color.parseColor(COLOR_ACTIVE_BADGE_BG_BLINK) : Color.parseColor(COLOR_ACTIVE_BADGE_BG);
            badgeFg = Color.parseColor(COLOR_ACTIVE_YELLOW);
            labelColor = Color.parseColor(COLOR_ACTIVE_YELLOW);
            statusColor = Color.parseColor(COLOR_ACTIVE_YELLOW_ALT);
            statusStr = STATUS_BUSY;
            badge.setText(icon);
            detail.setTextColor(Color.parseColor(COLOR_ACTIVE_YELLOW_ALT));
        } else {
            cardBg = Color.parseColor(COLOR_WAIT_CARD_BG);
            badgeBg = Color.parseColor(COLOR_WAIT_BADGE_BG);
            badgeFg = Color.parseColor(COLOR_WAIT_GRAY);
            labelColor = Color.parseColor(COLOR_WAIT_GRAY);
            statusColor = Color.parseColor(COLOR_WAIT_GRAY);
            statusStr = STATUS_WAIT;
            badge.setText(icon);
            detail.setTextColor(Color.parseColor(COLOR_WAIT_LIGHT_GRAY));
        }

        if (card.getBackground() instanceof GradientDrawable) {
            GradientDrawable cardDrawable = (GradientDrawable) card.getBackground().mutate();
            cardDrawable.setColor(cardBg);
        } else {
            card.setBackgroundColor(cardBg);
        }
        badge.setBackgroundColor(badgeBg);
        badge.setTextColor(badgeFg);
        label.setTextColor(labelColor);
        status.setText(statusStr);
        status.setTextColor(statusColor);
        detail.setText(config.detailText != null ? config.detailText : "");
    }

    private static String stepIconForNumber(String number) {
        if ("1".equals(number)) {
            return "\u21C4";
        }
        if ("2".equals(number)) {
            return "\u2699";
        }
        return "\u25B6";
    }

    /**
     * Configuration object for applyStepState method (Builder pattern).
     */
    public static class StepStateConfig {
        public int state;
        public int animTick;
        public int ssOk;
        public int ssError;
        public int ssActive;
        public String number;
        public View card;
        public TextView badge;
        public TextView label;
        public TextView status;
        public TextView detail;
        public String detailText;

        public StepStateConfig(int state, int animTick, int ssOk, int ssError, int ssActive,
                              String number, View card, TextView badge, TextView label,
                              TextView status, TextView detail, String detailText) {
            this.state = state;
            this.animTick = animTick;
            this.ssOk = ssOk;
            this.ssError = ssError;
            this.ssActive = ssActive;
            this.number = number;
            this.card = card;
            this.badge = badge;
            this.label = label;
            this.status = status;
            this.detail = detail;
            this.detailText = detailText;
        }
    }
}
