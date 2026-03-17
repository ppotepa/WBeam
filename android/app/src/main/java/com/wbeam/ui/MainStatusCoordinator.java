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
                MainActivityStatusTracker.UpdateInput.create()
                        .setState(state)
                        .setInfo(info)
                        .setBps(bps)
                        .setDefaultState(stateIdle)
                        .setErrorState(stateError)
                        .setNowMs(SystemClock.elapsedRealtime())
                        .setCriticalLogStaleMs(30_000L)
                        .setLastCriticalErrorInfo(statusState.getCriticalErrorInfo())
                        .setLastCriticalErrorLogAtMs(statusState.getCriticalErrorLogAtMs())
        );
        statusState.setUiState(next.getState());
        statusState.setUiInfo(next.getInfo());
        statusState.setUiBps(next.getBps());
        statusState.setCriticalErrorInfo(next.getCriticalErrorInfo());
        statusState.setCriticalErrorLogAtMs(next.getCriticalErrorLogAtMs());
        if (next.shouldLogCritical()) {
            criticalLogHandler.onCritical(next.getCriticalLogLine());
            Log.e(logTag, next.getCriticalLogLine());
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
                statusState.getUiState(),
                statusState.getUiInfo(),
                statusState.getUiBps(),
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
        statusState.setStatsLine(MainActivityStatusPresenter.normalizeStatsLine(line));
        statsText.setText(statusState.getStatsLine());
    }
}
