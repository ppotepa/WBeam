package com.wbeam.hud;

public final class RuntimeHudAvailabilityHooksFactory {
    @FunctionalInterface
    public interface RuntimeValuesUpdater {
        void update(double targetFps, double presentFps, long uptimeSec, long frameOutHost);
    }

    @FunctionalInterface
    public interface LineUpdater {
        void update(String line);
    }

    @FunctionalInterface
    public interface HudTextRenderer {
        void render(String modeTag, String text, int color);
    }

    @FunctionalInterface
    public interface AlphaSetter {
        void set(float alpha);
    }

    @FunctionalInterface
    public interface Action {
        void run();
    }

    @FunctionalInterface
    public interface MessageEmitter {
        void emit(String message);
    }

    private RuntimeHudAvailabilityHooksFactory() {
    }

    public static RuntimeHudAvailabilityCoordinator.Hooks create(
            RuntimeValuesUpdater runtimeValuesUpdater,
            LineUpdater lineUpdater,
            HudTextRenderer hudTextRenderer,
            AlphaSetter alphaSetter,
            Action debugRefreshAction,
            MessageEmitter messageEmitter
    ) {
        return new RuntimeHudAvailabilityCoordinator.Hooks() {
            @Override
            public void updateRuntimeValues(
                    double targetFps,
                    double presentFps,
                    long uptimeSec,
                    long frameOutHost
            ) {
                runtimeValuesUpdater.update(targetFps, presentFps, uptimeSec, frameOutHost);
            }

            @Override
            public void updateCompactLine(String line) {
                lineUpdater.update(line);
            }

            @Override
            public void showTextOnly(String modeTag, String text, int color) {
                hudTextRenderer.render(modeTag, text, color);
            }

            @Override
            public void setPerfHudPanelAlpha(float alpha) {
                alphaSetter.set(alpha);
            }

            @Override
            public void refreshDebugOverlay() {
                debugRefreshAction.run();
            }

            @Override
            public void emitDebug(String message) {
                messageEmitter.emit(message);
            }
        };
    }
}
