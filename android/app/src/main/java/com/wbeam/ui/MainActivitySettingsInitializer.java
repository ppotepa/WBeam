package com.wbeam.ui;

import android.widget.SeekBar;
import android.widget.Spinner;

import com.wbeam.input.CursorOverlayController;

public final class MainActivitySettingsInitializer {
    public interface Hooks {
        void enforceCursorOverlayPolicy(boolean persist);
        void refreshSettingsUi(boolean includeSimpleMenuButtons);
    }

    private MainActivitySettingsInitializer() {
    }

    public static final class DefaultsConfig {
        private Spinner profileSpinner;
        private Spinner encoderSpinner;
        private Spinner cursorSpinner;
        private SeekBar resolutionSeek;
        private SeekBar fpsSeek;
        private SeekBar bitrateSeek;
        private String[] profileOptions;
        private String[] encoderOptions;
        private String[] cursorOptions;
        private String defaultProfile;
        private String preferredVideo;
        private String defaultCursorMode;
        private int defaultResScale;
        private int defaultFps;
        private int defaultBitrateMbps;
        private CursorOverlayController cursorOverlayController;
        private MainActivitySimpleMenuCoordinator.State simpleMenuState;
        private Hooks hooks;

        public DefaultsConfig setProfileSpinner(Spinner profileSpinner) { this.profileSpinner = profileSpinner; return this; }
        public DefaultsConfig setEncoderSpinner(Spinner encoderSpinner) { this.encoderSpinner = encoderSpinner; return this; }
        public DefaultsConfig setCursorSpinner(Spinner cursorSpinner) { this.cursorSpinner = cursorSpinner; return this; }
        public DefaultsConfig setResolutionSeek(SeekBar resolutionSeek) { this.resolutionSeek = resolutionSeek; return this; }
        public DefaultsConfig setFpsSeek(SeekBar fpsSeek) { this.fpsSeek = fpsSeek; return this; }
        public DefaultsConfig setBitrateSeek(SeekBar bitrateSeek) { this.bitrateSeek = bitrateSeek; return this; }
        public DefaultsConfig setProfileOptions(String[] profileOptions) { this.profileOptions = profileOptions; return this; }
        public DefaultsConfig setEncoderOptions(String[] encoderOptions) { this.encoderOptions = encoderOptions; return this; }
        public DefaultsConfig setCursorOptions(String[] cursorOptions) { this.cursorOptions = cursorOptions; return this; }
        public DefaultsConfig setDefaultProfile(String defaultProfile) { this.defaultProfile = defaultProfile; return this; }
        public DefaultsConfig setPreferredVideo(String preferredVideo) { this.preferredVideo = preferredVideo; return this; }
        public DefaultsConfig setDefaultCursorMode(String defaultCursorMode) { this.defaultCursorMode = defaultCursorMode; return this; }
        public DefaultsConfig setDefaultResScale(int defaultResScale) { this.defaultResScale = defaultResScale; return this; }
        public DefaultsConfig setDefaultFps(int defaultFps) { this.defaultFps = defaultFps; return this; }
        public DefaultsConfig setDefaultBitrateMbps(int defaultBitrateMbps) { this.defaultBitrateMbps = defaultBitrateMbps; return this; }
        public DefaultsConfig setCursorOverlayController(CursorOverlayController cursorOverlayController) { this.cursorOverlayController = cursorOverlayController; return this; }
        public DefaultsConfig setSimpleMenuState(MainActivitySimpleMenuCoordinator.State simpleMenuState) { this.simpleMenuState = simpleMenuState; return this; }
        public DefaultsConfig setHooks(Hooks hooks) { this.hooks = hooks; return this; }
    }

    public static void loadDefaults(DefaultsConfig config) {
        MainActivitySettingsPresenter.applyDefaultSettings(
                config.profileSpinner,
                config.encoderSpinner,
                config.cursorSpinner,
                config.resolutionSeek,
                config.fpsSeek,
                config.bitrateSeek,
                config.profileOptions,
                config.encoderOptions,
                config.cursorOptions,
                config.defaultProfile,
                config.preferredVideo,
                config.defaultCursorMode,
                config.defaultResScale,
                config.defaultFps,
                config.defaultBitrateMbps
        );
        if (config.cursorOverlayController != null) {
            config.cursorOverlayController.resetEnabledDefault();
        }
        config.hooks.enforceCursorOverlayPolicy(false);
        config.simpleMenuState.setMode(config.preferredVideo);
        config.simpleMenuState.setFps(config.defaultFps);
        config.simpleMenuState.setVisible(false);
        config.hooks.refreshSettingsUi(true);
    }
}
