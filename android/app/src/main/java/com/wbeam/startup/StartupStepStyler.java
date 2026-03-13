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

    @SuppressWarnings("java:java:S107")
    public static void applyStepState(
            int state,
            int animTick,
            int ssOk,
            int ssError,
            int ssActive,
            String number,
            View card,
            TextView badge,
            TextView label,
            TextView status,
            TextView detail,
            String detailText
    ) {
        if (badge == null || label == null || status == null || detail == null || card == null) {
            return;
        }
        int badgeBg;
        int badgeFg;
        int labelColor;
        int statusColor;
        int cardBg;
        String statusStr;
        String icon = stepIconForNumber(number);
        boolean blink = (animTick % 2) == 0;
        if (state == ssOk) {
            cardBg = Color.parseColor("#0F2E25");
            badgeBg = Color.parseColor("#14532D");
            badgeFg = Color.parseColor("#4ADE80");
            labelColor = Color.parseColor("#86EFAC");
            statusColor = Color.parseColor("#4ADE80");
            statusStr = "OK";
            badge.setText(icon);
            detail.setTextColor(Color.parseColor("#86EFAC"));
        } else if (state == ssError) {
            cardBg = Color.parseColor("#361113");
            badgeBg = Color.parseColor("#7F1D1D");
            badgeFg = Color.parseColor("#FCA5A5");
            labelColor = Color.parseColor("#FCA5A5");
            statusColor = Color.parseColor("#FCA5A5");
            statusStr = "ERR";
            badge.setText(icon);
            detail.setTextColor(Color.parseColor("#FCA5A5"));
        } else if (state == ssActive) {
            cardBg = blink ? Color.parseColor("#3A2A0F") : Color.parseColor("#2B1F0F");
            badgeBg = blink ? Color.parseColor("#A16207") : Color.parseColor("#854D0E");
            badgeFg = Color.parseColor("#FEF08A");
            labelColor = Color.parseColor("#FEF08A");
            statusColor = Color.parseColor("#FDE68A");
            statusStr = "BUSY";
            badge.setText(icon);
            detail.setTextColor(Color.parseColor("#FDE68A"));
        } else {
            cardBg = Color.parseColor("#111827");
            badgeBg = Color.parseColor("#1E293B");
            badgeFg = Color.parseColor("#64748B");
            labelColor = Color.parseColor("#64748B");
            statusColor = Color.parseColor("#64748B");
            statusStr = "WAIT";
            badge.setText(icon);
            detail.setTextColor(Color.parseColor("#94A3B8"));
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
        detail.setText(detailText != null ? detailText : "");
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
}
