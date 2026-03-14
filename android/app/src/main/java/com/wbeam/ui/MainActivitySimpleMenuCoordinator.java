package com.wbeam.ui;

import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;

public final class MainActivitySimpleMenuCoordinator {
    public static final class State {
        private boolean visible;
        private String mode = "";
        private int fps;

        public boolean isVisible() {
            return visible;
        }

        public void setVisible(boolean visible) {
            this.visible = visible;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode == null ? "" : mode;
        }

        public int getFps() {
            return fps;
        }

        public void setFps(int fps) {
            this.fps = fps;
        }
    }

    private MainActivitySimpleMenuCoordinator() {
    }

    public static void selectFps(
            int selectedFps,
            State state,
            Runnable refreshButtons,
            Runnable scheduleAutoHide
    ) {
        state.setFps(MainActivitySettingsPresenter.simpleMenuFpsFromSelection(selectedFps));
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
        state.setMode(MainActivitySettingsPresenter.simpleMenuModeFromSelection(
                selectedEncoder,
                preferredVideo
        ));
        state.setFps(MainActivitySettingsPresenter.simpleMenuFpsFromSelection(selectedFps));
        state.setVisible(true);
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
        state.setVisible(false);
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
        if (state.isVisible()) {
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
                new MainActivitySettingsPresenter.SimpleMenuButtonsInput()
                        .setSimpleModePreferredButton(simpleModeH265Button)
                        .setSimpleModeRawButton(simpleModeRawButton)
                        .setPreferredVideo(preferredVideo)
                        .setSimpleMode(mode)
                        .setSimpleFps30Button(simpleFps30Button)
                        .setSimpleFps45Button(simpleFps45Button)
                        .setSimpleFps60Button(simpleFps60Button)
                        .setSimpleFps90Button(simpleFps90Button)
                        .setSimpleFps120Button(simpleFps120Button)
                        .setSimpleFps144Button(simpleFps144Button)
                        .setSimpleFps(fps)
        );
    }
}
