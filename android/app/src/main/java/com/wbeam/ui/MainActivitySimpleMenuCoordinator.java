package com.wbeam.ui;

import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;

@SuppressWarnings("java:S107")
public final class MainActivitySimpleMenuCoordinator {
    public static final class State {
        private boolean visible;
        private String mode;
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
            this.mode = mode;
        }

        public int getFps() {
            return fps;
        }

        public void setFps(int fps) {
            this.fps = fps;
        }
    }

    public static final class ToggleInput {
        private View simpleMenuPanel;
        private Handler uiHandler;
        private Runnable autoHideTask;
        private State state;
        private String selectedEncoder;
        private String preferredVideo;
        private int selectedFps;
        private Runnable refreshButtons;
        private Runnable scheduleAutoHide;

        public ToggleInput setSimpleMenuPanel(View value) { simpleMenuPanel = value; return this; }
        public ToggleInput setUiHandler(Handler value) { uiHandler = value; return this; }
        public ToggleInput setAutoHideTask(Runnable value) { autoHideTask = value; return this; }
        public ToggleInput setState(State value) { state = value; return this; }
        public ToggleInput setSelectedEncoder(String value) { selectedEncoder = value; return this; }
        public ToggleInput setPreferredVideo(String value) { preferredVideo = value; return this; }
        public ToggleInput setSelectedFps(int value) { selectedFps = value; return this; }
        public ToggleInput setRefreshButtons(Runnable value) { refreshButtons = value; return this; }
        public ToggleInput setScheduleAutoHide(Runnable value) { scheduleAutoHide = value; return this; }
    }

    public static final class RefreshButtonsInput {
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

        public RefreshButtonsInput setSimpleMenuPanel(View value) { simpleMenuPanel = value; return this; }
        public RefreshButtonsInput setSimpleModeH265Button(Button value) { simpleModeH265Button = value; return this; }
        public RefreshButtonsInput setSimpleModeRawButton(Button value) { simpleModeRawButton = value; return this; }
        public RefreshButtonsInput setPreferredVideo(String value) { preferredVideo = value; return this; }
        public RefreshButtonsInput setMode(String value) { mode = value; return this; }
        public RefreshButtonsInput setSimpleFps30Button(Button value) { simpleFps30Button = value; return this; }
        public RefreshButtonsInput setSimpleFps45Button(Button value) { simpleFps45Button = value; return this; }
        public RefreshButtonsInput setSimpleFps60Button(Button value) { simpleFps60Button = value; return this; }
        public RefreshButtonsInput setSimpleFps90Button(Button value) { simpleFps90Button = value; return this; }
        public RefreshButtonsInput setSimpleFps120Button(Button value) { simpleFps120Button = value; return this; }
        public RefreshButtonsInput setSimpleFps144Button(Button value) { simpleFps144Button = value; return this; }
        public RefreshButtonsInput setFps(int value) { fps = value; return this; }
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

    public static void toggle(ToggleInput input) {
        if (input.state.isVisible()) {
            hide(input.simpleMenuPanel, input.uiHandler, input.autoHideTask, input.state);
            return;
        }
        show(
                input.simpleMenuPanel,
                input.selectedEncoder,
                input.preferredVideo,
                input.selectedFps,
                input.state,
                input.refreshButtons,
                input.scheduleAutoHide
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

    public static void refreshButtons(RefreshButtonsInput input) {
        if (input.simpleMenuPanel == null) {
            return;
        }
        MainActivitySettingsPresenter.applySimpleMenuButtons(
                input.simpleModeH265Button,
                input.simpleModeRawButton,
                input.preferredVideo,
                input.mode,
                input.simpleFps30Button,
                input.simpleFps45Button,
                input.simpleFps60Button,
                input.simpleFps90Button,
                input.simpleFps120Button,
                input.simpleFps144Button,
                input.fps
        );
    }
}
