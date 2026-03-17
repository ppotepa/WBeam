package com.wbeam.ui;

public final class MainActivityStatusTracker {
    public static final class UpdateResult {
        private final String state;
        private final String info;
        private final long bps;
        private final String criticalErrorInfo;
        private final long criticalErrorLogAtMs;
        private final boolean shouldLogCritical;
        private final String criticalLogLine;

        @SuppressWarnings("java:S107")
        private UpdateResult(
                String state,
                String info,
                long bps,
                String criticalErrorInfo,
                long criticalErrorLogAtMs,
                boolean shouldLogCritical,
                String criticalLogLine
        ) {
            this.state = state;
            this.info = info;
            this.bps = bps;
            this.criticalErrorInfo = criticalErrorInfo;
            this.criticalErrorLogAtMs = criticalErrorLogAtMs;
            this.shouldLogCritical = shouldLogCritical;
            this.criticalLogLine = criticalLogLine;
        }

        public String getState() {
            return state;
        }

        public String getInfo() {
            return info;
        }

        public long getBps() {
            return bps;
        }

        public String getCriticalErrorInfo() {
            return criticalErrorInfo;
        }

        public long getCriticalErrorLogAtMs() {
            return criticalErrorLogAtMs;
        }

        public boolean shouldLogCritical() {
            return shouldLogCritical;
        }

        public String getCriticalLogLine() {
            return criticalLogLine;
        }
    }

    @SuppressWarnings("java:S1104")
    public static final class UpdateInput {
        private String state;
        private String info;
        private long bps;
        private String defaultState;
        private String errorState;
        private long nowMs;
        private long criticalLogStaleMs;
        private String lastCriticalErrorInfo;
        private long lastCriticalErrorLogAtMs;

        public static UpdateInput create() {
            return new UpdateInput();
        }

        public String getState() {
            return state;
        }

        public String getInfo() {
            return info;
        }

        public long getBps() {
            return bps;
        }

        public String getDefaultState() {
            return defaultState;
        }

        public String getErrorState() {
            return errorState;
        }

        public long getNowMs() {
            return nowMs;
        }

        public long getCriticalLogStaleMs() {
            return criticalLogStaleMs;
        }

        public String getLastCriticalErrorInfo() {
            return lastCriticalErrorInfo;
        }

        public long getLastCriticalErrorLogAtMs() {
            return lastCriticalErrorLogAtMs;
        }

        public UpdateInput setState(String state) {
            this.state = state;
            return this;
        }

        public UpdateInput setInfo(String info) {
            this.info = info;
            return this;
        }

        public UpdateInput setBps(long bps) {
            this.bps = bps;
            return this;
        }

        public UpdateInput setDefaultState(String defaultState) {
            this.defaultState = defaultState;
            return this;
        }

        public UpdateInput setErrorState(String errorState) {
            this.errorState = errorState;
            return this;
        }

        public UpdateInput setNowMs(long nowMs) {
            this.nowMs = nowMs;
            return this;
        }

        public UpdateInput setCriticalLogStaleMs(long criticalLogStaleMs) {
            this.criticalLogStaleMs = criticalLogStaleMs;
            return this;
        }

        public UpdateInput setLastCriticalErrorInfo(String lastCriticalErrorInfo) {
            this.lastCriticalErrorInfo = lastCriticalErrorInfo;
            return this;
        }

        public UpdateInput setLastCriticalErrorLogAtMs(long lastCriticalErrorLogAtMs) {
            this.lastCriticalErrorLogAtMs = lastCriticalErrorLogAtMs;
            return this;
        }
    }

    private MainActivityStatusTracker() {
    }

    public static UpdateResult update(UpdateInput input) {
        String nextState = MainActivityStatusPresenter.normalizeState(
                input.getState(),
                input.getDefaultState()
        );
        String nextInfo = MainActivityStatusPresenter.normalizeInfo(input.getInfo());
        String nextCriticalErrorInfo = input.getLastCriticalErrorInfo();
        long nextCriticalErrorLogAtMs = input.getLastCriticalErrorLogAtMs();
        if (!input.getErrorState().equals(nextState) || !ErrorTextUtil.isCriticalUiInfo(nextInfo)) {
            return new UpdateResult(
                    nextState,
                    nextInfo,
                    input.getBps(),
                    nextCriticalErrorInfo,
                    nextCriticalErrorLogAtMs,
                    false,
                    null
            );
        }

        boolean same = nextInfo.equals(input.getLastCriticalErrorInfo());
        boolean stale = (input.getNowMs() - input.getLastCriticalErrorLogAtMs())
                > input.getCriticalLogStaleMs();
        boolean shouldLogCritical = false;
        String criticalLogLine = null;
        if (!same || stale) {
            nextCriticalErrorInfo = nextInfo;
            nextCriticalErrorLogAtMs = input.getNowMs();
            shouldLogCritical = true;
            criticalLogLine = "status=" + nextState + " info=" + nextInfo + " bps=" + input.getBps();
        }
        return new UpdateResult(
                nextState,
                nextInfo,
                input.getBps(),
                nextCriticalErrorInfo,
                nextCriticalErrorLogAtMs,
                shouldLogCritical,
                criticalLogLine
        );
    }
}
