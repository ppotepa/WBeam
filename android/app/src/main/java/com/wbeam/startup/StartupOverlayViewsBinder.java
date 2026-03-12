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
        views.titleText = activity.findViewById(R.id.startupTitle);
        views.subtitleText = activity.findViewById(R.id.startupSubtitle);
        views.step1Card = activity.findViewById(R.id.startupStep1Row);
        views.step2Card = activity.findViewById(R.id.startupStep2Row);
        views.step3Card = activity.findViewById(R.id.startupStep3Row);
        views.step1Badge = activity.findViewById(R.id.startupStep1Badge);
        views.step1Label = activity.findViewById(R.id.startupStep1Label);
        views.step1Detail = activity.findViewById(R.id.startupStep1Detail);
        views.step1Status = activity.findViewById(R.id.startupStep1Status);
        views.step2Badge = activity.findViewById(R.id.startupStep2Badge);
        views.step2Label = activity.findViewById(R.id.startupStep2Label);
        views.step2Detail = activity.findViewById(R.id.startupStep2Detail);
        views.step2Status = activity.findViewById(R.id.startupStep2Status);
        views.step3Badge = activity.findViewById(R.id.startupStep3Badge);
        views.step3Label = activity.findViewById(R.id.startupStep3Label);
        views.step3Detail = activity.findViewById(R.id.startupStep3Detail);
        views.step3Status = activity.findViewById(R.id.startupStep3Status);
        views.infoText = activity.findViewById(R.id.startupInfoText);
        if (views.infoText != null) {
            views.infoText.setMovementMethod(new ScrollingMovementMethod());
        }
        return activity.findViewById(R.id.startupBuildVersion);
    }
}
