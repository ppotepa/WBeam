package com.wbeam.hud;

public final class TrainerHudRouting {
    public enum Action {
        NONE,
        RENDER_JSON,
        RENDER_TEXT,
        KEEP_LAST,
        RENDER_PLACEHOLDER,
        SHOW_WAITING
    }

    /**
     * Plain data carrier for routing decision.
     */
    static final class Decision {
        boolean updatedSessionActive;
        long updatedLastPayloadAtMs;
        boolean enableDebugOverlay;
        boolean handled;
        Action action = Action.NONE;
        String logMessage;
    }

    static final class Input {
        String connectionMode;
        boolean trainerHudFlag;
        boolean trainerHudFromJson;
        boolean trainerHudFromText;
        long nowMs;
        long lastTrainerHudPayloadAtMs;
        boolean trainerHudSessionActive;
        long payloadGraceMs;
        boolean debugBuild;
        boolean debugOverlayVisible;
    }

    private TrainerHudRouting() {
    }

    @SuppressWarnings("java:S107")
    public static Decision decide(
            String connectionMode,
            boolean trainerHudFlag,
            boolean trainerHudFromJson,
            boolean trainerHudFromText,
            long nowMs,
            long lastTrainerHudPayloadAtMs,
            boolean trainerHudSessionActive,
            long payloadGraceMs,
            boolean debugBuild,
            boolean debugOverlayVisible
    ) {
        Input input = new Input();
        input.connectionMode = connectionMode;
        input.trainerHudFlag = trainerHudFlag;
        input.trainerHudFromJson = trainerHudFromJson;
        input.trainerHudFromText = trainerHudFromText;
        input.nowMs = nowMs;
        input.lastTrainerHudPayloadAtMs = lastTrainerHudPayloadAtMs;
        input.trainerHudSessionActive = trainerHudSessionActive;
        input.payloadGraceMs = payloadGraceMs;
        input.debugBuild = debugBuild;
        input.debugOverlayVisible = debugOverlayVisible;
        return decide(input);
    }

    public static Decision decide(Input input) {
        Decision decision = new Decision();
        boolean isTrainingConnection = "training".equals(input.connectionMode);

        updateTimestamps(decision, input, isTrainingConnection);
        updateSessionState(decision, input, isTrainingConnection);

        if (isTrainingConnection && input.trainerHudFromJson) {
            return createDecision(decision, Action.RENDER_JSON);
        }
        if (isTrainingConnection && input.trainerHudFromText) {
            return createDecision(decision, Action.RENDER_TEXT);
        }

        boolean trainerHudActive = isTrainerHudActive(input, isTrainingConnection);

        if (isTrainingConnection && trainerHudActive) {
            return handleActiveTraining(decision, input);
        }

        if (isTrainingConnection && decision.updatedSessionActive) {
            return handleEndedTraining(decision, input);
        }

        decision.handled = isTrainingConnection;
        return decision;
    }

    private static void updateTimestamps(Decision decision, Input input, boolean isTrainingConnection) {
        decision.updatedLastPayloadAtMs = input.lastTrainerHudPayloadAtMs;
        if (isTrainingConnection && (input.trainerHudFromJson || input.trainerHudFromText)) {
            decision.updatedLastPayloadAtMs = input.nowMs;
        }
    }

    private static void updateSessionState(Decision decision, Input input, boolean isTrainingConnection) {
        decision.updatedSessionActive = input.trainerHudSessionActive;
        boolean trainerHudActive = isTrainerHudActive(input, isTrainingConnection);

        if (!isTrainingConnection && hasTrainerPayload(input)) {
            decision.logMessage = "trainer_payload_ignored connection_mode=" + input.connectionMode;
        }

        if (trainerHudActive && !input.trainerHudSessionActive) {
            decision.updatedSessionActive = true;
            decision.enableDebugOverlay = input.debugBuild && !input.debugOverlayVisible;
        } else if (!trainerHudActive && input.trainerHudSessionActive) {
            decision.updatedSessionActive = false;
        }
    }

    private static Decision createDecision(Decision decision, Action action) {
        decision.handled = true;
        decision.action = action;
        return decision;
    }

    private static Decision handleActiveTraining(Decision decision, Input input) {
        if (isWithinGracePeriod(decision.updatedLastPayloadAtMs, input.nowMs, input.payloadGraceMs)) {
            decision.handled = true;
            decision.action = Action.KEEP_LAST;
            decision.logMessage = "trainer_payload_gap grace=1 keep_last=1";
            return decision;
        }
        return createDecision(decision, Action.RENDER_PLACEHOLDER);
    }

    private static Decision handleEndedTraining(Decision decision, Input input) {
        if (isWithinGracePeriod(decision.updatedLastPayloadAtMs, input.nowMs, input.payloadGraceMs)) {
            decision.handled = true;
            decision.action = Action.KEEP_LAST;
            decision.logMessage = "trainer_payload_missing grace=1 keep_last=1";
            return decision;
        }
        return createDecision(decision, Action.SHOW_WAITING);
    }

    private static boolean isWithinGracePeriod(long lastPayloadAtMs, long nowMs, long graceMs) {
        return lastPayloadAtMs > 0L && (nowMs - lastPayloadAtMs) <= graceMs;
    }

    private static boolean isTrainerHudActive(Input input, boolean isTrainingConnection) {
        return isTrainingConnection && hasTrainerPayload(input);
    }

    private static boolean hasTrainerPayload(Input input) {
        return input.trainerHudFlag || input.trainerHudFromJson || input.trainerHudFromText;
    }
}
