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

    @SuppressWarnings({"java:S107", "java:S3776"})
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

        decision.updatedLastPayloadAtMs = lastTrainerHudPayloadAtMs;
        decision.updatedSessionActive = trainerHudSessionActive;
        if (isTrainingConnection && (trainerHudFromJson || trainerHudFromText)) {
            decision.updatedLastPayloadAtMs = nowMs;
        }

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

        if (isTrainingConnection && trainerHudFromJson) {
            decision.handled = true;
            decision.action = Action.RENDER_JSON;
            return decision;
        }
        if (isTrainingConnection && trainerHudFromText) {
            decision.handled = true;
            decision.action = Action.RENDER_TEXT;
            return decision;
        }
        if (isTrainingConnection && trainerHudActive) {
            if (decision.updatedLastPayloadAtMs > 0L
                    && (nowMs - decision.updatedLastPayloadAtMs) <= payloadGraceMs) {
                decision.handled = true;
                decision.action = Action.KEEP_LAST;
                decision.logMessage = "trainer_payload_gap grace=1 keep_last=1";
                return decision;
            }
            decision.handled = true;
            decision.action = Action.RENDER_PLACEHOLDER;
            return decision;
        }
        if (isTrainingConnection && decision.updatedSessionActive) {
            if (decision.updatedLastPayloadAtMs > 0L
                    && (nowMs - decision.updatedLastPayloadAtMs) <= payloadGraceMs) {
                decision.handled = true;
                decision.action = Action.KEEP_LAST;
                decision.logMessage = "trainer_payload_missing grace=1 keep_last=1";
                return decision;
            }
            decision.handled = true;
            decision.action = Action.SHOW_WAITING;
            return decision;
        }

        decision.handled = isTrainingConnection;
        return decision;
    }
}
