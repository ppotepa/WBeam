package com.wbeam.startup;

@SuppressWarnings("java:S1104")
public final class StartupOverlayStateSync {
    public static final class StateValues {
        public long startupBeganAtMs;
        public int controlRetryCount;
        public boolean startupDismissed;
        public boolean preflightComplete;
    }

    private StartupOverlayStateSync() {
    }

    public static StartupOverlayCoordinator.State snapshot(StateValues values) {
        StartupOverlayCoordinator.State state = new StartupOverlayCoordinator.State();
        state.startupBeganAtMs = values.startupBeganAtMs;
        state.controlRetryCount = values.controlRetryCount;
        state.startupDismissed = values.startupDismissed;
        state.preflightComplete = values.preflightComplete;
        return state;
    }

    public static StateValues fromState(StartupOverlayCoordinator.State state) {
        StateValues values = new StateValues();
        values.startupBeganAtMs = state.startupBeganAtMs;
        values.controlRetryCount = state.controlRetryCount;
        values.startupDismissed = state.startupDismissed;
        values.preflightComplete = state.preflightComplete;
        return values;
    }
}
