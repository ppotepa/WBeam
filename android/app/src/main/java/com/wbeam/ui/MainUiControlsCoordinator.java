package com.wbeam.ui;

import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.wbeam.input.CursorOverlayController;
import com.wbeam.input.ImmersiveModeController;

public final class MainUiControlsCoordinator {
    public static final class LoadSavedSettingsRequest {
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
        private Runnable enforceCursorPolicy;
        private MainActivitySettingsInitializerHooksFactory.SettingsRefreshHandler refreshHandler;

        public LoadSavedSettingsRequest setProfileSpinner(Spinner profileSpinner) {
            this.profileSpinner = profileSpinner;
            return this;
        }

        public LoadSavedSettingsRequest setEncoderSpinner(Spinner encoderSpinner) {
            this.encoderSpinner = encoderSpinner;
            return this;
        }

        public LoadSavedSettingsRequest setCursorSpinner(Spinner cursorSpinner) {
            this.cursorSpinner = cursorSpinner;
            return this;
        }

        public LoadSavedSettingsRequest setResolutionSeek(SeekBar resolutionSeek) {
            this.resolutionSeek = resolutionSeek;
            return this;
        }

        public LoadSavedSettingsRequest setFpsSeek(SeekBar fpsSeek) {
            this.fpsSeek = fpsSeek;
            return this;
        }

        public LoadSavedSettingsRequest setBitrateSeek(SeekBar bitrateSeek) {
            this.bitrateSeek = bitrateSeek;
            return this;
        }

        public LoadSavedSettingsRequest setProfileOptions(String[] profileOptions) {
            this.profileOptions = profileOptions;
            return this;
        }

        public LoadSavedSettingsRequest setEncoderOptions(String[] encoderOptions) {
            this.encoderOptions = encoderOptions;
            return this;
        }

        public LoadSavedSettingsRequest setCursorOptions(String[] cursorOptions) {
            this.cursorOptions = cursorOptions;
            return this;
        }

        public LoadSavedSettingsRequest setDefaultProfile(String defaultProfile) {
            this.defaultProfile = defaultProfile;
            return this;
        }

        public LoadSavedSettingsRequest setPreferredVideo(String preferredVideo) {
            this.preferredVideo = preferredVideo;
            return this;
        }

        public LoadSavedSettingsRequest setDefaultCursorMode(String defaultCursorMode) {
            this.defaultCursorMode = defaultCursorMode;
            return this;
        }

        public LoadSavedSettingsRequest setDefaultResScale(int defaultResScale) {
            this.defaultResScale = defaultResScale;
            return this;
        }

        public LoadSavedSettingsRequest setDefaultFps(int defaultFps) {
            this.defaultFps = defaultFps;
            return this;
        }

        public LoadSavedSettingsRequest setDefaultBitrateMbps(int defaultBitrateMbps) {
            this.defaultBitrateMbps = defaultBitrateMbps;
            return this;
        }

        public LoadSavedSettingsRequest setCursorOverlayController(
                CursorOverlayController cursorOverlayController
        ) {
            this.cursorOverlayController = cursorOverlayController;
            return this;
        }

        public LoadSavedSettingsRequest setSimpleMenuState(
                MainActivitySimpleMenuCoordinator.State simpleMenuState
        ) {
            this.simpleMenuState = simpleMenuState;
            return this;
        }

        public LoadSavedSettingsRequest setEnforceCursorPolicy(Runnable enforceCursorPolicy) {
            this.enforceCursorPolicy = enforceCursorPolicy;
            return this;
        }

        public LoadSavedSettingsRequest setRefreshHandler(
                MainActivitySettingsInitializerHooksFactory.SettingsRefreshHandler refreshHandler
        ) {
            this.refreshHandler = refreshHandler;
            return this;
        }
    }

    public static final class SettingValueLabelsRequest {
        private TextView resValueText;
        private TextView fpsValueText;
        private TextView bitrateValueText;
        private int scale;
        private int fps;
        private int bitrate;
        private int width;
        private int height;

