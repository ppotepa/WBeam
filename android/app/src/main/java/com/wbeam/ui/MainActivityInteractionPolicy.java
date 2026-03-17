package com.wbeam.ui;

import android.view.KeyEvent;

@SuppressWarnings("java:S1104")
public final class MainActivityInteractionPolicy {
    @SuppressWarnings("java:S1104")
    public static final class ToggleState {
        private boolean volumeUpHeld;
        private boolean volumeDownHeld;
        private boolean debugOverlayToggleArmed;

        public boolean isVolumeUpHeld() {
            return volumeUpHeld;
        }

        public void setVolumeUpHeld(boolean volumeUpHeld) {
            this.volumeUpHeld = volumeUpHeld;
        }

        public boolean isVolumeDownHeld() {
            return volumeDownHeld;
        }

        public void setVolumeDownHeld(boolean volumeDownHeld) {
            this.volumeDownHeld = volumeDownHeld;
        }

        public boolean isDebugOverlayToggleArmed() {
            return debugOverlayToggleArmed;
        }

        public void setDebugOverlayToggleArmed(boolean debugOverlayToggleArmed) {
            this.debugOverlayToggleArmed = debugOverlayToggleArmed;
        }
    }

    @SuppressWarnings("java:S1104")
    public static final class ToggleAction {
        private boolean handled;
        private boolean scheduleToggle;
        private boolean cancelScheduledToggle;
        private boolean resetArmed;

        public boolean isHandled() {
            return handled;
        }

        public void setHandled(boolean handled) {
            this.handled = handled;
        }

        public boolean isScheduleToggle() {
            return scheduleToggle;
        }

        public void setScheduleToggle(boolean scheduleToggle) {
            this.scheduleToggle = scheduleToggle;
        }

        public boolean isCancelScheduledToggle() {
            return cancelScheduledToggle;
        }

        public void setCancelScheduledToggle(boolean cancelScheduledToggle) {
            this.cancelScheduledToggle = cancelScheduledToggle;
        }

        public boolean isResetArmed() {
            return resetArmed;
        }

        public void setResetArmed(boolean resetArmed) {
            this.resetArmed = resetArmed;
        }
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
        action.setHandled(true);
        if (repeatCount == 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                state.setVolumeUpHeld(true);
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                state.setVolumeDownHeld(true);
            }
            if (state.isVolumeUpHeld() && state.isVolumeDownHeld() && !state.isDebugOverlayToggleArmed()) {
                action.setCancelScheduledToggle(true);
                action.setScheduleToggle(true);
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
        action.setHandled(true);
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            state.setVolumeUpHeld(false);
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            state.setVolumeDownHeld(false);
        }
        if (!state.isVolumeUpHeld() || !state.isVolumeDownHeld()) {
            action.setCancelScheduledToggle(true);
        }
        if (!state.isVolumeUpHeld() && !state.isVolumeDownHeld()) {
            action.setResetArmed(true);
        }
        return action;
    }

    private static boolean isVolumeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }
}
