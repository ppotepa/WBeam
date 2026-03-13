package com.wbeam.ui;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.wbeam.ui.state.MainStatusState;

public final class MainStatusCoordinator {
    public interface CriticalLogHandler {
        void onCritical(String line);
    }

    private MainStatusCoordinator() {
    }

    @SuppressWarnings("java:S107")
    public static void updateStatus(
            String state,
            String info,
            long bps,
            String stateIdle,
            String stateError,
            MainStatusState statusState,
            CriticalLogHandler criticalLogHandler,
            String logTag
    ) {
        MainActivityStatusTracker.UpdateResult next = MainActivityStatusTracker.update(
                state,
                info,
                bps,
                stateIdle,
                stateError,
                SystemClock.elapsedRealtime(),
                30_000L,
                statusState.criticalErrorInfo,
                statusState.criticalErrorLogAtMs
        );
        statusState.uiState = next.state;
        statusState.uiInfo = next.info;
        statusState.uiBps = next.bps;
        statusState.criticalErrorInfo = next.criticalErrorInfo;
        statusState.criticalErrorLogAtMs = next.criticalErrorLogAtMs;
        if (next.shouldLogCritical) {
            criticalLogHandler.onCritical(next.criticalLogLine);
            Log.e(logTag, next.criticalLogLine);
        }
    }

    @SuppressWarnings("java:S107")
    public static void renderStatus(
            TextView statusText,
            TextView detailText,
            TextView bpsText,
            View statusLed,
            MainStatusState statusState,
            boolean daemonReachable,
            String daemonHostName,
            String effectiveDaemonStateUi,
            String stateStreaming,
            String stateConnecting
    ) {
        MainActivityStatusPresenter.renderStatus(
                statusText,
                detailText,
                bpsText,
                statusLed,
                statusState.uiState,
                statusState.uiInfo,
                statusState.uiBps,
                daemonReachable,
                daemonHostName,
                effectiveDaemonStateUi,
                stateStreaming,
                stateConnecting
        );
    }

    public static void updateStatsLine(
            MainStatusState statusState,
            TextView statsText,
            String line
    ) {
        statusState.statsLine = MainActivityStatusPresenter.normalizeStatsLine(line);
        statsText.setText(statusState.statsLine);
    }
}
