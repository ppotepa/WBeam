package com.wbeam.ui;

import android.view.KeyEvent;

public final class MainActivityInteractionPolicy {
    /**
     * Plain data carrier for toggle state.
     */
    public static final class ToggleState {
        private boolean volumeUpHeld;
        private boolean volumeDownHeld;
        private boolean debugOverlayToggleArmed;

        public boolean isVolumeUpHeld() { return volumeUpHeld; }
        public boolean isVolumeDownHeld() { return volumeDownHeld; }
        public boolean isDebugOverlayToggleArmed() { return debugOverlayToggleArmed; }
        public void setDebugOverlayToggleArmed(boolean debugOverlayToggleArmed) {
            this.debugOverlayToggleArmed = debugOverlayToggleArmed;
        }
    }

    /**
     * Plain data carrier for toggle action result.
     */
    public static final class ToggleAction {
        private boolean handled;
        private boolean scheduleToggle;
        private boolean cancelScheduledToggle;
        private boolean resetArmed;

        public boolean isHandled() { return handled; }
        public boolean shouldScheduleToggle() { return scheduleToggle; }
        public boolean shouldCancelScheduledToggle() { return cancelScheduledToggle; }
        public boolean shouldResetArmed() { return resetArmed; }
    }

    private MainActivityInteractionPolicy() {
    }

    public static boolean handleBackNavigation(
            boolean simpleMenuVisible,
            boolean settingsVisible,
            Runnable hideSimpleMenu,
            Runnable hideSettingsPanel
    ) {
        if (simpleMenuVisible) {
            hideSimpleMenu.run();
            return true;
        }
        if (settingsVisible) {
            hideSettingsPanel.run();
            return true;
        }
        return false;
    }

    public static ToggleAction handleDebugToggleKeyDown(
            ToggleState state,
            boolean debugBuild,
            int keyCode,
            int repeatCount
    ) {
        ToggleAction action = new ToggleAction();
        if (!debugBuild || !isVolumeKey(keyCode)) {
            return action;
        }
        action.handled = true;
        if (repeatCount == 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                state.volumeUpHeld = true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                state.volumeDownHeld = true;
            }
            if (state.volumeUpHeld && state.volumeDownHeld && !state.debugOverlayToggleArmed) {
                action.cancelScheduledToggle = true;
                action.scheduleToggle = true;
            }
        }
        return action;
    }

    public static ToggleAction handleDebugToggleKeyUp(
            ToggleState state,
            boolean debugBuild,
            int keyCode
    ) {
        ToggleAction action = new ToggleAction();
        if (!debugBuild || !isVolumeKey(keyCode)) {
            return action;
        }
        action.handled = true;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            state.volumeUpHeld = false;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            state.volumeDownHeld = false;
        }
        if (!state.volumeUpHeld || !state.volumeDownHeld) {
            action.cancelScheduledToggle = true;
        }
        if (!state.volumeUpHeld && !state.volumeDownHeld) {
            action.resetArmed = true;
        }
        return action;
    }

    private static boolean isVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }
}
