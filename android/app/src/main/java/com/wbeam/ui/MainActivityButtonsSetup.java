package com.wbeam.ui;

import android.os.Handler;
import android.view.View;
import android.widget.Button;

public final class MainActivityButtonsSetup {
    @FunctionalInterface
    public interface Action {
        void run();
    }

    @FunctionalInterface
    public interface ModeSelected {
        void onSelected(String mode);
    }

    @FunctionalInterface
    public interface FpsSelected {
        void onSelected(int fps);
    }

    private MainActivityButtonsSetup() {
    }

    public static final class SetupInput {
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
        private ModeSelected modeSelected;
        private Button simpleFps30Button;
        private Button simpleFps45Button;
        private Button simpleFps60Button;
        private Button simpleFps90Button;
        private Button simpleFps120Button;
        private Button simpleFps144Button;
        private FpsSelected fpsSelected;
        private Button simpleApplyButton;
        private Action onSettingsClose;
        private Action onSimpleMenuTouchRefresh;
        private Action onSimpleApply;

        public SetupInput setSettingsCloseButton(Button value) { settingsCloseButton = value; return this; }
        public SetupInput setSimpleMenuPanel(View value) { simpleMenuPanel = value; return this; }
        public SetupInput setDebugInfoPanel(View value) { debugInfoPanel = value; return this; }
        public SetupInput setUiHandler(Handler value) { uiHandler = value; return this; }
        public SetupInput setDebugInfoFadeTask(Runnable value) { debugInfoFadeTask = value; return this; }
        public SetupInput setDebugInfoAlphaTouch(float value) { debugInfoAlphaTouch = value; return this; }
        public SetupInput setDebugInfoAlphaResetMs(long value) { debugInfoAlphaResetMs = value; return this; }
        public SetupInput setSimpleModeH265Button(Button value) { simpleModeH265Button = value; return this; }
        public SetupInput setSimpleModeRawButton(Button value) { simpleModeRawButton = value; return this; }
        public SetupInput setPreferredVideo(String value) { preferredVideo = value; return this; }
        public SetupInput setModeSelected(ModeSelected value) { modeSelected = value; return this; }
        public SetupInput setSimpleFps30Button(Button value) { simpleFps30Button = value; return this; }
        public SetupInput setSimpleFps45Button(Button value) { simpleFps45Button = value; return this; }
        public SetupInput setSimpleFps60Button(Button value) { simpleFps60Button = value; return this; }
        public SetupInput setSimpleFps90Button(Button value) { simpleFps90Button = value; return this; }
        public SetupInput setSimpleFps120Button(Button value) { simpleFps120Button = value; return this; }
        public SetupInput setSimpleFps144Button(Button value) { simpleFps144Button = value; return this; }
        public SetupInput setFpsSelected(FpsSelected value) { fpsSelected = value; return this; }
        public SetupInput setSimpleApplyButton(Button value) { simpleApplyButton = value; return this; }
        public SetupInput setOnSettingsClose(Action value) { onSettingsClose = value; return this; }
        public SetupInput setOnSimpleMenuTouchRefresh(Action value) { onSimpleMenuTouchRefresh = value; return this; }
        public SetupInput setOnSimpleApply(Action value) { onSimpleApply = value; return this; }
    }

    public static void setup(SetupInput input) {
        MainActivityUiBinder.bindSettingsCloseButton(input.settingsCloseButton, input.onSettingsClose::run);
        MainActivityUiBinder.bindSimpleMenuTouchRefresh(
                input.simpleMenuPanel,
                input.onSimpleMenuTouchRefresh::run
        );
        MainActivityUiBinder.bindDebugInfoTouchFade(
                input.debugInfoPanel,
                input.uiHandler,
                input.debugInfoFadeTask,
                input.debugInfoAlphaTouch,
                input.debugInfoAlphaResetMs
        );
        MainActivityUiBinder.bindSimpleModeButtons(
                input.simpleModeH265Button,
                input.simpleModeRawButton,
                input.preferredVideo,
                input.modeSelected::onSelected
        );
        MainActivityUiBinder.bindSimpleFpsButtons(
                input.simpleFps30Button,
                input.simpleFps45Button,
                input.simpleFps60Button,
                input.simpleFps90Button,
                input.simpleFps120Button,
                input.simpleFps144Button,
                input.fpsSelected::onSelected
        );
        MainActivityUiBinder.bindSimpleApplyButton(input.simpleApplyButton, input.onSimpleApply::run);
    }
}
