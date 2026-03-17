package com.wbeam.ui;

import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;

import com.wbeam.input.CursorOverlayController;
import com.wbeam.ui.state.MainUiState;
import com.wbeam.widget.FpsLossGraphView;

public final class MainViewBehaviorCoordinator {
    public interface SurfaceChangedHandler {
        void onChanged(Surface surface, boolean ready);
    }

    public interface SurfaceDestroyedHandler {
        void onDestroyed();
    }

    public interface UiTask {
        void run();
    }

    public interface CursorMotionHandler {
        void onMotion(float x, float y, int action);
    }

    public interface ModeSelectedHandler {
        void onMode(String mode);
    }

    public interface FpsSelectedHandler {
        void onFps(int fps);
    }

    public interface OverlayVisibilityApplier {
        void apply(boolean visible);
    }

    private MainViewBehaviorCoordinator() {
    }

    @SuppressWarnings("java:S107")
    public static final class SetupButtonsInput {
        private Button settingsCloseButton;
        private View simpleMenuPanel;
        private View debugInfoPanel;
        private Handler uiHandler;
        private Runnable debugInfoFadeTask;
        private float debugInfoAlphaTouch;
        private long debugInfoAlphaResetMs;
        private Button simpleModeH265Button;
        private Button simpleModeRawButton;
        private String preferredVideo;
        private ModeSelectedHandler onModeSelected;
        private Button simpleFps30Button;
        private Button simpleFps45Button;
        private Button simpleFps60Button;
        private Button simpleFps90Button;
        private Button simpleFps120Button;
        private Button simpleFps144Button;
        private FpsSelectedHandler onFpsSelected;
        private Button simpleApplyButton;
        private UiTask hideSettingsPanelTask;
        private UiTask scheduleSimpleMenuAutoHideTask;
        private UiTask onApplyAndStartTask;

        public SetupButtonsInput setSettingsCloseButton(Button value) { settingsCloseButton = value; return this; }
        public SetupButtonsInput setSimpleMenuPanel(View value) { simpleMenuPanel = value; return this; }
        public SetupButtonsInput setDebugInfoPanel(View value) { debugInfoPanel = value; return this; }
        public SetupButtonsInput setUiHandler(Handler value) { uiHandler = value; return this; }
        public SetupButtonsInput setDebugInfoFadeTask(Runnable value) { debugInfoFadeTask = value; return this; }
        public SetupButtonsInput setDebugInfoAlphaTouch(float value) { debugInfoAlphaTouch = value; return this; }
        public SetupButtonsInput setDebugInfoAlphaResetMs(long value) { debugInfoAlphaResetMs = value; return this; }
        public SetupButtonsInput setSimpleModeH265Button(Button value) { simpleModeH265Button = value; return this; }
        public SetupButtonsInput setSimpleModeRawButton(Button value) { simpleModeRawButton = value; return this; }
        public SetupButtonsInput setPreferredVideo(String value) { preferredVideo = value; return this; }
        public SetupButtonsInput setOnModeSelected(ModeSelectedHandler value) { onModeSelected = value; return this; }
        public SetupButtonsInput setSimpleFps30Button(Button value) { simpleFps30Button = value; return this; }
        public SetupButtonsInput setSimpleFps45Button(Button value) { simpleFps45Button = value; return this; }
        public SetupButtonsInput setSimpleFps60Button(Button value) { simpleFps60Button = value; return this; }
        public SetupButtonsInput setSimpleFps90Button(Button value) { simpleFps90Button = value; return this; }
        public SetupButtonsInput setSimpleFps120Button(Button value) { simpleFps120Button = value; return this; }
        public SetupButtonsInput setSimpleFps144Button(Button value) { simpleFps144Button = value; return this; }
        public SetupButtonsInput setOnFpsSelected(FpsSelectedHandler value) { onFpsSelected = value; return this; }
        public SetupButtonsInput setSimpleApplyButton(Button value) { simpleApplyButton = value; return this; }
        public SetupButtonsInput setHideSettingsPanelTask(UiTask value) { hideSettingsPanelTask = value; return this; }
        public SetupButtonsInput setScheduleSimpleMenuAutoHideTask(UiTask value) { scheduleSimpleMenuAutoHideTask = value; return this; }
        public SetupButtonsInput setOnApplyAndStartTask(UiTask value) { onApplyAndStartTask = value; return this; }
    }

    @SuppressWarnings("java:S107")
    public static final class BuildVariantUiInput {
        private boolean buildDebug;
        private MainUiState uiState;
        private FpsLossGraphView debugFpsGraphView;
        private int debugFpsGraphPoints;
        private View debugInfoPanel;
        private UiTask forceFullscreenTask;
        private OverlayVisibilityApplier overlayVisibilityApplier;
        private UiTask startDebugGraphSamplingTask;
        private UiTask refreshDebugInfoOverlayTask;
        private UiTask stopDebugGraphSamplingTask;

