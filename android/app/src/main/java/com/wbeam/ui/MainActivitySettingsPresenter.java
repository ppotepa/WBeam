package com.wbeam.ui;

import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

public final class MainActivitySettingsPresenter {
    static final class ValueLabelsInput {
        private TextView resValueText;
        private TextView fpsValueText;
        private TextView bitrateValueText;
        private int resolutionScalePercent;
        private int fps;
        private int bitrateMbps;
        private int width;
        private int height;

        ValueLabelsInput setResValueText(TextView resValueText) {
            this.resValueText = resValueText;
            return this;
        }

        ValueLabelsInput setFpsValueText(TextView fpsValueText) {
            this.fpsValueText = fpsValueText;
            return this;
        }

        ValueLabelsInput setBitrateValueText(TextView bitrateValueText) {
            this.bitrateValueText = bitrateValueText;
            return this;
        }

        ValueLabelsInput setResolutionScalePercent(int resolutionScalePercent) {
            this.resolutionScalePercent = resolutionScalePercent;
            return this;
        }

        ValueLabelsInput setFps(int fps) {
            this.fps = fps;
            return this;
        }

        ValueLabelsInput setBitrateMbps(int bitrateMbps) {
            this.bitrateMbps = bitrateMbps;
            return this;
        }

        ValueLabelsInput setWidth(int width) {
            this.width = width;
            return this;
        }

        ValueLabelsInput setHeight(int height) {
            this.height = height;
            return this;
        }
    }

    static final class DefaultSettingsInput {
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

        DefaultSettingsInput setProfileSpinner(Spinner profileSpinner) {
            this.profileSpinner = profileSpinner;
            return this;
        }

        DefaultSettingsInput setEncoderSpinner(Spinner encoderSpinner) {
            this.encoderSpinner = encoderSpinner;
            return this;
        }

        DefaultSettingsInput setCursorSpinner(Spinner cursorSpinner) {
            this.cursorSpinner = cursorSpinner;
            return this;
        }

        DefaultSettingsInput setResolutionSeek(SeekBar resolutionSeek) {
            this.resolutionSeek = resolutionSeek;
            return this;
        }

        DefaultSettingsInput setFpsSeek(SeekBar fpsSeek) {
            this.fpsSeek = fpsSeek;
            return this;
        }

        DefaultSettingsInput setBitrateSeek(SeekBar bitrateSeek) {
            this.bitrateSeek = bitrateSeek;
            return this;
        }

        DefaultSettingsInput setProfileOptions(String[] profileOptions) {
            this.profileOptions = profileOptions;
            return this;
        }

        DefaultSettingsInput setEncoderOptions(String[] encoderOptions) {
            this.encoderOptions = encoderOptions;
            return this;
        }

        DefaultSettingsInput setCursorOptions(String[] cursorOptions) {
            this.cursorOptions = cursorOptions;
            return this;
        }

        DefaultSettingsInput setDefaultProfile(String defaultProfile) {
            this.defaultProfile = defaultProfile;
            return this;
        }

        DefaultSettingsInput setPreferredVideo(String preferredVideo) {
            this.preferredVideo = preferredVideo;
            return this;
        }

        DefaultSettingsInput setDefaultCursorMode(String defaultCursorMode) {
            this.defaultCursorMode = defaultCursorMode;
            return this;
        }

        DefaultSettingsInput setDefaultResScale(int defaultResScale) {
            this.defaultResScale = defaultResScale;
            return this;
        }

        DefaultSettingsInput setDefaultFps(int defaultFps) {
            this.defaultFps = defaultFps;
            return this;
        }

        DefaultSettingsInput setDefaultBitrateMbps(int defaultBitrateMbps) {
            this.defaultBitrateMbps = defaultBitrateMbps;
            return this;
        }
    }

    static final class SimpleMenuButtonsInput {
        private Button simpleModePreferredButton;
        private Button simpleModeRawButton;
        private String preferredVideo;
        private String simpleMode;
        private Button simpleFps30Button;
        private Button simpleFps45Button;
        private Button simpleFps60Button;
        private Button simpleFps90Button;
        private Button simpleFps120Button;
        private Button simpleFps144Button;
        private int simpleFps;

        SimpleMenuButtonsInput setSimpleModePreferredButton(Button simpleModePreferredButton) {
            this.simpleModePreferredButton = simpleModePreferredButton;
            return this;
        }

        SimpleMenuButtonsInput setSimpleModeRawButton(Button simpleModeRawButton) {
            this.simpleModeRawButton = simpleModeRawButton;
            return this;
        }

        SimpleMenuButtonsInput setPreferredVideo(String preferredVideo) {
            this.preferredVideo = preferredVideo;
            return this;
        }

        SimpleMenuButtonsInput setSimpleMode(String simpleMode) {
            this.simpleMode = simpleMode;
            return this;
        }

        SimpleMenuButtonsInput setSimpleFps30Button(Button simpleFps30Button) {
            this.simpleFps30Button = simpleFps30Button;
            return this;
        }

        SimpleMenuButtonsInput setSimpleFps45Button(Button simpleFps45Button) {
            this.simpleFps45Button = simpleFps45Button;
            return this;
        }

        SimpleMenuButtonsInput setSimpleFps60Button(Button simpleFps60Button) {
            this.simpleFps60Button = simpleFps60Button;
            return this;
        }

        SimpleMenuButtonsInput setSimpleFps90Button(Button simpleFps90Button) {
            this.simpleFps90Button = simpleFps90Button;
            return this;
        }

        SimpleMenuButtonsInput setSimpleFps120Button(Button simpleFps120Button) {
            this.simpleFps120Button = simpleFps120Button;
            return this;
        }