        public SettingValueLabelsRequest setResValueText(TextView resValueText) {
            this.resValueText = resValueText;
            return this;
        }

        public SettingValueLabelsRequest setFpsValueText(TextView fpsValueText) {
            this.fpsValueText = fpsValueText;
            return this;
        }

        public SettingValueLabelsRequest setBitrateValueText(TextView bitrateValueText) {
            this.bitrateValueText = bitrateValueText;
            return this;
        }

        public SettingValueLabelsRequest setScale(int scale) {
            this.scale = scale;
            return this;
        }

        public SettingValueLabelsRequest setFps(int fps) {
            this.fps = fps;
            return this;
        }

        public SettingValueLabelsRequest setBitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        public SettingValueLabelsRequest setWidth(int width) {
            this.width = width;
            return this;
        }

        public SettingValueLabelsRequest setHeight(int height) {
            this.height = height;
            return this;
        }
    }

    public static final class HostHintRequest {
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

        public HostHintRequest setHostHintText(TextView hostHintText) {
            this.hostHintText = hostHintText;
            return this;
        }

        public HostHintRequest setDaemonReachable(boolean daemonReachable) {
            this.daemonReachable = daemonReachable;
            return this;
        }

        public HostHintRequest setApiBase(String apiBase) {
            this.apiBase = apiBase;
            return this;
        }

        public HostHintRequest setDaemonHostName(String daemonHostName) {
            this.daemonHostName = daemonHostName;
            return this;
        }

        public HostHintRequest setDaemonStateUi(String daemonStateUi) {
            this.daemonStateUi = daemonStateUi;
            return this;
        }

        public HostHintRequest setDaemonService(String daemonService) {
            this.daemonService = daemonService;
            return this;
        }

        public HostHintRequest setSelectedProfile(String selectedProfile) {
            this.selectedProfile = selectedProfile;
            return this;
        }

        public HostHintRequest setCfg(StreamConfigResolver.Resolved cfg) {
            this.cfg = cfg;
            return this;
        }

        public HostHintRequest setSelectedEncoder(String selectedEncoder) {
            this.selectedEncoder = selectedEncoder;
            return this;
        }

        public HostHintRequest setIntraOnlyEnabled(boolean intraOnlyEnabled) {
            this.intraOnlyEnabled = intraOnlyEnabled;
            return this;
        }

        public HostHintRequest setSelectedCursorMode(String selectedCursorMode) {
            this.selectedCursorMode = selectedCursorMode;
            return this;
        }
    }

    public static final class SimpleMenuToggleRequest {
        private View simpleMenuPanel;
        private Handler uiHandler;
        private Runnable simpleMenuAutoHideTask;
        private MainActivitySimpleMenuCoordinator.State simpleMenuState;
        private String selectedEncoder;
        private String preferredVideo;
        private int selectedFps;
        private Runnable refreshSimpleMenuButtons;
        private Runnable scheduleSimpleMenuAutoHide;

        public SimpleMenuToggleRequest setSimpleMenuPanel(View simpleMenuPanel) {
            this.simpleMenuPanel = simpleMenuPanel;
            return this;
        }

        public SimpleMenuToggleRequest setUiHandler(Handler uiHandler) {
            this.uiHandler = uiHandler;
            return this;
        }

        public SimpleMenuToggleRequest setSimpleMenuAutoHideTask(Runnable simpleMenuAutoHideTask) {
            this.simpleMenuAutoHideTask = simpleMenuAutoHideTask;
            return this;
        }

        public SimpleMenuToggleRequest setSimpleMenuState(
                MainActivitySimpleMenuCoordinator.State simpleMenuState
        ) {
            this.simpleMenuState = simpleMenuState;
            return this;
        }

        public SimpleMenuToggleRequest setSelectedEncoder(String selectedEncoder) {
            this.selectedEncoder = selectedEncoder;
            return this;
        }

