package com.wbeam.startup;

import android.view.View;
import android.widget.TextView;

public final class StartupOverlayViewRenderer {
    private static final int SUBTITLE_OK_COLOR = 0xFF4ADE80;
    private static final int SUBTITLE_ERROR_COLOR = 0xFFF87171;
    private static final int SUBTITLE_NEUTRAL_COLOR = 0xFF475569;
    private static final int INFO_TEXT_COLOR = 0xFFCBD5E1;

    /**
     * Plain data carrier for startup overlay views.
     */
    @SuppressWarnings("java:S1104")
    public static final class Views {
        public TextView titleText;
        public View step1Card;
        public View step2Card;
        public View step3Card;
        public TextView step1Badge;
        public TextView step1Label;
        public TextView step1Detail;
        public TextView step1Status;
        public TextView step2Badge;
        public TextView step2Label;
        public TextView step2Detail;
        public TextView step2Status;
        public TextView step3Badge;
        public TextView step3Label;
        public TextView step3Detail;
        public TextView step3Status;
        public TextView subtitleText;
        public TextView infoText;
    }

    private StartupOverlayViewRenderer() {
    }

    public static boolean applyVideoTestOverride(
            Views views,
            String title,
            String body,
            String hint,
            int hintColor
    ) {
        if (views.titleText != null) {
            views.titleText.setText(title);
        }
        if (views.subtitleText != null) {
            views.subtitleText.setText(body);
        }
        if (views.infoText != null) {
            views.infoText.setText(hint);
            views.infoText.setTextColor(hintColor);
        }
        return true;
    }

    public static void applyModel(
            StartupOverlayModelBuilder.Model model,
            int preflightAnimTick,
            Views views
    ) {
        applyStepState(
                model.step1State,
                "1",
                preflightAnimTick,
                views.step1Card,
                views.step1Badge,
                views.step1Label,
                views.step1Status,
                views.step1Detail,
                model.step1Detail
        );
        applyStepState(
                model.step2State,
                "2",
                preflightAnimTick,
                views.step2Card,
                views.step2Badge,
                views.step2Label,
                views.step2Status,
                views.step2Detail,
                model.step2Detail
        );
        applyStepState(
                model.step3State,
                "3",
                preflightAnimTick,
                views.step3Card,
                views.step3Badge,
                views.step3Label,
                views.step3Status,
                views.step3Detail,
                model.step3Detail
        );

        if (views.subtitleText != null) {
            views.subtitleText.setText(model.subtitle);
            final int subtitleColor;
            if (model.step3State == StartupOverlayModelBuilder.Model.SS_OK) {
                subtitleColor = SUBTITLE_OK_COLOR;
            } else if (model.step3State == StartupOverlayModelBuilder.Model.SS_ERROR) {
                subtitleColor = SUBTITLE_ERROR_COLOR;
            } else {
                subtitleColor = SUBTITLE_NEUTRAL_COLOR;
            }
            views.subtitleText.setTextColor(subtitleColor);
        }

        if (views.infoText != null) {
            views.infoText.setText(model.infoLog);
            views.infoText.setTextColor(INFO_TEXT_COLOR);
        }
    }

    @SuppressWarnings("java:S107")
    private static void applyStepState(
            int stepState,
            String stepNumber,
            int preflightAnimTick,
            View stepCard,
            TextView stepBadge,
            TextView stepLabel,
            TextView stepStatus,
            TextView stepDetail,
            String detail
    ) {
        StartupStepStyler.applyStepState(
                stepState,
                preflightAnimTick,
                StartupOverlayModelBuilder.Model.SS_OK,
                StartupOverlayModelBuilder.Model.SS_ERROR,
                StartupOverlayModelBuilder.Model.SS_ACTIVE,
                stepNumber,
                stepCard,
                stepBadge,
                stepLabel,
                stepStatus,
                stepDetail,
                detail
        );
    }
}
