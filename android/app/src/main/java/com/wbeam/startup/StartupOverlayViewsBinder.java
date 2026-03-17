package com.wbeam.startup;

import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wbeam.R;

public final class StartupOverlayViewsBinder {
    private StartupOverlayViewsBinder() {
    }

    public static TextView bind(
            AppCompatActivity activity,
            StartupOverlayViewRenderer.Views views
    ) {
        views.setTitleText(activity.findViewById(R.id.startupTitle));
        views.setSubtitleText(activity.findViewById(R.id.startupSubtitle));
        views.setStep1Card(activity.findViewById(R.id.startupStep1Row));
        views.setStep2Card(activity.findViewById(R.id.startupStep2Row));
        views.setStep3Card(activity.findViewById(R.id.startupStep3Row));
        views.setStep1Badge(activity.findViewById(R.id.startupStep1Badge));
        views.setStep1Label(activity.findViewById(R.id.startupStep1Label));
        views.setStep1Detail(activity.findViewById(R.id.startupStep1Detail));
        views.setStep1Status(activity.findViewById(R.id.startupStep1Status));
        views.setStep2Badge(activity.findViewById(R.id.startupStep2Badge));
        views.setStep2Label(activity.findViewById(R.id.startupStep2Label));
        views.setStep2Detail(activity.findViewById(R.id.startupStep2Detail));
        views.setStep2Status(activity.findViewById(R.id.startupStep2Status));
        views.setStep3Badge(activity.findViewById(R.id.startupStep3Badge));
        views.setStep3Label(activity.findViewById(R.id.startupStep3Label));
        views.setStep3Detail(activity.findViewById(R.id.startupStep3Detail));
        views.setStep3Status(activity.findViewById(R.id.startupStep3Status));
        views.setInfoText(activity.findViewById(R.id.startupInfoText));
        if (views.getInfoText() != null) {
            views.getInfoText().setMovementMethod(new ScrollingMovementMethod());
        }
        return activity.findViewById(R.id.startupBuildVersion);
    }
}