        public SimpleMenuToggleRequest setPreferredVideo(String preferredVideo) {
            this.preferredVideo = preferredVideo;
            return this;
        }

        public SimpleMenuToggleRequest setSelectedFps(int selectedFps) {
            this.selectedFps = selectedFps;
            return this;
        }

        public SimpleMenuToggleRequest setRefreshSimpleMenuButtons(
                Runnable refreshSimpleMenuButtons
        ) {
            this.refreshSimpleMenuButtons = refreshSimpleMenuButtons;
            return this;
        }

        public SimpleMenuToggleRequest setScheduleSimpleMenuAutoHide(
                Runnable scheduleSimpleMenuAutoHide
        ) {
            this.scheduleSimpleMenuAutoHide = scheduleSimpleMenuAutoHide;
            return this;
        }
    }

    public static final class SimpleMenuButtonsRequest {
        private View simpleMenuPanel;
        private Button simpleModeH265Button;
        private Button simpleModeRawButton;
        private String preferredVideo;
        private String mode;
        private Button simpleFps30Button;
        private Button simpleFps45Button;
        private Button simpleFps60Button;
        private Button simpleFps90Button;
        private Button simpleFps120Button;
        private Button simpleFps144Button;
        private int fps;

        public SimpleMenuButtonsRequest setSimpleMenuPanel(View simpleMenuPanel) {
            this.simpleMenuPanel = simpleMenuPanel;
            return this;
        }

        public SimpleMenuButtonsRequest setSimpleModeH265Button(Button simpleModeH265Button) {
            this.simpleModeH265Button = simpleModeH265Button;
            return this;
        }

        public SimpleMenuButtonsRequest setSimpleModeRawButton(Button simpleModeRawButton) {
            this.simpleModeRawButton = simpleModeRawButton;
            return this;
        }

        public SimpleMenuButtonsRequest setPreferredVideo(String preferredVideo) {
            this.preferredVideo = preferredVideo;
            return this;
        }

        public SimpleMenuButtonsRequest setMode(String mode) {
            this.mode = mode;
            return this;
        }

        public SimpleMenuButtonsRequest setSimpleFps30Button(Button simpleFps30Button) {
            this.simpleFps30Button = simpleFps30Button;
            return this;
        }

        public SimpleMenuButtonsRequest setSimpleFps45Button(Button simpleFps45Button) {
            this.simpleFps45Button = simpleFps45Button;
            return this;
        }

        public SimpleMenuButtonsRequest setSimpleFps60Button(Button simpleFps60Button) {
            this.simpleFps60Button = simpleFps60Button;
            return this;
        }

        public SimpleMenuButtonsRequest setSimpleFps90Button(Button simpleFps90Button) {
            this.simpleFps90Button = simpleFps90Button;
            return this;
        }

        public SimpleMenuButtonsRequest setSimpleFps120Button(Button simpleFps120Button) {
            this.simpleFps120Button = simpleFps120Button;
            return this;
        }

        public SimpleMenuButtonsRequest setSimpleFps144Button(Button simpleFps144Button) {
            this.simpleFps144Button = simpleFps144Button;
            return this;
        }

        public SimpleMenuButtonsRequest setFps(int fps) {
            this.fps = fps;
            return this;
        }
    }

    private MainUiControlsCoordinator() {
    }

    public static void loadSavedSettings(LoadSavedSettingsRequest request) {
        MainActivitySettingsInitializer.loadDefaults(
                request.profileSpinner,
                request.encoderSpinner,
                request.cursorSpinner,
                request.resolutionSeek,
                request.fpsSeek,
                request.bitrateSeek,
                request.profileOptions,
                request.encoderOptions,
                request.cursorOptions,
                request.defaultProfile,
                request.preferredVideo,
                request.defaultCursorMode,
                request.defaultResScale,
                request.defaultFps,
                request.defaultBitrateMbps,
                request.cursorOverlayController,
                request.simpleMenuState,
                MainActivitySettingsInitializerHooksFactory.create(
                        persist -> request.enforceCursorPolicy.run(),
                        request.refreshHandler
                )
        );
    }

