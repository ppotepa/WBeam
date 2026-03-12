package com.wbeam.stream;

import android.view.Surface;
import android.view.SurfaceView;

import com.wbeam.ui.StreamConfigResolver;

public final class MainStreamCoordinator {
    @FunctionalInterface
    public interface ConfigProvider {
        StreamConfigResolver.Resolved get();
    }

    private final String tag;
    private final SurfaceView previewSurface;
    private final VideoTestController videoTestController;
    private final ConfigProvider configProvider;
    private final LiveViewPlaybackCoordinator.StatusSink statusSink;
    private final LiveViewPlaybackCoordinator.StatsSink statsSink;
    private final LiveViewPlaybackCoordinator.MetricsSink metricsSink;
    private final LiveViewPlaybackCoordinator.UiThreadRunner uiThreadRunner;
    private final LiveViewPlaybackCoordinator.UiAction onStarted;
    private final LiveViewPlaybackCoordinator.UiAction onAfterStop;
    private final String stateError;
    private final String stateStreaming;
    private final String stateIdle;
    private H264TcpPlayer player;

    public MainStreamCoordinator(
            String tag,
            SurfaceView previewSurface,
            VideoTestController videoTestController,
            ConfigProvider configProvider,
            LiveViewPlaybackCoordinator.StatusSink statusSink,
            LiveViewPlaybackCoordinator.StatsSink statsSink,
            LiveViewPlaybackCoordinator.MetricsSink metricsSink,
            LiveViewPlaybackCoordinator.UiThreadRunner uiThreadRunner,
            LiveViewPlaybackCoordinator.UiAction onStarted,
            LiveViewPlaybackCoordinator.UiAction onAfterStop,
            String stateError,
            String stateStreaming,
            String stateIdle
    ) {
        this.tag = tag;
        this.previewSurface = previewSurface;
        this.videoTestController = videoTestController;
        this.configProvider = configProvider;
        this.statusSink = statusSink;
        this.statsSink = statsSink;
        this.metricsSink = metricsSink;
        this.uiThreadRunner = uiThreadRunner;
        this.onStarted = onStarted;
        this.onAfterStop = onAfterStop;
        this.stateError = stateError;
        this.stateStreaming = stateStreaming;
        this.stateIdle = stateIdle;
    }

    public void ensureDecoderRunning(Surface surface) {
        if (surface == null || !surface.isValid()) {
            return;
        }
        if (player != null && player.isRunning()) {
            return;
        }
        startLiveView(surface);
    }

    public void startLiveView(Surface surface) {
        videoTestController.release();
        player = LiveViewPlaybackCoordinator.start(
                tag,
                surface,
                player,
                previewSurface,
                configProvider.get(),
                statusSink,
                statsSink,
                metricsSink,
                uiThreadRunner,
                onStarted,
                stateError,
                stateStreaming
        );
    }

    public void stopLiveView() {
        player = LiveViewPlaybackCoordinator.stop(
                player,
                () -> {
                    videoTestController.release();
                    onAfterStop.run();
                },
                statsSink,
                statusSink,
                stateIdle
        );
    }

    public void stopPlaybackOnly() {
        if (player != null) {
            player.stop();
            player = null;
        }
    }
}
