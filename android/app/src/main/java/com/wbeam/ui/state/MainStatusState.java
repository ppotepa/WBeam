package com.wbeam.ui.state;

import com.wbeam.ui.MainActivityStatusPresenter;

public final class MainStatusState {
    public String uiState;
    public String uiInfo;
    public long uiBps;
    public String criticalErrorInfo;
    public long criticalErrorLogAtMs;
    public String statsLine;

    public MainStatusState(String defaultUiState, String defaultUiInfo) {
        uiState = defaultUiState;
        uiInfo = defaultUiInfo;
        uiBps = 0L;
        criticalErrorInfo = "";
        criticalErrorLogAtMs = 0L;
        statsLine = MainActivityStatusPresenter.DEFAULT_STATS_LINE;
    }
}
