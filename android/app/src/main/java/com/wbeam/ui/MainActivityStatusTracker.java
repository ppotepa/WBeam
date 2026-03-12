package com.wbeam.ui;

public final class MainActivityStatusTracker {
    public static final class State {
        public String lastUiState;
        public String lastUiInfo;
        public long lastUiBps;
        public String lastCriticalErrorInfo;
        public long lastCriticalErrorLogAtMs;
    }

    public static final class UpdateResult {
        public String state;
        public String info;
        public long bps;
        public String criticalErrorInfo;
        public long criticalErrorLogAtMs;
        public boolean shouldLogCritical;
        public String criticalLogLine;
    }

    private MainActivityStatusTracker() {
    }

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
