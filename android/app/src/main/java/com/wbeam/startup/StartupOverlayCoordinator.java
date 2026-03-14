package com.wbeam.startup;

public final class StartupOverlayCoordinator {
    public interface Hooks {
        boolean hasOverlayContainer();
        boolean isVideoTestOverlayActive();
        void applyVideoTestOverlay();
        boolean requiresTransportProbe();
        void maybeStartTransportProbe();
        StartupOverlayModelBuilder.Input buildInput(boolean shouldProbe);
        void applyModel(StartupOverlayModelBuilder.Model model);
        void setOverlayVisible(boolean visible);
        void scheduleHide(long delayMs, Runnable action);
    }

    public static final class State {
        long startupBeganAtMs;
        int controlRetryCount;
        boolean startupDismissed;
        boolean preflightComplete;

        long getStartupBeganAtMs() { return startupBeganAtMs; }
        void setStartupBeganAtMs(long startupBeganAtMs) { this.startupBeganAtMs = startupBeganAtMs; }
        int getControlRetryCount() { return controlRetryCount; }
        void setControlRetryCount(int controlRetryCount) { this.controlRetryCount = controlRetryCount; }
        boolean isStartupDismissed() { return startupDismissed; }
        void setStartupDismissed(boolean startupDismissed) { this.startupDismissed = startupDismissed; }
        boolean isPreflightComplete() { return preflightComplete; }
        void setPreflightComplete(boolean preflightComplete) { this.preflightComplete = preflightComplete; }
    }

    private StartupOverlayCoordinator() {
    }

    public static State update(Hooks hooks, State state) {
        if (!hooks.hasOverlayContainer()) {
            return state;
        }
        if (hooks.isVideoTestOverlayActive()) {
            hooks.applyVideoTestOverlay();
            return state;
        }

        boolean shouldProbe = hooks.requiresTransportProbe();
        if (shouldProbe) {
            hooks.maybeStartTransportProbe();
        }

        StartupOverlayModelBuilder.Input input = hooks.buildInput(shouldProbe);
        StartupOverlayModelBuilder.Model model = StartupOverlayModelBuilder.build(input);
        hooks.applyModel(model);

        State next = new State();
        next.setStartupBeganAtMs(model.getUpdatedStartupBeganAtMs());
        next.setControlRetryCount(model.getUpdatedControlRetryCount());
        next.setStartupDismissed(state.isStartupDismissed());
        next.setPreflightComplete(state.isPreflightComplete());

        PreflightStateMachine.Transition transition =
                PreflightStateMachine.next(model.isAllOk(), state.isStartupDismissed(), 800L);
        next.setStartupDismissed(transition.startupDismissed);
        next.setPreflightComplete(transition.preflightComplete);
        if (transition.showOverlayNow) {
            hooks.setOverlayVisible(true);
        }
        if (transition.scheduleHide) {
            hooks.scheduleHide(transition.hideDelayMs, () -> {
                if (next.isStartupDismissed()) {
                    hooks.setOverlayVisible(false);
                }
            });
        }
        return next;
    }
}
