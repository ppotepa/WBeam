package com.wbeam.startup;

public final class StartupOverlayStateSync {
    public static final class StateValues {
        private long startupBeganAtMs;
        private int controlRetryCount;
        private boolean startupDismissed;
        private boolean preflightComplete;

        public long getStartupBeganAtMs() {
            return startupBeganAtMs;
        }

        public void setStartupBeganAtMs(long startupBeganAtMs) {
            this.startupBeganAtMs = startupBeganAtMs;
        }

        public int getControlRetryCount() {
            return controlRetryCount;
        }

        public void setControlRetryCount(int controlRetryCount) {
            this.controlRetryCount = controlRetryCount;
        }

        public boolean isStartupDismissed() {
            return startupDismissed;
        }

        public void setStartupDismissed(boolean startupDismissed) {
            this.startupDismissed = startupDismissed;
        }

        public boolean isPreflightComplete() {
            return preflightComplete;
        }

        public void setPreflightComplete(boolean preflightComplete) {
            this.preflightComplete = preflightComplete;
        }
    }

    private StartupOverlayStateSync() {
    }

    public static StartupOverlayCoordinator.State snapshot(StateValues values) {
        StartupOverlayCoordinator.State state = new StartupOverlayCoordinator.State();
        state.setStartupBeganAtMs(values.getStartupBeganAtMs());
        state.setControlRetryCount(values.getControlRetryCount());
        state.setStartupDismissed(values.isStartupDismissed());
        state.setPreflightComplete(values.isPreflightComplete());
        return state;
    }

    public static StateValues fromState(StartupOverlayCoordinator.State state) {
        StateValues values = new StateValues();
        values.setStartupBeganAtMs(state.getStartupBeganAtMs());
        values.setControlRetryCount(state.getControlRetryCount());
        values.setStartupDismissed(state.isStartupDismissed());
        values.setPreflightComplete(state.isPreflightComplete());
        return values;
    }
}
