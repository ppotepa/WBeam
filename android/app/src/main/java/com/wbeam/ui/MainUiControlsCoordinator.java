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
    private MainUiControlsCoordinator() {
    }

    @SuppressWarnings("java:S107")
    public static void loadSavedSettings(
            Spinner profileSpinner,
            Spinner encoderSpinner,
            Spinner cursorSpinner,
            SeekBar resolutionSeek,
            SeekBar fpsSeek,
            SeekBar bitrateSeek,
            String[] profileOptions,
            String[] encoderOptions,
            String[] cursorOptions,
            String defaultProfile,
            String preferredVideo,
            String defaultCursorMode,
            int defaultResScale,
            int defaultFps,
            int defaultBitrateMbps,
            CursorOverlayController cursorOverlayController,
            MainActivitySimpleMenuCoordinator.State simpleMenuState,
            Runnable enforceCursorPolicy,
            MainActivitySettingsInitializerHooksFactory.SettingsRefreshHandler refreshHandler
    ) {
        MainActivitySettingsInitializer.loadDefaults(
                profileSpinner,
                encoderSpinner,
                cursorSpinner,
                resolutionSeek,
                fpsSeek,
                bitrateSeek,
                profileOptions,
                encoderOptions,
                cursorOptions,
                defaultProfile,
                preferredVideo,
                defaultCursorMode,
                defaultResScale,
                defaultFps,
                defaultBitrateMbps,
                cursorOverlayController,
                simpleMenuState,
                MainActivitySettingsInitializerHooksFactory.create(
                        persist -> enforceCursorPolicy.run(),
                        refreshHandler
                )
        );
    }

    @SuppressWarnings("java:S107")
    public static void updateSettingValueLabels(
            TextView resValueText,
            TextView fpsValueText,
            TextView bitrateValueText,
            int scale,
            int fps,
            int bitrate,
            int width,
            int height
    ) {
        MainActivitySettingsPresenter.applySettingValueLabels(
                resValueText,
                fpsValueText,
                bitrateValueText,
                scale,
                fps,
                bitrate,
                width,
                height
        );
    }

    public static boolean updateIntraOnlyButton(
            Button intraOnlyButton,
            String selectedEncoder,
            boolean previousEnabled
    ) {
        return IntraOnlyButtonController.apply(intraOnlyButton, selectedEncoder, previousEnabled);
    }

    @SuppressWarnings("java:S107")
    public static void updateHostHint(
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
        HostHintPresenter.apply(
                new HostHintPresenter.Input()
                        .setHostHintText(hostHintText)
                        .setDaemonReachable(daemonReachable)
                        .setApiBase(apiBase)
                        .setDaemonHostName(daemonHostName)
                        .setDaemonStateUi(daemonStateUi)
                        .setDaemonService(daemonService)
                        .setSelectedProfile(selectedProfile)
                        .setCfg(cfg)
                        .setSelectedEncoder(selectedEncoder)
                        .setIntraOnlyEnabled(intraOnlyEnabled)
                        .setSelectedCursorMode(selectedCursorMode)
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

    @SuppressWarnings("java:S107")
    public static void toggleSimpleMenu(
            View simpleMenuPanel,
            Handler uiHandler,
            Runnable simpleMenuAutoHideTask,
            MainActivitySimpleMenuCoordinator.State simpleMenuState,
            String selectedEncoder,
            String preferredVideo,
            int selectedFps,
            Runnable refreshSimpleMenuButtons,
            Runnable scheduleSimpleMenuAutoHide
    ) {
        MainActivitySimpleMenuCoordinator.toggle(
                new MainActivitySimpleMenuCoordinator.ToggleInput()
                        .setSimpleMenuPanel(simpleMenuPanel)
                        .setUiHandler(uiHandler)
                        .setAutoHideTask(simpleMenuAutoHideTask)
                        .setState(simpleMenuState)
                        .setSelectedEncoder(selectedEncoder)
                        .setPreferredVideo(preferredVideo)
                        .setSelectedFps(selectedFps)
                        .setRefreshButtons(refreshSimpleMenuButtons::run)
                        .setScheduleAutoHide(scheduleSimpleMenuAutoHide::run)
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

    @SuppressWarnings("java:S107")
    public static void refreshSimpleMenuButtons(
            View simpleMenuPanel,
            Button simpleModeH265Button,
            Button simpleModeRawButton,
            String preferredVideo,
            String mode,
            Button simpleFps30Button,
            Button simpleFps45Button,
            Button simpleFps60Button,
            Button simpleFps90Button,
            Button simpleFps120Button,
            Button simpleFps144Button,
            int fps
    ) {
        MainActivitySimpleMenuCoordinator.refreshButtons(
                new MainActivitySimpleMenuCoordinator.RefreshButtonsInput()
                        .setSimpleMenuPanel(simpleMenuPanel)
                        .setSimpleModeH265Button(simpleModeH265Button)
                        .setSimpleModeRawButton(simpleModeRawButton)
                        .setPreferredVideo(preferredVideo)
                        .setMode(mode)
                        .setSimpleFps30Button(simpleFps30Button)
                        .setSimpleFps45Button(simpleFps45Button)
                        .setSimpleFps60Button(simpleFps60Button)
                        .setSimpleFps90Button(simpleFps90Button)
                        .setSimpleFps120Button(simpleFps120Button)
                        .setSimpleFps144Button(simpleFps144Button)
                        .setFps(fps)
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
