package com.wbeam.ui;

import android.widget.TextView;

public final class HostHintPresenter {
    public static final class Input {
        private TextView hostHintText;
        private boolean daemonReachable;
        private String apiBase;
        private String daemonHostName;
        private String daemonStateUi;
        private String daemonService;
        private String selectedProfile;
        private StreamConfigResolver.Resolved cfg;
        private String selectedEncoder;
        private boolean intraOnlyEnabled;
        private String selectedCursorMode;

        public Input setHostHintText(TextView value) { hostHintText = value; return this; }
        public Input setDaemonReachable(boolean value) { daemonReachable = value; return this; }
        public Input setApiBase(String value) { apiBase = value; return this; }
        public Input setDaemonHostName(String value) { daemonHostName = value; return this; }
        public Input setDaemonStateUi(String value) { daemonStateUi = value; return this; }
        public Input setDaemonService(String value) { daemonService = value; return this; }
        public Input setSelectedProfile(String value) { selectedProfile = value; return this; }
        public Input setCfg(StreamConfigResolver.Resolved value) { cfg = value; return this; }
        public Input setSelectedEncoder(String value) { selectedEncoder = value; return this; }
        public Input setIntraOnlyEnabled(boolean value) { intraOnlyEnabled = value; return this; }
        public Input setSelectedCursorMode(String value) { selectedCursorMode = value; return this; }
    }

    private HostHintPresenter() {
    }

    @SuppressWarnings("java:S107")
    public static void apply(Input input) {
        if (input.hostHintText == null) {
            return;
        }
        input.hostHintText.setText(MainActivitySettingsPresenter.buildHostHint(
                input.daemonReachable,
                input.apiBase,
                input.daemonHostName,
                input.daemonStateUi,
                input.daemonService,
                input.selectedProfile,
                input.cfg.width,
                input.cfg.height,
                input.cfg.fps,
                input.cfg.bitrateMbps,
                input.selectedEncoder,
                input.intraOnlyEnabled,
                input.selectedCursorMode
        ));
    }
}
