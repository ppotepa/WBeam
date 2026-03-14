package com.wbeam.ui;

import android.widget.TextView;

public final class HostHintPresenter {
    private HostHintPresenter() {
    }

    @SuppressWarnings("java:S107")
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
                new MainActivitySettingsPresenter.HostHintInput()
                        .setDaemonReachable(daemonReachable)
                        .setApiBase(apiBase)
                        .setDaemonHostName(daemonHostName)
                        .setDaemonStateUi(daemonStateUi)
                        .setDaemonService(daemonService)
                        .setSelectedProfile(selectedProfile)
                        .setWidth(cfg.width)
                        .setHeight(cfg.height)
                        .setFps(cfg.fps)
                        .setBitrateMbps(cfg.bitrateMbps)
                        .setSelectedEncoder(selectedEncoder)
                        .setIntraOnlyEnabled(intraOnlyEnabled)
                        .setCursorMode(selectedCursorMode)
        ));
    }
}
