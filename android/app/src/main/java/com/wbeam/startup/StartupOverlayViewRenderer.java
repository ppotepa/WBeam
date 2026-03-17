package com.wbeam.startup;

import android.view.View;
import android.widget.TextView;

@SuppressWarnings({"java:S3358", "java:S107"})
public final class StartupOverlayViewRenderer {
    private static final int SUBTITLE_OK_COLOR = 0xFF4ADE80;
    private static final int SUBTITLE_ERROR_COLOR = 0xFFF87171;
    private static final int SUBTITLE_NEUTRAL_COLOR = 0xFF475569;
    private static final int INFO_TEXT_COLOR = 0xFFCBD5E1;

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

        public void setTitleText(TextView titleText) {
            this.titleText = titleText;
        }

        public View getStep1Card() {
            return step1Card;
        }

        public void setStep1Card(View step1Card) {
            this.step1Card = step1Card;
        }

        public View getStep2Card() {
            return step2Card;
        }

        public void setStep2Card(View step2Card) {
            this.step2Card = step2Card;
        }

        public View getStep3Card() {
            return step3Card;
        }

        public void setStep3Card(View step3Card) {
            this.step3Card = step3Card;
        }

        public TextView getStep1Badge() {
            return step1Badge;
        }

        public void setStep1Badge(TextView step1Badge) {
            this.step1Badge = step1Badge;
        }

        public TextView getStep1Label() {
            return step1Label;
        }

        public void setStep1Label(TextView step1Label) {
            this.step1Label = step1Label;
        }

        public TextView getStep1Detail() {
            return step1Detail;
        }

        public void setStep1Detail(TextView step1Detail) {
            this.step1Detail = step1Detail;
        }

        public TextView getStep1Status() {
            return step1Status;
        }

        public void setStep1Status(TextView step1Status) {
            this.step1Status = step1Status;
        }

        public TextView getStep2Badge() {
            return step2Badge;
        }

        public void setStep2Badge(TextView step2Badge) {
            this.step2Badge = step2Badge;
        }

        public TextView getStep2Label() {
            return step2Label;
        }

        public void setStep2Label(TextView step2Label) {
            this.step2Label = step2Label;
        }

        public TextView getStep2Detail() {
            return step2Detail;
        }

        public void setStep2Detail(TextView step2Detail) {
            this.step2Detail = step2Detail;
        }

        public TextView getStep2Status() {
            return step2Status;
        }

        public void setStep2Status(TextView step2Status) {
            this.step2Status = step2Status;
        }

        public TextView getStep3Badge() {
            return step3Badge;
        }

        public void setStep3Badge(TextView step3Badge) {
            this.step3Badge = step3Badge;
        }

        public TextView getStep3Label() {
            return step3Label;
        }

        public void setStep3Label(TextView step3Label) {
            this.step3Label = step3Label;
        }

        public TextView getStep3Detail() {
            return step3Detail;
        }

        public void setStep3Detail(TextView step3Detail) {
            this.step3Detail = step3Detail;
        }

        public TextView getStep3Status() {
            return step3Status;
        }

        public void setStep3Status(TextView step3Status) {
            this.step3Status = step3Status;
        }

        public TextView getSubtitleText() {
            return subtitleText;
        }

        public void setSubtitleText(TextView subtitleText) {
            this.subtitleText = subtitleText;
        }

        public TextView getInfoText() {
            return infoText;
        }

        public void setInfoText(TextView infoText) {
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
        if (views.getTitleText() != null) {
            views.getTitleText().setText(title);
        }
        if (views.getSubtitleText() != null) {
            views.getSubtitleText().setText(body);
        }
        if (views.getInfoText() != null) {
            views.getInfoText().setText(hint);
            views.getInfoText().setTextColor(hintColor);
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
                preflightAnimTick,
                new StepBinding(
                        "1",
                        views.getStep1Card(),
                        views.getStep1Badge(),
                        views.getStep1Label(),
                        views.getStep1Status(),
                        views.getStep1Detail()
                ),
                model.getStep1Detail()
        );
        applyStepState(
                model.getStep2State(),
                preflightAnimTick,
                new StepBinding(
                        "2",
                        views.getStep2Card(),
                        views.getStep2Badge(),
                        views.getStep2Label(),
                        views.getStep2Status(),
                        views.getStep2Detail()
                ),
                model.getStep2Detail()
        );
        applyStepState(
                model.getStep3State(),
                preflightAnimTick,
                new StepBinding(
                        "3",
                        views.getStep3Card(),
                        views.getStep3Badge(),
                        views.getStep3Label(),
                        views.getStep3Status(),
                        views.getStep3Detail()
                ),
                model.getStep3Detail()
        );

        if (views.getSubtitleText() != null) {
            views.getSubtitleText().setText(model.getSubtitle());
            int subtitleColor = SUBTITLE_NEUTRAL_COLOR;
            if (model.getStep3State() == StartupOverlayModelBuilder.Model.SS_OK) {
                subtitleColor = SUBTITLE_OK_COLOR;
            } else if (model.getStep3State() == StartupOverlayModelBuilder.Model.SS_ERROR) {
                subtitleColor = SUBTITLE_ERROR_COLOR;
            }
            views.getSubtitleText().setTextColor(subtitleColor);
        }

        if (views.getInfoText() != null) {
            views.getInfoText().setText(model.getInfoLog());
            views.getInfoText().setTextColor(INFO_TEXT_COLOR);
        }
    }

    private static void applyStepState(int stepState, int preflightAnimTick, StepBinding step, String detail) {
        StartupStepStyler.applyStepState(
                StartupStepStyler.StepStateConfig.builder()
                        .setState(stepState)
                        .setAnimTick(preflightAnimTick)
                        .setSsOk(StartupOverlayModelBuilder.Model.SS_OK)
                        .setSsError(StartupOverlayModelBuilder.Model.SS_ERROR)
                        .setSsActive(StartupOverlayModelBuilder.Model.SS_ACTIVE)
                        .setNumber(step.number)
                        .setCard(step.card)
                        .setBadge(step.badge)
                        .setLabel(step.label)
                        .setStatus(step.status)
                        .setDetail(step.detail)
                        .setDetailText(detail)
                        .build()
        );
    }

    private static final class StepBinding {
        private final String number;
        private final View card;
        private final TextView badge;
        private final TextView label;
        private final TextView status;
        private final TextView detail;

        private StepBinding(
                String number,
                View card,
                TextView badge,
                TextView label,
                TextView status,
                TextView detail
        ) {
            this.number = number;
            this.card = card;
            this.badge = badge;
            this.label = label;
            this.status = status;
            this.detail = detail;
        }
    }
}