    public static void updateSettingValueLabels(SettingValueLabelsRequest request) {
        MainActivitySettingsPresenter.applySettingValueLabels(
                new MainActivitySettingsPresenter.ValueLabelsInput()
                        .setResValueText(request.resValueText)
                        .setFpsValueText(request.fpsValueText)
                        .setBitrateValueText(request.bitrateValueText)
                        .setResolutionScalePercent(request.scale)
                        .setFps(request.fps)
                        .setBitrateMbps(request.bitrate)
                        .setWidth(request.width)
                        .setHeight(request.height)
        );
    }

    public static boolean updateIntraOnlyButton(
            Button intraOnlyButton,
            String selectedEncoder,
            boolean previousEnabled
    ) {
        return IntraOnlyButtonController.apply(intraOnlyButton, selectedEncoder, previousEnabled);
    }

    public static void updateHostHint(HostHintRequest request) {
        HostHintPresenter.apply(
                request.hostHintText,
                request.daemonReachable,
                request.apiBase,
                request.daemonHostName,
                request.daemonStateUi,
                request.daemonService,
                request.selectedProfile,
                request.cfg,
                request.selectedEncoder,
                request.intraOnlyEnabled,
                request.selectedCursorMode
        );
    }

    public static void toggleSettingsPanel(SettingsPanelController settingsPanelController) {
        if (settingsPanelController != null) {
            settingsPanelController.toggle();
        }
    }

    public static void showSettingsPanel(SettingsPanelController settingsPanelController) {
        if (settingsPanelController != null) {
            settingsPanelController.show();
        }
    }

    public static void hideSettingsPanel(SettingsPanelController settingsPanelController) {
        if (settingsPanelController != null) {
            settingsPanelController.hide();
        }
    }

    public static void toggleFullscreen(
            ImmersiveModeController immersiveModeController,
            boolean buildDebug,
            View topBar,
            View statusPanel,
            Runnable hideSettingsPanel
    ) {
        if (immersiveModeController == null) {
            return;
        }
        immersiveModeController.setFullscreen(
                !immersiveModeController.isFullscreen(),
                buildDebug,
                topBar,
                statusPanel,
                hideSettingsPanel::run
        );
    }

    public static void setFullscreen(
            ImmersiveModeController immersiveModeController,
            boolean enable,
            boolean buildDebug,
            View topBar,
            View statusPanel,
            Runnable hideSettingsPanel
    ) {
        if (immersiveModeController != null) {
            immersiveModeController.setFullscreen(
                    enable,
                    buildDebug,
                    topBar,
                    statusPanel,
                    hideSettingsPanel::run
            );
        }
    }

    public static void enforceImmersiveModeIfNeeded(ImmersiveModeController immersiveModeController) {
        if (immersiveModeController != null) {
            immersiveModeController.enforceImmersiveModeIfNeeded();
        }
    }

    public static void setScreenAlwaysOn(
            ImmersiveModeController immersiveModeController,
            boolean enable
    ) {
        if (immersiveModeController != null) {
            immersiveModeController.setScreenAlwaysOn(enable);
        }
    }

    public static void selectSimpleFps(
            int fps,
            MainActivitySimpleMenuCoordinator.State simpleMenuState,
            Runnable refreshSimpleMenuButtons,
            Runnable scheduleSimpleMenuAutoHide
    ) {
        MainActivitySimpleMenuCoordinator.selectFps(
                fps,
                simpleMenuState,
                refreshSimpleMenuButtons::run,
                scheduleSimpleMenuAutoHide::run
        );
    }

