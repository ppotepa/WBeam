package com.wbeam.ui;

import android.os.Build;
import android.widget.Button;

/**
 * Applies state and styling for the All-Intra toggle button.
 */
public final class IntraOnlyButtonController {
    private IntraOnlyButtonController() {}

    public static boolean apply(Button intraOnlyButton, String selectedEncoder, boolean intraOnlyEnabled) {
        if (intraOnlyButton == null) {
            return intraOnlyEnabled;
        }
        boolean supportsIntra = "h265".equals(selectedEncoder);
        boolean enabled = supportsIntra && intraOnlyEnabled;

        intraOnlyButton.setEnabled(supportsIntra);
        intraOnlyButton.setAlpha(supportsIntra ? 1.0f : 0.45f);
        String buttonText;
        if (enabled) {
            buttonText = "All-Intra: ON  \u2014 zero artifacts (HEVC only)";
        } else if (supportsIntra) {
            buttonText = "All-Intra: OFF";
        } else {
            buttonText = "All-Intra: N/A";
        }
        intraOnlyButton.setText(buttonText);

        int buttonColor = enabled ? 0xFF16A34A : 0xFF374151;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            intraOnlyButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(buttonColor));
        } else {
            intraOnlyButton.setBackgroundColor(buttonColor);
        }
        return enabled;
    }
}
