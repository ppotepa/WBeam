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

    public static void setupSurfaceCallbacks(
            SurfaceView previewSurface,
            CursorOverlayController cursorOverlayController,
            SurfaceChangedHandler onSurfaceCreated,
            SurfaceChangedHandler onSurfaceChanged,
            SurfaceDestroyedHandler onSurfaceDestroyed,
            CursorMotionHandler onCursorOverlayMotion
    ) {
        MainActivitySurfaceSetup.Input input = new MainActivitySurfaceSetup.Input();
        input.preview = previewSurface;
        input.onSurfaceCreated = onSurfaceCreated::onChanged;
        input.onSurfaceChanged = onSurfaceChanged::onChanged;
        input.onSurfaceDestroyed = onSurfaceDestroyed::onDestroyed;
        input.isCursorOverlayEnabled =
                () -> cursorOverlayController != null && cursorOverlayController.isOverlayEnabled();
        input.onCursorOverlayMotion = onCursorOverlayMotion::onMotion;
        MainActivitySurfaceSetup.setup(input);
    }

    @SuppressWarnings("java:S107")
    public static void setupButtons(
            Button settingsCloseButton,
            View simpleMenuPanel,
            View debugInfoPanel,
            Handler uiHandler,
            Runnable debugInfoFadeTask,
            float debugInfoAlphaTouch,
            long debugInfoAlphaResetMs,
            Button simpleModeH265Button,
            Button simpleModeRawButton,
            String preferredVideo,
            ModeSelectedHandler onModeSelected,
            Button simpleFps30Button,
            Button simpleFps45Button,
            Button simpleFps60Button,
            Button simpleFps90Button,
            Button simpleFps120Button,
            Button simpleFps144Button,
            FpsSelectedHandler onFpsSelected,
            Button simpleApplyButton,
            UiTask hideSettingsPanelTask,
            UiTask scheduleSimpleMenuAutoHideTask,
            UiTask onApplyAndStartTask
    ) {
        MainActivityButtonsSetup.setup(
                settingsCloseButton,
                simpleMenuPanel,
                debugInfoPanel,
                uiHandler,
                debugInfoFadeTask,
                debugInfoAlphaTouch,
                debugInfoAlphaResetMs,
                simpleModeH265Button,
                simpleModeRawButton,
                preferredVideo,
                onModeSelected::onMode,
                simpleFps30Button,
                simpleFps45Button,
                simpleFps60Button,
                simpleFps90Button,
                simpleFps120Button,
                simpleFps144Button,
                onFpsSelected::onFps,
                simpleApplyButton,
                hideSettingsPanelTask::run,
                scheduleSimpleMenuAutoHideTask::run,
                onApplyAndStartTask::run
        );
    }

    @SuppressWarnings("java:S107")
    public static void applyBuildVariantUi(
            boolean buildDebug,
            MainUiState uiState,
            FpsLossGraphView debugFpsGraphView,
            int debugFpsGraphPoints,
            View debugInfoPanel,
            UiTask forceFullscreenTask,
            OverlayVisibilityApplier overlayVisibilityApplier,
            UiTask startDebugGraphSamplingTask,
            UiTask refreshDebugInfoOverlayTask,
            UiTask stopDebugGraphSamplingTask
    ) {
        BuildVariantUiCoordinator.apply(
                buildDebug,
                uiState.debugOverlayVisible,
                debugFpsGraphView,
                debugFpsGraphPoints,
                debugInfoPanel,
                forceFullscreenTask::run,
                overlayVisibilityApplier::apply,
                startDebugGraphSamplingTask::run,
                refreshDebugInfoOverlayTask::run,
                stopDebugGraphSamplingTask::run
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
