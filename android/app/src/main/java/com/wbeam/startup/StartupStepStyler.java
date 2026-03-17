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
        TextView badge = config.getBadge();
        TextView label = config.getLabel();
        TextView status = config.getStatus();
        TextView detail = config.getDetail();
        View card = config.getCard();

        if (badge == null || label == null || status == null || detail == null || card == null) {
            return;
        }

        int badgeBg;
        int badgeFg;
        int labelColor;
        int statusColor;
        int cardBg;
        String statusStr;
        String icon = stepIconForNumber(config.getNumber());
        boolean blink = (config.getAnimTick() % 2) == 0;

        if (config.getState() == config.getSsOk()) {
            cardBg = Color.parseColor(COLOR_OK_CARD_BG);
            badgeBg = Color.parseColor(COLOR_OK_BADGE_BG);
            badgeFg = Color.parseColor(COLOR_OK_GREEN);
            labelColor = Color.parseColor(COLOR_OK_LIGHT_GREEN);
            statusColor = Color.parseColor(COLOR_OK_GREEN);
            statusStr = STATUS_OK;
            badge.setText(icon);
            detail.setTextColor(Color.parseColor(COLOR_OK_LIGHT_GREEN));
        } else if (config.getState() == config.getSsError()) {
            cardBg = Color.parseColor(COLOR_ERR_CARD_BG);
            badgeBg = Color.parseColor(COLOR_ERR_BADGE_BG);
            badgeFg = Color.parseColor(COLOR_ERR_RED);
            labelColor = Color.parseColor(COLOR_ERR_RED);
            statusColor = Color.parseColor(COLOR_ERR_RED);
            statusStr = STATUS_ERR;
            badge.setText(icon);
            detail.setTextColor(Color.parseColor(COLOR_ERR_RED));
        } else if (config.getState() == config.getSsActive()) {
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
        detail.setText(config.getDetailText() != null ? config.getDetailText() : "");
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
        private final int state;
        private final int animTick;
        private final int ssOk;
        private final int ssError;
        private final int ssActive;
        private final String number;
        private final View card;
        private final TextView badge;
        private final TextView label;
        private final TextView status;
        private final TextView detail;
        private final String detailText;

        private StepStateConfig(Builder builder) {
            this.state = builder.state;
            this.animTick = builder.animTick;
            this.ssOk = builder.ssOk;
            this.ssError = builder.ssError;
            this.ssActive = builder.ssActive;
            this.number = builder.number;
            this.card = builder.card;
            this.badge = builder.badge;
            this.label = builder.label;
            this.status = builder.status;
            this.detail = builder.detail;
            this.detailText = builder.detailText;
        }

        public static Builder builder() {
            return new Builder();
        }

        public int getState() {
            return state;
        }

        public int getAnimTick() {
            return animTick;
        }

        public int getSsOk() {
            return ssOk;
        }

        public int getSsError() {
            return ssError;
        }

        public int getSsActive() {
            return ssActive;
        }

        public String getNumber() {
            return number;
        }

        public View getCard() {
            return card;
        }

        public TextView getBadge() {
            return badge;
        }

        public TextView getLabel() {
            return label;
        }

        public TextView getStatus() {
            return status;
        }

        public TextView getDetail() {
            return detail;
        }

        public String getDetailText() {
            return detailText;
        }
    }

    public static class Builder {
        private int state;
        private int animTick;
        private int ssOk;
        private int ssError;
        private int ssActive;
        private String number;
        private View card;
        private TextView badge;
        private TextView label;
        private TextView status;
        private TextView detail;
        private String detailText;

        public Builder setState(int state) {
            this.state = state;
            return this;
        }

        public Builder setAnimTick(int animTick) {
            this.animTick = animTick;
            return this;
        }

        public Builder setSsOk(int ssOk) {
            this.ssOk = ssOk;
            return this;
        }

        public Builder setSsError(int ssError) {
            this.ssError = ssError;
            return this;
        }

        public Builder setSsActive(int ssActive) {
            this.ssActive = ssActive;
            return this;
        }

        public Builder setNumber(String number) {
            this.number = number;
            return this;
        }

        public Builder setCard(View card) {
            this.card = card;
            return this;
        }

        public Builder setBadge(TextView badge) {
            this.badge = badge;
            return this;
        }

        public Builder setLabel(TextView label) {
            this.label = label;
            return this;
        }

        public Builder setStatus(TextView status) {
            this.status = status;
            return this;
        }

        public Builder setDetail(TextView detail) {
            this.detail = detail;
            return this;
        }

        public Builder setDetailText(String detailText) {
            this.detailText = detailText;
            return this;
        }

        public StepStateConfig build() {
            return new StepStateConfig(this);
        }
    }
}
