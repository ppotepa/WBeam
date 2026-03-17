package com.wbeam.stream;

import android.media.MediaCodec;
import android.os.SystemClock;
import android.util.Log;

import com.wbeam.api.StatusListener;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Random;

final class StreamReconnectLoop {

    interface RuntimeState {
        boolean isRunning();
        long getDroppedTotal();
        long getTooLateTotal();
        long getReconnects();
        long incrementReconnects();
        long getReconnectDelayMs();
        void setReconnectDelayMs(long reconnectDelayMs);
        long incrementSessionConnectId();
        void resetSampleSeq();
        void setSocket(Socket socket);
        void closeSocket();
    }

    @SuppressWarnings("java:S112")
    interface StreamWorker {
        void run(BufferedInputStream input, MediaCodec[] codecHolder) throws Exception;
    }

    static class Config {
        final String tag;
        final String host;
        final int port;
        final int socketRecvBufferSize;
        final RuntimeState runtimeState;
        final StatusListener statusListener;
        final StreamWorker streamWorker;
        final String stateConnecting;
        final String stateStreaming;
        final String stateError;

        @SuppressWarnings("java:S107")
        Config(String tag, String host, int port, int socketRecvBufferSize,
               RuntimeState runtimeState, StatusListener statusListener,
               StreamWorker streamWorker, String stateConnecting,
               String stateStreaming, String stateError) {
            this.tag = tag;
            this.host = host;
            this.port = port;
            this.socketRecvBufferSize = socketRecvBufferSize;
            this.runtimeState = runtimeState;
            this.statusListener = statusListener;
            this.streamWorker = streamWorker;
            this.stateConnecting = stateConnecting;
            this.stateStreaming = stateStreaming;
            this.stateError = stateError;
        }
    }

    private final Config config;
    private final Random random = new Random();

    StreamReconnectLoop(Config config) {
        this.config = config;
    }

    void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);

        while (config.runtimeState.isRunning()) {
            final MediaCodec[] codecHolder = {null};
            try {
                logConnectionAttempt();
                connectAndStream(codecHolder);
            } catch (Exception e) {
                handleStreamError(e);
            } finally {
                config.runtimeState.closeSocket();
                releaseCodec(codecHolder[0]);
            }

            if (config.runtimeState.isRunning()) {
                sleepWithJitter();
            }
        }
    }

    private void logConnectionAttempt() {
        config.statusListener.onStatus(config.stateConnecting,
                "connecting to " + config.host + ":" + config.port, 0);
        config.statusListener.onStats(
                "fps in/out: - | drops: " + config.runtimeState.getDroppedTotal()
                        + " | late: " + config.runtimeState.getTooLateTotal()
                        + " | reconnects: " + config.runtimeState.getReconnects()
        );
    }

    private void connectAndStream(MediaCodec[] codecHolder) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.host, config.port), 2000);
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(config.socketRecvBufferSize);
            socket.setSoTimeout(5_000);
            config.runtimeState.setSocket(socket);
            config.runtimeState.incrementSessionConnectId();
            config.runtimeState.resetSampleSeq();

            config.statusListener.onStatus(config.stateStreaming, "connected [framed]", 0);
            config.streamWorker.run(new BufferedInputStream(socket.getInputStream(), 256 * 1024), codecHolder);
        }
    }

    private void handleStreamError(Exception e) {
        if (config.runtimeState.isRunning()) {
            long reconnects = config.runtimeState.incrementReconnects();
            long reconnectDelayMs = Math.min(5000L, config.runtimeState.getReconnectDelayMs() + 400L);
            config.runtimeState.setReconnectDelayMs(reconnectDelayMs);
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            
            if (H264TcpPlayer.isExpectedStreamClose(e)) {
                Log.w(config.tag, "stream worker reconnect #" + reconnects
                        + " delay_ms=" + reconnectDelayMs + " reason=" + reason);
                config.statusListener.onStatus(config.stateConnecting, "stream reconnecting: " + reason, 0);
            } else {
                Log.e(config.tag, "stream worker failed", e);
                config.statusListener.onStatus(config.stateError, "stream error: " + e.getClass().getSimpleName(), 0);
            }
            config.statusListener.onStats(
                    "fps in/out: - | drops: " + config.runtimeState.getDroppedTotal()
                            + " | late: " + config.runtimeState.getTooLateTotal()
                            + " | reconnects: " + config.runtimeState.getReconnects()
            );
        }
    }

    private void releaseCodec(MediaCodec codec) {
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
                // Expected when codec is not in proper state
            }
            try {
                codec.release();
            } catch (IllegalStateException ignored) {
                // Expected when codec is already released
            }
        }
    }

    private void sleepWithJitter() {
        long reconnectDelayMs = config.runtimeState.getReconnectDelayMs();
        long jitterBound = Math.max(1L, reconnectDelayMs / 4L + 1L);
        long jitterMs = random.nextLong(jitterBound);
        SystemClock.sleep(reconnectDelayMs + jitterMs);
    }
}
