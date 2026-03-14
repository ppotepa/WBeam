package com.wbeam.ui;

public final class MainActivityStatusTracker {
    /**
     * Plain data carrier for status tracker state.
     */
    public static final class State {
        private String lastUiState;
        private String lastUiInfo;
        private long lastUiBps;
        private String lastCriticalErrorInfo;
        private long lastCriticalErrorLogAtMs;

        public String getLastUiState() { return lastUiState; }
        public String getLastUiInfo() { return lastUiInfo; }
        public long getLastUiBps() { return lastUiBps; }
        public String getLastCriticalErrorInfo() { return lastCriticalErrorInfo; }
        public long getLastCriticalErrorLogAtMs() { return lastCriticalErrorLogAtMs; }
    }

    /**
     * Plain data carrier for status update result.
     */
    public static final class UpdateResult {
        private String state;
        private String info;
        private long bps;
        private String criticalErrorInfo;
        private long criticalErrorLogAtMs;
        private boolean shouldLogCritical;
        private String criticalLogLine;

        public String getState() { return state; }
        public String getInfo() { return info; }
        public long getBps() { return bps; }
        public String getCriticalErrorInfo() { return criticalErrorInfo; }
        public long getCriticalErrorLogAtMs() { return criticalErrorLogAtMs; }
        public boolean shouldLogCritical() { return shouldLogCritical; }
        public String getCriticalLogLine() { return criticalLogLine; }
    }

    private MainActivityStatusTracker() {
    }

    @SuppressWarnings("java:S107")
    public static UpdateResult update(
            String state,
            String info,
            long bps,
            String defaultState,
            String errorState,
            long nowMs,
            long criticalLogStaleMs,
            String lastCriticalErrorInfo,
            long lastCriticalErrorLogAtMs
    ) {
        UpdateResult result = new UpdateResult();
        result.state = MainActivityStatusPresenter.normalizeState(state, defaultState);
        result.info = MainActivityStatusPresenter.normalizeInfo(info);
        result.bps = bps;
        result.criticalErrorInfo = lastCriticalErrorInfo;
        result.criticalErrorLogAtMs = lastCriticalErrorLogAtMs;
        if (!errorState.equals(result.state) || !ErrorTextUtil.isCriticalUiInfo(result.info)) {
            return result;
        }

        boolean same = result.info.equals(lastCriticalErrorInfo);
        boolean stale = (nowMs - lastCriticalErrorLogAtMs) > criticalLogStaleMs;
        if (!same || stale) {
            result.criticalErrorInfo = result.info;
            result.criticalErrorLogAtMs = nowMs;
            result.shouldLogCritical = true;
            result.criticalLogLine =
                    "status=" + result.state + " info=" + result.info + " bps=" + bps;
        }
        return result;
    }
}
