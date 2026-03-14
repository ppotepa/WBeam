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
    @SuppressWarnings("java:S1104")
    public static final class Decision {
        public boolean updatedSessionActive;
        public long updatedLastPayloadAtMs;
        public boolean enableDebugOverlay;
        public boolean handled;
        public Action action = Action.NONE;
        public String logMessage;
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
        Decision decision = new Decision();
        boolean isTrainingConnection = "training".equals(connectionMode);

        updateTimestamps(decision, isTrainingConnection, trainerHudFromJson, trainerHudFromText,
                nowMs, lastTrainerHudPayloadAtMs);
        
        updateSessionState(decision, isTrainingConnection, trainerHudFlag, trainerHudFromJson,
                trainerHudFromText, trainerHudSessionActive, debugBuild, debugOverlayVisible,
                connectionMode);

        // Early return for direct payload rendering
        if (isTrainingConnection && trainerHudFromJson) {
            return createDecision(decision, Action.RENDER_JSON);
        }
        if (isTrainingConnection && trainerHudFromText) {
            return createDecision(decision, Action.RENDER_TEXT);
        }

        boolean trainerHudActive =
                isTrainingConnection && (trainerHudFlag || trainerHudFromJson || trainerHudFromText);

        // Handle active training session
        if (isTrainingConnection && trainerHudActive) {
            return handleActiveTraining(decision, nowMs, payloadGraceMs);
        }

        // Handle recently ended training session (grace period)
        if (isTrainingConnection && decision.updatedSessionActive) {
            return handleEndedTraining(decision, nowMs, payloadGraceMs);
        }

        decision.handled = isTrainingConnection;
        return decision;
    }

    private static void updateTimestamps(
            Decision decision,
            boolean isTrainingConnection,
            boolean trainerHudFromJson,
            boolean trainerHudFromText,
            long nowMs,
            long lastTrainerHudPayloadAtMs
    ) {
        decision.updatedLastPayloadAtMs = lastTrainerHudPayloadAtMs;
        if (isTrainingConnection && (trainerHudFromJson || trainerHudFromText)) {
            decision.updatedLastPayloadAtMs = nowMs;
        }
    }

    private static void updateSessionState(
            Decision decision,
            boolean isTrainingConnection,
            boolean trainerHudFlag,
            boolean trainerHudFromJson,
            boolean trainerHudFromText,
            boolean trainerHudSessionActive,
            boolean debugBuild,
            boolean debugOverlayVisible,
            String connectionMode
    ) {
        decision.updatedSessionActive = trainerHudSessionActive;

        boolean trainerHudActive =
                isTrainingConnection && (trainerHudFlag || trainerHudFromJson || trainerHudFromText);

        if (!isTrainingConnection && (trainerHudFlag || trainerHudFromJson || trainerHudFromText)) {
            decision.logMessage = "trainer_payload_ignored connection_mode=" + connectionMode;
        }

        if (trainerHudActive && !trainerHudSessionActive) {
            decision.updatedSessionActive = true;
            decision.enableDebugOverlay = debugBuild && !debugOverlayVisible;
        } else if (!trainerHudActive && trainerHudSessionActive) {
            decision.updatedSessionActive = false;
        }
    }

    private static Decision createDecision(Decision decision, Action action) {
        decision.handled = true;
        decision.action = action;
        return decision;
    }

    private static Decision handleActiveTraining(Decision decision, long nowMs, long payloadGraceMs) {
        if (isWithinGracePeriod(decision.updatedLastPayloadAtMs, nowMs, payloadGraceMs)) {
            decision.handled = true;
            decision.action = Action.KEEP_LAST;
            decision.logMessage = "trainer_payload_gap grace=1 keep_last=1";
            return decision;
        }
        return createDecision(decision, Action.RENDER_PLACEHOLDER);
    }

    private static Decision handleEndedTraining(Decision decision, long nowMs, long payloadGraceMs) {
        if (isWithinGracePeriod(decision.updatedLastPayloadAtMs, nowMs, payloadGraceMs)) {
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
}
