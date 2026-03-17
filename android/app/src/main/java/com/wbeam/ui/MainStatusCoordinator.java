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
                new MainActivityStatusPresenter.RenderStatusInput()
                        .setStatusText(statusText)
                        .setDetailText(detailText)
                        .setBpsText(bpsText)
                        .setStatusLed(statusLed)
                        .setState(statusState.getUiState())
                        .setInfo(statusState.getUiInfo())
                        .setBps(statusState.getUiBps())
                        .setDaemonReachable(daemonReachable)
                        .setDaemonHostName(daemonHostName)
                        .setDaemonStateUi(effectiveDaemonStateUi)
                        .setStreamingState(stateStreaming)
                        .setConnectingState(stateConnecting)
        );
    }

    public static void updateStatsLine(
            MainStatusState statusState,
            TextView statsText,
            String line
    ) {
        String normalized = MainActivityStatusPresenter.normalizeStatsLine(line);
        if (normalized.equals(statusState.getStatsLine())) {
            return;
        }
        statusState.setStatsLine(normalized);
        statsText.setText(normalized);
    }
}
