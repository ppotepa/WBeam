package com.wbeam.stream;

import android.media.MediaCodec;
import android.os.SystemClock;
import android.util.Log;

import com.wbeam.api.StatusListener;

import java.io.BufferedInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

@SuppressWarnings("S112") 
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

    @SuppressWarnings("S112")
    interface StreamWorker {
        @SuppressWarnings("S112")
        void run(BufferedInputStream input, MediaCodec[] codecHolder) throws Exception;
    }

    private final String tag;
    private final String host;
    private final int port;
    private final int socketRecvBufferSize;
    private final RuntimeState runtimeState;
    private final StatusListener statusListener;
    private final StreamWorker streamWorker;
    private final String stateConnecting;
    private final String stateStreaming;
    private final String stateError;

    @SuppressWarnings("java:S107")
    StreamReconnectLoop(
            String tag,
            String host,
            int port,
            int socketRecvBufferSize,
            RuntimeState runtimeState,
            StatusListener statusListener,
            StreamWorker streamWorker,
            String stateConnecting,
            String stateStreaming,
            String stateError
    ) {
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
 @SuppressWarnings({"java:S3776","java:S2093","java:S112","java:S1181","java:S2140"})

    void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

        while (runtimeState.isRunning()) {
            final MediaCodec[] codecHolder = {null};
            try {
                statusListener.onStatus(stateConnecting, "connecting to " + host + ":" + port, 0);
                statusListener.onStats(
                        "fps in/out: - | drops: " + runtimeState.getDroppedTotal()
                                + " | late: " + runtimeState.getTooLateTotal()
                                + " | reconnects: " + runtimeState.getReconnects()
                );

                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 2000);
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(socketRecvBufferSize);
                socket.setSoTimeout(5_000);
                runtimeState.setSocket(socket);
                runtimeState.incrementSessionConnectId();
                runtimeState.resetSampleSeq();

                statusListener.onStatus(stateStreaming, "connected [framed]", 0);
                streamWorker.run(new BufferedInputStream(socket.getInputStream(), 256 * 1024), codecHolder);

            } catch (Exception e) {
                if (runtimeState.isRunning()) {
                    long reconnects = runtimeState.incrementReconnects();
                    long reconnectDelayMs = Math.min(5000L, runtimeState.getReconnectDelayMs() + 400L);
                    runtimeState.setReconnectDelayMs(reconnectDelayMs);
                    String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                    boolean isException = (e instanceof Exception);
                    if (isException && H264TcpPlayer.isExpectedStreamClose((Exception) e)) {
                        Log.w(tag, "stream worker reconnect #" + reconnects
                                + " delay_ms=" + reconnectDelayMs + " reason=" + reason);
                        statusListener.onStatus(stateConnecting, "stream reconnecting: " + reason, 0);
                    } else {
                        Log.e(tag, "stream worker failed", e);
                        statusListener.onStatus(stateError, "stream error: " + e.getClass().getSimpleName(), 0);
                    }
                    statusListener.onStats(
                            "fps in/out: - | drops: " + runtimeState.getDroppedTotal()
                                    + " | late: " + runtimeState.getTooLateTotal()
                                    + " | reconnects: " + runtimeState.getReconnects()
                    );
                }
            } catch (Error err) {
                // Preserve previous behaviour of surfacing fatal errors to Android runtime.
                throw err;
            } finally {
                runtimeState.closeSocket();
                MediaCodec codec = codecHolder[0];
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (Exception ignored) {
                        // Best-effort shutdown; codec might already be stopped.
                    }
                    try {
                        codec.release();
                    } catch (Exception ignored) {
                        // Release is best-effort as codec may already be torn down.
                    }
                }
            }

            if (runtimeState.isRunning()) {
                long reconnectDelayMs = runtimeState.getReconnectDelayMs();
                long jitterBound = Math.max(1L, reconnectDelayMs / 4L + 1L);
                long jitterMs = (long) (Math.random() * jitterBound);
                SystemClock.sleep(reconnectDelayMs + jitterMs);
            }
        }
    }
}
