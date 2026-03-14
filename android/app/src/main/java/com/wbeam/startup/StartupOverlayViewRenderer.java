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
    public static final class Views {
        private TextView titleText;
        private View step1Card;
        private View step2Card;
        private View step3Card;
        private TextView step1Badge;
        private TextView step1Label;
        private TextView step1Detail;
        private TextView step1Status;
        private TextView step2Badge;
        private TextView step2Label;
        private TextView step2Detail;
        private TextView step2Status;
        private TextView step3Badge;
        private TextView step3Label;
        private TextView step3Detail;
        private TextView step3Status;
        private TextView subtitleText;
        private TextView infoText;

        public TextView getTitleText() {
            return titleText;
        }

        void setTitleText(TextView titleText) {
            this.titleText = titleText;
        }

        public View getStep1Card() {
            return step1Card;
        }

        void setStep1Card(View step1Card) {
            this.step1Card = step1Card;
        }

        public View getStep2Card() {
            return step2Card;
        }

        void setStep2Card(View step2Card) {
            this.step2Card = step2Card;
        }

        public View getStep3Card() {
            return step3Card;
        }

        void setStep3Card(View step3Card) {
            this.step3Card = step3Card;
        }

        public TextView getStep1Badge() {
            return step1Badge;
        }

        void setStep1Badge(TextView step1Badge) {
            this.step1Badge = step1Badge;
        }

        public TextView getStep1Label() {
            return step1Label;
        }

        void setStep1Label(TextView step1Label) {
            this.step1Label = step1Label;
        }

        public TextView getStep1Detail() {
            return step1Detail;
        }

        void setStep1Detail(TextView step1Detail) {
            this.step1Detail = step1Detail;
        }

        public TextView getStep1Status() {
            return step1Status;
        }

        void setStep1Status(TextView step1Status) {
            this.step1Status = step1Status;
        }

        public TextView getStep2Badge() {
            return step2Badge;
        }

        void setStep2Badge(TextView step2Badge) {
            this.step2Badge = step2Badge;
        }

        public TextView getStep2Label() {
            return step2Label;
        }

        void setStep2Label(TextView step2Label) {
            this.step2Label = step2Label;
        }

        public TextView getStep2Detail() {
            return step2Detail;
        }

        void setStep2Detail(TextView step2Detail) {
            this.step2Detail = step2Detail;
        }

        public TextView getStep2Status() {
            return step2Status;
        }

        void setStep2Status(TextView step2Status) {
            this.step2Status = step2Status;
        }

        public TextView getStep3Badge() {
            return step3Badge;
        }

        void setStep3Badge(TextView step3Badge) {
            this.step3Badge = step3Badge;
        }

        public TextView getStep3Label() {
            return step3Label;
        }

        void setStep3Label(TextView step3Label) {
            this.step3Label = step3Label;
        }

        public TextView getStep3Detail() {
            return step3Detail;
        }

        void setStep3Detail(TextView step3Detail) {
            this.step3Detail = step3Detail;
        }

        public TextView getStep3Status() {
            return step3Status;
        }

        void setStep3Status(TextView step3Status) {
            this.step3Status = step3Status;
        }

        public TextView getSubtitleText() {
            return subtitleText;
        }

        void setSubtitleText(TextView subtitleText) {
            this.subtitleText = subtitleText;
        }

        public TextView getInfoText() {
            return infoText;
        }

        void setInfoText(TextView infoText) {
            this.infoText = infoText;
        }
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
        TextView titleView = views.getTitleText();
        if (titleView != null) {
            titleView.setText(title);
        }
        TextView subtitleView = views.getSubtitleText();
        if (subtitleView != null) {
            subtitleView.setText(body);
        }
        TextView infoView = views.getInfoText();
        if (infoView != null) {
            infoView.setText(hint);
            infoView.setTextColor(hintColor);
        }
        return true;
    }

    public static void applyModel(
            StartupOverlayModelBuilder.Model model,
            int preflightAnimTick,
            Views views
    ) {
        applyStepState(
                model.getStep1State(),
                "1",
                preflightAnimTick,
                views.getStep1Card(),
                views.getStep1Badge(),
                views.getStep1Label(),
                views.getStep1Status(),
                views.getStep1Detail(),
                model.getStep1Detail()
        );
        applyStepState(
                model.getStep2State(),
                "2",
                preflightAnimTick,
                views.getStep2Card(),
                views.getStep2Badge(),
                views.getStep2Label(),
                views.getStep2Status(),
                views.getStep2Detail(),
                model.getStep2Detail()
        );
        applyStepState(
                model.getStep3State(),
                "3",
                preflightAnimTick,
                views.getStep3Card(),
                views.getStep3Badge(),
                views.getStep3Label(),
                views.getStep3Status(),
                views.getStep3Detail(),
                model.getStep3Detail()
        );

        TextView subtitleView = views.getSubtitleText();
        if (subtitleView != null) {
            subtitleView.setText(model.getSubtitle());
            final int subtitleColor;
            if (model.getStep3State() == StartupOverlayModelBuilder.Model.SS_OK) {
                subtitleColor = SUBTITLE_OK_COLOR;
            } else if (model.getStep3State() == StartupOverlayModelBuilder.Model.SS_ERROR) {
                subtitleColor = SUBTITLE_ERROR_COLOR;
            } else {
                subtitleColor = SUBTITLE_NEUTRAL_COLOR;
            }
            subtitleView.setTextColor(subtitleColor);
        }

        TextView infoView = views.getInfoText();
        if (infoView != null) {
            infoView.setText(model.getInfoLog());
            infoView.setTextColor(INFO_TEXT_COLOR);
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