    public static void showSimpleMenu(
            View simpleMenuPanel,
            String selectedEncoder,
            String preferredVideo,
            int selectedFps,
            MainActivitySimpleMenuCoordinator.State simpleMenuState,
            Runnable refreshSimpleMenuButtons,
            Runnable scheduleSimpleMenuAutoHide
    ) {
        MainActivitySimpleMenuCoordinator.show(
                simpleMenuPanel,
                selectedEncoder,
                preferredVideo,
                selectedFps,
                simpleMenuState,
                refreshSimpleMenuButtons::run,
                scheduleSimpleMenuAutoHide::run
        );
    }

    public static void hideSimpleMenu(
            View simpleMenuPanel,
            Handler uiHandler,
            Runnable simpleMenuAutoHideTask,
            MainActivitySimpleMenuCoordinator.State simpleMenuState
    ) {
        MainActivitySimpleMenuCoordinator.hide(
                simpleMenuPanel,
                uiHandler,
                simpleMenuAutoHideTask,
                simpleMenuState
        );
    }

    public static void toggleSimpleMenu(SimpleMenuToggleRequest request) {
        MainActivitySimpleMenuCoordinator.toggle(
                request.simpleMenuPanel,
                request.uiHandler,
                request.simpleMenuAutoHideTask,
                request.simpleMenuState,
                request.selectedEncoder,
                request.preferredVideo,
                request.selectedFps,
                request.refreshSimpleMenuButtons::run,
                request.scheduleSimpleMenuAutoHide::run
        );
    }

    public static void scheduleSimpleMenuAutoHide(
            View simpleMenuPanel,
            Handler uiHandler,
            Runnable simpleMenuAutoHideTask,
            boolean simpleMenuVisible,
            long autoHideMs
    ) {
        MainActivitySimpleMenuCoordinator.scheduleAutoHide(
                simpleMenuPanel,
                uiHandler,
                simpleMenuAutoHideTask,
                simpleMenuVisible,
                autoHideMs
        );
    }

    public static void applySimpleMenuToSettings(
            Spinner encoderSpinner,
            SeekBar fpsSeek,
            String[] encoderOptions,
            String preferredVideo,
            String mode,
            int fps
    ) {
        MainActivitySimpleMenuCoordinator.applyToSettings(
                encoderSpinner,
                fpsSeek,
                encoderOptions,
                preferredVideo,
                mode,
                fps
        );
    }

    public static void refreshSimpleMenuButtons(SimpleMenuButtonsRequest request) {
        MainActivitySimpleMenuCoordinator.refreshButtons(
                request.simpleMenuPanel,
                request.simpleModeH265Button,
                request.simpleModeRawButton,
                request.preferredVideo,
                request.mode,
                request.simpleFps30Button,
                request.simpleFps45Button,
                request.simpleFps60Button,
                request.simpleFps90Button,
                request.simpleFps120Button,
                request.simpleFps144Button,
                request.fps
        );
    }

    public static void toggleCursorOverlayMode(
            CursorOverlayController cursorOverlayController,
            String selectedCursorMode,
            Runnable enforceCursorOverlayPolicy
    ) {
        CursorOverlayUiCoordinator.toggleMode(
                cursorOverlayController,
                selectedCursorMode,
                enforceCursorOverlayPolicy::run
        );
    }

    public static void enforceCursorOverlayPolicy(
            CursorOverlayController cursorOverlayController,
            String selectedCursorMode,
            boolean persist,
            Runnable updateHostHint
    ) {
        CursorOverlayUiCoordinator.applyPolicy(
                cursorOverlayController,
                selectedCursorMode,
                persist,
                updateHostHint::run
        );
    }

    public static void updateCursorOverlay(
            CursorOverlayController cursorOverlayController,
            float x,
            float y,
            int action
    ) {
        CursorOverlayUiCoordinator.updateOverlay(cursorOverlayController, x, y, action);
    }

    public static void hideCursorOverlay(CursorOverlayController cursorOverlayController) {
        CursorOverlayUiCoordinator.hideOverlay(cursorOverlayController);
    }
}
