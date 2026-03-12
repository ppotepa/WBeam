package com.wbeam.startup;

import android.view.View;

import com.wbeam.stream.VideoTestController;

public final class StartupOverlayHooksFactory {
    public interface ProbeHooks {
        boolean requiresTransportProbe();
        void maybeStartTransportProbe();
    }

    public interface InputHooks {
        StartupOverlayModelBuilder.Input buildInput(boolean shouldProbe);
    }

    public interface ModelHooks {
        void applyModel(StartupOverlayModelBuilder.Model model);
    }

    public interface VisibilityHooks {
        void setOverlayVisible(boolean visible);
    }

    public interface Scheduler {
        void postDelayed(long delayMs, Runnable action);
    }

    private StartupOverlayHooksFactory() {
    }

    public static StartupOverlayCoordinator.Hooks create(
            final View overlayContainer,
            final StartupOverlayViewRenderer.Views views,
            final VideoTestController videoTestController,
            final int videoTestHintColor,
            final ProbeHooks probeHooks,
            final InputHooks inputHooks,
            final ModelHooks modelHooks,
            final VisibilityHooks visibilityHooks,
            final Scheduler scheduler
    ) {
        return new StartupOverlayCoordinator.Hooks() {
            @Override
            public boolean hasOverlayContainer() {
                return overlayContainer != null;
            }

            @Override
            public boolean isVideoTestOverlayActive() {
                return videoTestController != null && videoTestController.isOverlayActive();
            }

            @Override
            public void applyVideoTestOverlay() {
                StartupOverlayViewRenderer.applyVideoTestOverride(
                        views,
                        videoTestController.getOverlayTitle(),
                        videoTestController.getOverlayBody(),
                        videoTestController.getOverlayHint(),
                        videoTestHintColor
                );
                visibilityHooks.setOverlayVisible(true);
            }

            @Override
            public boolean requiresTransportProbe() {
                return probeHooks.requiresTransportProbe();
            }

            @Override
            public void maybeStartTransportProbe() {
                probeHooks.maybeStartTransportProbe();
            }

            @Override
            public StartupOverlayModelBuilder.Input buildInput(boolean shouldProbe) {
                return inputHooks.buildInput(shouldProbe);
            }

            @Override
            public void applyModel(StartupOverlayModelBuilder.Model model) {
                modelHooks.applyModel(model);
            }

            @Override
            public void setOverlayVisible(boolean visible) {
                visibilityHooks.setOverlayVisible(visible);
            }

            @Override
            public void scheduleHide(long delayMs, Runnable action) {
                scheduler.postDelayed(delayMs, action);
            }
        };
    }
}
