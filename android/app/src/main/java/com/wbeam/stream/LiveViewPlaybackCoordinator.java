package com.wbeam.stream;

import android.graphics.Rect;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceView;

import com.wbeam.ClientMetricsSample;
import com.wbeam.api.StatusListener;
import com.wbeam.ui.StreamConfigResolver;

import java.util.Locale;

public final class LiveViewPlaybackCoordinator {
    @FunctionalInterface
    public interface StatusSink {
        void onStatus(String state, String info, long bps);
    }

    @FunctionalInterface
    public interface StatsSink {
        void onStats(String line);
    }

    @FunctionalInterface
    public interface MetricsSink {
        void onMetrics(ClientMetricsSample metrics);
    }

    @FunctionalInterface
    public interface UiThreadRunner {
        void post(Runnable action);
    }

    @FunctionalInterface
    public interface UiAction {
        void run();
    }

    private LiveViewPlaybackCoordinator() {
    }

    @SuppressWarnings("java:S107")
    public static H264TcpPlayer start(
            String tag,
            Surface surface,
            H264TcpPlayer currentPlayer,
            SurfaceView preview,
            StreamConfigResolver.Resolved cfg,
            StatusSink statusSink,
            StatsSink statsSink,
            MetricsSink metricsSink,
            UiThreadRunner uiThreadRunner,
            UiAction onStarted,
            String stateError,
            String stateStreaming
    ) {
        if (surface == null || !surface.isValid()) {
            statusSink.onStatus(stateError, "surface not ready yet", 0);
            return currentPlayer;
        }
        if (currentPlayer != null && currentPlayer.isRunning()) {
            statusSink.onStatus(stateStreaming, "already running", 0);
            return currentPlayer;
        }

        long frameUs = Math.max(1L, 1_000_000L / Math.max(1, cfg.fps));
        logStartLiveViewConfig(tag, cfg, preview, surface);
        // Keep Surface buffer sized by layout to avoid centered "small video" rendering.
        preview.getHolder().setSizeFromLayout();
        logSurfaceFrame(tag, preview);

        H264TcpPlayer player = new H264TcpPlayer(
                surface,
                buildStatusListener(uiThreadRunner, statusSink, statsSink, metricsSink),
                cfg.width,
                cfg.height,
                frameUs
        );
        player.start();
        onStarted.run();
        return player;
    }

    public static H264TcpPlayer stop(
            H264TcpPlayer currentPlayer,
            UiAction onAfterStop,
            StatsSink statsSink,
            StatusSink statusSink,
            String stateIdle
    ) {
        if (currentPlayer != null) {
            currentPlayer.stop();
        }
        onAfterStop.run();
        statsSink.onStats("fps in/out: - | drops: - | late: - | q(t/d/r): -/-/- | reconnects: -");
        statusSink.onStatus(stateIdle, "stopped", 0);
        return null;
    }

    private static StatusListener buildStatusListener(
            UiThreadRunner uiThreadRunner,
            StatusSink statusSink,
            StatsSink statsSink,
            MetricsSink metricsSink
    ) {
        return new StatusListener() {
            private String lastStatusState;
            private String lastStatusInfo;
            private long lastStatusBps = Long.MIN_VALUE;
            private String lastStatsLine;

            @Override
            public void onStatus(String state, String info, long bps) {
                if (bps == lastStatusBps
                        && equalsNullable(state, lastStatusState)
                        && equalsNullable(info, lastStatusInfo)) {
                    return;
                }
                lastStatusState = state;
                lastStatusInfo = info;
                lastStatusBps = bps;
                uiThreadRunner.post(() -> statusSink.onStatus(state, info, bps));
            }

            @Override
            public void onStats(String line) {
                if (equalsNullable(line, lastStatsLine)) {
                    return;
                }
                lastStatsLine = line;
                uiThreadRunner.post(() -> statsSink.onStats(line));
            }

            @Override
            public void onClientMetrics(ClientMetricsSample metrics) {
                metricsSink.onMetrics(metrics);
            }
        };
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void logStartLiveViewConfig(
            String tag,
            StreamConfigResolver.Resolved cfg,
            SurfaceView preview,
            Surface surface
    ) {
        Log.i(tag, String.format(
                Locale.US,
                "startLiveView: cfg=%dx%d@%dfps view=%dx%d surfaceValid=%s",
                cfg.width,
                cfg.height,
                cfg.fps,
                preview.getWidth(),
                preview.getHeight(),
                surface != null && surface.isValid()
        ));
    }

    private static void logSurfaceFrame(String tag, SurfaceView preview) {
        try {
            Rect frame = preview.getHolder().getSurfaceFrame();
            if (frame != null) {
                Log.i(tag, String.format(
                        Locale.US,
                        "startLiveView: surfaceFrame=%dx%d",
                        frame.width(),
                        frame.height()
                ));
            }
        } catch (Exception ignored) {
            // surface frame logging is best-effort
        }
    }
}
