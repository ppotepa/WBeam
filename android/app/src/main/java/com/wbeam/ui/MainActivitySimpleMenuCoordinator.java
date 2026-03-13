package com.wbeam.ui;

import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;

public final class MainActivitySimpleMenuCoordinator {
    @SuppressWarnings("java:S1104")
    public static final class State {
        public boolean visible;
        public String mode;
        public int fps;
    }

    private MainActivitySimpleMenuCoordinator() {
    }

    public static void selectFps(
            int selectedFps,
            State state,
            Runnable refreshButtons,
            Runnable scheduleAutoHide
    ) {
        state.fps = MainActivitySettingsPresenter.simpleMenuFpsFromSelection(selectedFps);
        refreshButtons.run();
        scheduleAutoHide.run();
    }

    public static void show(
            View simpleMenuPanel,
            String selectedEncoder,
            String preferredVideo,
            int selectedFps,
            State state,
            Runnable refreshButtons,
            Runnable scheduleAutoHide
    ) {
        if (simpleMenuPanel == null) {
            return;
        }
        state.mode = MainActivitySettingsPresenter.simpleMenuModeFromSelection(
                selectedEncoder,
                preferredVideo
        );
        state.fps = MainActivitySettingsPresenter.simpleMenuFpsFromSelection(selectedFps);
        state.visible = true;
        refreshButtons.run();
        simpleMenuPanel.setVisibility(View.VISIBLE);
        scheduleAutoHide.run();
    }

    public static void hide(
            View simpleMenuPanel,
            Handler uiHandler,
            Runnable autoHideTask,
            State state
    ) {
        if (simpleMenuPanel == null) {
            return;
        }
        state.visible = false;
        uiHandler.removeCallbacks(autoHideTask);
        simpleMenuPanel.setVisibility(View.GONE);
    }

    @SuppressWarnings("java:S107")
    public static void toggle(
            View simpleMenuPanel,
            Handler uiHandler,
            Runnable autoHideTask,
            State state,
            String selectedEncoder,
            String preferredVideo,
            int selectedFps,
            Runnable refreshButtons,
            Runnable scheduleAutoHide
    ) {
        if (state.visible) {
            hide(simpleMenuPanel, uiHandler, autoHideTask, state);
            return;
        }
        show(
                simpleMenuPanel,
                selectedEncoder,
                preferredVideo,
                selectedFps,
                state,
                refreshButtons,
                scheduleAutoHide
        );
    }

    public static void scheduleAutoHide(
            View simpleMenuPanel,
            Handler uiHandler,
            Runnable autoHideTask,
            boolean simpleMenuVisible,
            long autoHideMs
    ) {
        if (!simpleMenuVisible || simpleMenuPanel == null) {
            return;
        }
        uiHandler.removeCallbacks(autoHideTask);
        uiHandler.postDelayed(autoHideTask, autoHideMs);
    }

    public static void applyToSettings(
            Spinner encoderSpinner,
            SeekBar fpsSeek,
            String[] encoderOptions,
            String preferredVideo,
            String mode,
            int fps
    ) {
        MainActivitySettingsPresenter.applySimpleMenuSettings(
                encoderSpinner,
                fpsSeek,
                encoderOptions,
                preferredVideo,
                mode,
                fps
        );
    }

    @SuppressWarnings("java:S107")
    public static void refreshButtons(
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
        if (simpleMenuPanel == null) {
            return;
        }
        MainActivitySettingsPresenter.applySimpleMenuButtons(
                simpleModeH265Button,
                simpleModeRawButton,
                preferredVideo,
                mode,
                simpleFps30Button,
                simpleFps45Button,
                simpleFps60Button,
                simpleFps90Button,
                simpleFps120Button,
                simpleFps144Button,
                fps
        );
    }
}
