package com.wbeam.startup;

/**
 * Computes startup overlay visibility transitions based on preflight completion state.
 */
public final class PreflightStateMachine {
    private PreflightStateMachine() {}

    public static final class Transition {
        public final boolean startupDismissed;
        public final boolean preflightComplete;
        public final boolean showOverlayNow;
        public final boolean scheduleHide;
        public final long hideDelayMs;

        private Transition(
                boolean startupDismissed,
                boolean preflightComplete,
                boolean showOverlayNow,
                boolean scheduleHide,
                long hideDelayMs
        ) {
            this.startupDismissed = startupDismissed;
            this.preflightComplete = preflightComplete;
            this.showOverlayNow = showOverlayNow;
            this.scheduleHide = scheduleHide;
            this.hideDelayMs = hideDelayMs;
        }
    }

    public static Transition next(boolean allOk, boolean startupDismissed, long hideDelayMs) {
        if (!allOk) {
            return new Transition(false, false, true, false, 0L);
        }
        if (!startupDismissed) {
            return new Transition(true, true, false, true, Math.max(0L, hideDelayMs));
        }
        return new Transition(true, true, false, false, 0L);
    }
}