        public BuildVariantUiInput setBuildDebug(boolean value) { buildDebug = value; return this; }
        public BuildVariantUiInput setUiState(MainUiState value) { uiState = value; return this; }
        public BuildVariantUiInput setDebugFpsGraphView(FpsLossGraphView value) { debugFpsGraphView = value; return this; }
        public BuildVariantUiInput setDebugFpsGraphPoints(int value) { debugFpsGraphPoints = value; return this; }
        public BuildVariantUiInput setDebugInfoPanel(View value) { debugInfoPanel = value; return this; }
        public BuildVariantUiInput setForceFullscreenTask(UiTask value) { forceFullscreenTask = value; return this; }
        public BuildVariantUiInput setOverlayVisibilityApplier(OverlayVisibilityApplier value) { overlayVisibilityApplier = value; return this; }
        public BuildVariantUiInput setStartDebugGraphSamplingTask(UiTask value) { startDebugGraphSamplingTask = value; return this; }
        public BuildVariantUiInput setRefreshDebugInfoOverlayTask(UiTask value) { refreshDebugInfoOverlayTask = value; return this; }
        public BuildVariantUiInput setStopDebugGraphSamplingTask(UiTask value) { stopDebugGraphSamplingTask = value; return this; }
    }

    public static void setupSurfaceCallbacks(
            SurfaceView previewSurface,
            CursorOverlayController cursorOverlayController,
            SurfaceChangedHandler onSurfaceCreated,
            SurfaceChangedHandler onSurfaceChanged,
            SurfaceDestroyedHandler onSurfaceDestroyed,
            CursorMotionHandler onCursorOverlayMotion
    ) {
        MainActivitySurfaceSetup.Input input = new MainActivitySurfaceSetup.Input();
        input.setPreview(previewSurface);
        input.setOnSurfaceCreated(onSurfaceCreated::onChanged);
        input.setOnSurfaceChanged(onSurfaceChanged::onChanged);
        input.setOnSurfaceDestroyed(onSurfaceDestroyed::onDestroyed);
        input.setIsCursorOverlayEnabled(
                () -> cursorOverlayController != null && cursorOverlayController.isOverlayEnabled()
        );
        input.setOnCursorOverlayMotion(onCursorOverlayMotion::onMotion);
        MainActivitySurfaceSetup.setup(input);
    }

    public static void setupButtons(SetupButtonsInput input) {
        MainActivityButtonsSetup.setup(
                new MainActivityButtonsSetup.SetupInput()
                        .setSettingsCloseButton(input.settingsCloseButton)
                        .setSimpleMenuPanel(input.simpleMenuPanel)
                        .setDebugInfoPanel(input.debugInfoPanel)
                        .setUiHandler(input.uiHandler)
                        .setDebugInfoFadeTask(input.debugInfoFadeTask)
                        .setDebugInfoAlphaTouch(input.debugInfoAlphaTouch)
                        .setDebugInfoAlphaResetMs(input.debugInfoAlphaResetMs)
                        .setSimpleModeH265Button(input.simpleModeH265Button)
                        .setSimpleModeRawButton(input.simpleModeRawButton)
                        .setPreferredVideo(input.preferredVideo)
                        .setModeSelected(input.onModeSelected::onMode)
                        .setSimpleFps30Button(input.simpleFps30Button)
                        .setSimpleFps45Button(input.simpleFps45Button)
                        .setSimpleFps60Button(input.simpleFps60Button)
                        .setSimpleFps90Button(input.simpleFps90Button)
                        .setSimpleFps120Button(input.simpleFps120Button)
                        .setSimpleFps144Button(input.simpleFps144Button)
                        .setFpsSelected(input.onFpsSelected::onFps)
                        .setSimpleApplyButton(input.simpleApplyButton)
                        .setOnSettingsClose(input.hideSettingsPanelTask::run)
                        .setOnSimpleMenuTouchRefresh(input.scheduleSimpleMenuAutoHideTask::run)
                        .setOnSimpleApply(input.onApplyAndStartTask::run)
        );
    }

    public static void applyBuildVariantUi(BuildVariantUiInput input) {
        BuildVariantUiCoordinator.apply(
                input.buildDebug,
                input.uiState.debugOverlayVisible,
                input.debugFpsGraphView,
                input.debugFpsGraphPoints,
                input.debugInfoPanel,
                input.forceFullscreenTask::run,
                input.overlayVisibilityApplier::apply,
                input.startDebugGraphSamplingTask::run,
                input.refreshDebugInfoOverlayTask::run,
                input.stopDebugGraphSamplingTask::run
        );
    }

    public static void setDebugOverlayVisible(
            MainUiState uiState,
            boolean buildDebug,
            boolean visible,
            View debugInfoPanel,
            View perfHudPanel
    ) {
        uiState.debugOverlayVisible = visible;
        MainActivityRuntimeStateView.applyDebugOverlayVisibility(
                buildDebug,
                visible,
                debugInfoPanel,
                perfHudPanel
        );
    }

    public static void updateActionButtonsEnabled(
            boolean daemonReachable,
            Button quickStartButton,
            Button quickStopButton,
            Button quickTestButton,
            Button startButton,
            Button stopButton,
            Button testButton
    ) {
        MainActivityUiBinder.applyActionButtonsEnabled(
                daemonReachable,
                quickStartButton,
                quickStopButton,
                quickTestButton,
                startButton,
                stopButton,
                testButton
        );
    }

    public static void setDebugControlsVisible(
            MainUiState uiState,
            boolean visible,
            View debugControlsRow,
            Button testButton
    ) {
        uiState.debugControlsVisible = visible;
        MainActivityUiBinder.applyDebugControlsVisible(visible, debugControlsRow, testButton);
    }
}
