package com.wbeam.hud;

import org.json.JSONObject;

public final class TrainerHudModeHooksFactory {
    @FunctionalInterface
    public interface DebugOverlayEnabler {
        void enable();
    }

    @FunctionalInterface
    public interface DebugMessageEmitter {
        void emit(String message);
    }

    @FunctionalInterface
    public interface HudJsonRenderer {
        void render(JSONObject trainerHudJson);
    }

    @FunctionalInterface
    public interface HudTextRenderer {
        void render(String trainerHudText);
    }

    @FunctionalInterface
    public interface Action {
        void run();
    }

    private TrainerHudModeHooksFactory() {
    }

    public static TrainerHudModeCoordinator.Hooks create(
            DebugOverlayEnabler debugOverlayEnabler,
            DebugMessageEmitter debugMessageEmitter,
            HudJsonRenderer hudJsonRenderer,
            HudTextRenderer hudTextRenderer,
            Action keepLastAction,
            Action placeholderAction,
            Action waitingAction
    ) {
        return new TrainerHudModeCoordinator.Hooks() {
            @Override
            public void enableDebugOverlay() {
                debugOverlayEnabler.enable();
            }

            @Override
            public void emitDebug(String message) {
                debugMessageEmitter.emit(message);
            }

            @Override
            public void renderJson(JSONObject trainerHudJson) {
                hudJsonRenderer.render(trainerHudJson);
            }

            @Override
            public void renderText(String trainerHudText) {
                hudTextRenderer.render(trainerHudText);
            }

            @Override
            public void keepLast() {
                keepLastAction.run();
            }

            @Override
            public void renderPlaceholder() {
                placeholderAction.run();
            }

            @Override
            public void showWaiting() {
                waitingAction.run();
            }
        };
    }
}
