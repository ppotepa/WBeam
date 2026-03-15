package com.wbeam.ui;

import android.widget.TextView;

public final class HostHintPresenter {
    private HostHintPresenter() {
    }

    public static void apply(
            TextView hostHintText,
            boolean daemonReachable,
            String apiBase,
            String daemonHostName,
            String daemonStateUi,
            String daemonService,
            String selectedProfile,
            StreamConfigResolver.Resolved cfg,
            String selectedEncoder,
            boolean intraOnlyEnabled,
            String selectedCursorMode
    ) {
        if (hostHintText == null) {
            return;
        }
        hostHintText.setText(MainActivitySettingsPresenter.buildHostHint(
                daemonReachable,
                apiBase,
                daemonHostName,
                daemonStateUi,
                daemonService,
                selectedProfile,
                cfg.width,
                cfg.height,
                cfg.fps,
                cfg.bitrateMbps,
                selectedEncoder,
                intraOnlyEnabled,
                selectedCursorMode
        ));
    }
}
