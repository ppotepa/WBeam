package com.wbeam.hud;

import org.json.JSONObject;

import java.util.Locale;

public final class TrainerHudModeCoordinator {
    static final class State {
        long lastPayloadAtMs;
        boolean sessionActive;
    }

    public interface Hooks {
        void enableDebugOverlay();
        void emitDebug(String message);
        void renderJson(JSONObject trainerHudJson);
        void renderText(String trainerHudText);
        void keepLast();
        void renderPlaceholder();
        void showWaiting();
    }

    private TrainerHudModeCoordinator() {
    }

    public static boolean handle(
            JSONObject metrics,
            long nowMs,
            State state,
            long payloadGraceMs,
            boolean debugBuild,
            boolean debugOverlayVisible,
            Hooks hooks
    ) {
        String connectionMode = metrics.optString("connection_mode", "live")
                .trim()
                .toLowerCase(Locale.US);
        JSONObject trainerHudJson = metrics.optJSONObject("trainer_hud_json");
        boolean trainerHudFromJson = trainerHudJson != null && trainerHudJson.length() > 0;
        String trainerHudText = metrics.optString("trainer_hud_text", "");
        boolean trainerHudFromText = trainerHudText != null && !trainerHudText.trim().isEmpty();
        boolean trainerHudFlag = metrics.optBoolean("trainer_hud_active", false);

        TrainerHudRouting.Decision decision = TrainerHudRouting.decide(
                connectionMode,
                trainerHudFlag,
                trainerHudFromJson,
                trainerHudFromText,
                nowMs,
                state.lastPayloadAtMs,
                state.sessionActive,
                payloadGraceMs,
                debugBuild,
                debugOverlayVisible
        );
        state.lastPayloadAtMs = decision.updatedLastPayloadAtMs;
        state.sessionActive = decision.updatedSessionActive;
        if (decision.enableDebugOverlay) {
            hooks.enableDebugOverlay();
        }
        if (decision.logMessage != null && !decision.logMessage.isEmpty()) {
            hooks.emitDebug(decision.logMessage);
        }

        switch (decision.action) {
            case RENDER_JSON:
                hooks.renderJson(trainerHudJson);
                return true;
            case RENDER_TEXT:
                hooks.renderText(trainerHudText);
                return true;
            case KEEP_LAST:
                hooks.keepLast();
                return true;
            case RENDER_PLACEHOLDER:
                hooks.renderPlaceholder();
                return true;
            case SHOW_WAITING:
                hooks.showWaiting();
                return true;
            case NONE:
            default:
                return decision.handled;
        }
    }
}
