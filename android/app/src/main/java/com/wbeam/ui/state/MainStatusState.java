package com.wbeam.ui.state;

import com.wbeam.ui.MainActivityStatusPresenter;

@SuppressWarnings({"java:S1104", "java:S107"})
public final class MainStatusState {
    private String uiState;
    private String uiInfo;
    private long uiBps;
    private String criticalErrorInfo;
    private long criticalErrorLogAtMs;
    private String statsLine;

    public MainStatusState(String defaultUiState, String defaultUiInfo) {
        uiState = defaultUiState;
        uiInfo = defaultUiInfo;
        uiBps = 0L;
        criticalErrorInfo = "";
        criticalErrorLogAtMs = 0L;
        statsLine = MainActivityStatusPresenter.DEFAULT_STATS_LINE;
    }

    public String getUiState() {
        return uiState;
    }

    public void setUiState(String uiState) {
        this.uiState = uiState;
    }

    public String getUiInfo() {
        return uiInfo;
    }

    public void setUiInfo(String uiInfo) {
        this.uiInfo = uiInfo;
    }

    public long getUiBps() {
        return uiBps;
    }

    public void setUiBps(long uiBps) {
        this.uiBps = uiBps;
    }

    public String getCriticalErrorInfo() {
        return criticalErrorInfo;
    }

    public void setCriticalErrorInfo(String criticalErrorInfo) {
        this.criticalErrorInfo = criticalErrorInfo;
    }

    public long getCriticalErrorLogAtMs() {
        return criticalErrorLogAtMs;
    }

    public void setCriticalErrorLogAtMs(long criticalErrorLogAtMs) {
        this.criticalErrorLogAtMs = criticalErrorLogAtMs;
    }

    public String getStatsLine() {
        return statsLine;
    }

    public void setStatsLine(String statsLine) {
        this.statsLine = statsLine;
    }
}
