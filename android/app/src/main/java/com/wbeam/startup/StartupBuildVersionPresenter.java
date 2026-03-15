package com.wbeam.startup;

import android.widget.TextView;

public final class StartupBuildVersionPresenter {
    private StartupBuildVersionPresenter() {
    }

    public static void apply(TextView startupBuildVersionText, String buildRevision) {
        if (startupBuildVersionText == null) {
            return;
        }
        String rev = buildRevision == null ? "" : buildRevision.trim();
        if (rev.isEmpty()) {
            rev = "unknown";
        }
        startupBuildVersionText.setText("build " + rev);
    }
}
