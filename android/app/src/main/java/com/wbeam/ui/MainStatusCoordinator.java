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
                MainActivityStatusTracker.UpdateInput.create()
                        .setState(state)
                        .setInfo(info)
                        .setBps(bps)
                        .setDefaultState(stateIdle)
                        .setErrorState(stateError)
                        .setNowMs(SystemClock.elapsedRealtime())
                        .setCriticalLogStaleMs(30_000L)
                        .setLastCriticalErrorInfo(statusState.criticalErrorInfo)
                        .setLastCriticalErrorLogAtMs(statusState.criticalErrorLogAtMs)
        );
        statusState.uiState = next.getState();
        statusState.uiInfo = next.getInfo();
        statusState.uiBps = next.getBps();
        statusState.criticalErrorInfo = next.getCriticalErrorInfo();
        statusState.criticalErrorLogAtMs = next.getCriticalErrorLogAtMs();
        if (next.shouldLogCritical()) {
            criticalLogHandler.onCritical(next.getCriticalLogLine());
            Log.e(logTag, next.getCriticalLogLine());
        }
    }

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
