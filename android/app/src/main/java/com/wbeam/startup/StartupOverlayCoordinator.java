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
        public long startupBeganAtMs;
        public int controlRetryCount;
        public boolean startupDismissed;
        public boolean preflightComplete;
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
        next.startupBeganAtMs = model.getUpdatedStartupBeganAtMs();
        next.controlRetryCount = model.getUpdatedControlRetryCount();
        next.startupDismissed = state.startupDismissed;
        next.preflightComplete = state.preflightComplete;

        PreflightStateMachine.Transition transition =
                PreflightStateMachine.next(model.isAllOk(), state.startupDismissed, 800L);
        next.startupDismissed = transition.startupDismissed;
        next.preflightComplete = transition.preflightComplete;
        if (transition.showOverlayNow) {
            hooks.setOverlayVisible(true);
        }
        if (transition.scheduleHide) {
            hooks.scheduleHide(transition.hideDelayMs, () -> {
                if (next.startupDismissed) {
                    hooks.setOverlayVisible(false);
                }
            });
        }
        return next;
    }
}
