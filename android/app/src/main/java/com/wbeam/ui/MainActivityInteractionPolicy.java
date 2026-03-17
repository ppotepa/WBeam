package com.wbeam.ui;

import android.view.KeyEvent;

public final class MainActivityInteractionPolicy {
    @SuppressWarnings("java:S1104")
    public static final class ToggleState {
        public boolean volumeUpHeld;
        public boolean volumeDownHeld;
        public boolean debugOverlayToggleArmed;
    }

    @SuppressWarnings("java:S1104")
    public static final class ToggleAction {
        public boolean handled;
        public boolean scheduleToggle;
        public boolean cancelScheduledToggle;
        public boolean resetArmed;
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