        SimpleMenuButtonsInput setSimpleFps144Button(Button simpleFps144Button) {
            this.simpleFps144Button = simpleFps144Button;
            return this;
        }

        SimpleMenuButtonsInput setSimpleFps(int simpleFps) {
            this.simpleFps = simpleFps;
            return this;
        }
    }

    static final class HostHintInput {
        private boolean daemonReachable;
        private String apiBase;
        private String daemonHostName;
        private String daemonStateUi;
        private String daemonService;
        private String selectedProfile;
        private int width;
        private int height;
        private int fps;
        private int bitrateMbps;
        private String selectedEncoder;
        private boolean intraOnlyEnabled;
        private String cursorMode;

        HostHintInput setDaemonReachable(boolean daemonReachable) {
            this.daemonReachable = daemonReachable;
            return this;
        }

        HostHintInput setApiBase(String apiBase) {
            this.apiBase = apiBase;
            return this;
        }

        HostHintInput setDaemonHostName(String daemonHostName) {
            this.daemonHostName = daemonHostName;
            return this;
        }

        HostHintInput setDaemonStateUi(String daemonStateUi) {
            this.daemonStateUi = daemonStateUi;
            return this;
        }

        HostHintInput setDaemonService(String daemonService) {
            this.daemonService = daemonService;
            return this;
        }

        HostHintInput setSelectedProfile(String selectedProfile) {
            this.selectedProfile = selectedProfile;
            return this;
        }

        HostHintInput setWidth(int width) {
            this.width = width;
            return this;
        }

        HostHintInput setHeight(int height) {
            this.height = height;
            return this;
        }

        HostHintInput setFps(int fps) {
            this.fps = fps;
            return this;
        }

        HostHintInput setBitrateMbps(int bitrateMbps) {
            this.bitrateMbps = bitrateMbps;
            return this;
        }

        HostHintInput setSelectedEncoder(String selectedEncoder) {
            this.selectedEncoder = selectedEncoder;
            return this;
        }

        HostHintInput setIntraOnlyEnabled(boolean intraOnlyEnabled) {
            this.intraOnlyEnabled = intraOnlyEnabled;
            return this;
        }

        HostHintInput setCursorMode(String cursorMode) {
            this.cursorMode = cursorMode;
            return this;
        }
    }

    private MainActivitySettingsPresenter() {
    }

    public static void applySettingValueLabels(ValueLabelsInput input) {
        input.resValueText.setText(SettingsUiSupport.resolutionValueLabel(
                input.resolutionScalePercent,
                input.width,
                input.height
        ));
        input.fpsValueText.setText(SettingsUiSupport.fpsValueLabel(input.fps));
        input.bitrateValueText.setText(SettingsUiSupport.bitrateValueLabel(input.bitrateMbps));
    }

    public static void applyDefaultSettings(DefaultSettingsInput input) {
        SettingsUiSupport.setSpinnerSelection(
                input.profileSpinner,
                input.profileOptions,
                input.defaultProfile
        );
        SettingsUiSupport.setSpinnerSelection(
                input.encoderSpinner,
                input.encoderOptions,
                input.preferredVideo
        );
        SettingsUiSupport.setSpinnerSelection(
                input.cursorSpinner,
                input.cursorOptions,
                input.defaultCursorMode
        );
        input.resolutionSeek.setProgress(
                SettingsSelectionReader.clamp(input.defaultResScale, 50, 100) - 50
        );
        input.fpsSeek.setProgress(SettingsSelectionReader.clamp(input.defaultFps, 24, 144) - 24);
        input.bitrateSeek.setProgress(
                SettingsSelectionReader.clamp(input.defaultBitrateMbps, 5, 300) - 5
        );
    }

    public static void applySimpleMenuSettings(
            Spinner encoderSpinner,
            SeekBar fpsSeek,
            String[] encoderOptions,
            String preferredVideo,
            String simpleMode,
            int simpleFps
    ) {
        String selectedEncoder = SimpleMenuUi.encoderFromMode(simpleMode, preferredVideo);
        int selectedFps = SimpleMenuUi.clampSimpleFps(simpleFps);
        SettingsUiSupport.setSpinnerSelection(encoderSpinner, encoderOptions, selectedEncoder);
        fpsSeek.setProgress(SettingsSelectionReader.clamp(selectedFps, 24, 144) - 24);
    }

    public static String simpleMenuModeFromSelection(String selectedEncoder, String preferredVideo) {
        return SimpleMenuUi.modeFromEncoder(selectedEncoder, preferredVideo);
    }

    public static int simpleMenuFpsFromSelection(int selectedFps) {
        return SimpleMenuUi.clampSimpleFps(selectedFps);
    }

    public static void applySimpleMenuButtons(SimpleMenuButtonsInput input) {
        SimpleMenuUi.applyModeButtons(
                input.simpleModePreferredButton,
                input.simpleModeRawButton,
                input.preferredVideo,
                input.simpleMode
        );
        SimpleMenuUi.applyFpsButtons(
                input.simpleFps30Button,
                input.simpleFps45Button,
                input.simpleFps60Button,
                input.simpleFps90Button,
                input.simpleFps120Button,
                input.simpleFps144Button,
                input.simpleFps
        );
    }

    public static String buildHostHint(HostHintInput input) {
        return StatusTextFormatter.buildHostHintText(
                input.daemonReachable,
                input.apiBase,
                input.daemonHostName,
                input.daemonStateUi,
                input.daemonService,
                input.selectedProfile,
                input.width,
                input.height,
                input.fps,
                input.bitrateMbps,
                input.selectedEncoder,
                input.intraOnlyEnabled,
                input.cursorMode
        );
    }
}
