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

    @SuppressWarnings("java:S107")
    public static void setup(
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
            ModeSelected modeSelected,
            Button simpleFps30Button,
            Button simpleFps45Button,
            Button simpleFps60Button,
            Button simpleFps90Button,
            Button simpleFps120Button,
            Button simpleFps144Button,
            FpsSelected fpsSelected,
            Button simpleApplyButton,
            Action onSettingsClose,
            Action onSimpleMenuTouchRefresh,
            Action onSimpleApply
    ) {
        MainActivityUiBinder.bindSettingsCloseButton(settingsCloseButton, onSettingsClose::run);
        MainActivityUiBinder.bindSimpleMenuTouchRefresh(
                simpleMenuPanel,
                onSimpleMenuTouchRefresh::run
        );
        MainActivityUiBinder.bindDebugInfoTouchFade(
                debugInfoPanel,
                uiHandler,
                debugInfoFadeTask,
                debugInfoAlphaTouch,
                debugInfoAlphaResetMs
        );
        MainActivityUiBinder.bindSimpleModeButtons(
                simpleModeH265Button,
                simpleModeRawButton,
                preferredVideo,
                modeSelected::onSelected
        );
        MainActivityUiBinder.bindSimpleFpsButtons(
                simpleFps30Button,
                simpleFps45Button,
                simpleFps60Button,
                simpleFps90Button,
                simpleFps120Button,
                simpleFps144Button,
                fpsSelected::onSelected
        );
        MainActivityUiBinder.bindSimpleApplyButton(simpleApplyButton, onSimpleApply::run);
    }
}
