package com.wbeam.stream;

import android.media.MediaCodec;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.wbeam.BuildConfig;
import com.wbeam.ClientMetricsSample;
import com.wbeam.api.StatusListener;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Locale;

/**
 * Connects to the host's WBTP/1 TCP stream on port 5000, decodes H.264 via
 * MediaCodec, and renders to a Surface. Automatically reconnects on disconnection.
 *
 * Protocol: 22-byte WBTP/1 frame header (big-endian) followed by H.264 access unit.
 * Header layout: magic(4) ver(1) flags(1) seq(4) capture_ts_us(8) payload_len(4)
 */
public final class H264TcpPlayer {

    private static final String TAG = "WBeamH264Player";

    // ── Network ───────────────────────────────────────────────────────────────
    private static final String HOST = resolveHost();
    private static final int    PORT = BuildConfig.WBEAM_STREAM_PORT;

    // ── State strings ─────────────────────────────────────────────────────────
    private static final String STATE_CONNECTING = "connecting";
    private static final String STATE_STREAMING  = "streaming";
    private static final String STATE_ERROR      = "error";

    // ── WBTP/1 framing constants ──────────────────────────────────────────────
    private static final int  FRAME_MAGIC             = 0x57425450; // "WBTP"
    private static final int  FRAME_FLAG_KEYFRAME     = 0x02;
    private static final int  FRAME_HEADER_SIZE       = 22;
    // 2 MB: headroom for bursty IDR frames at 25 Mbps+ over USB tether
    private static final int  SOCKET_RECV_BUFFER_SIZE = 2 * 1024 * 1024;
    private static final int  FRAME_PAYLOAD_INITIAL_CAP = 8 * 1024 * 1024;
    private static final int  FRAME_PAYLOAD_HARD_CAP  = 32 * 1024 * 1024;
    private static final int  FRAME_RESYNC_SCAN_LIMIT = 64 * 1024;
    private static final int  PNG_BUFFER_MIN_FRAMES   = 2;
    private static final int  PNG_BUFFER_MAX_FRAMES   = 12;
    private static final int  HELLO_MAGIC             = 0x57425331; // "WBS1"
    private static final int  HELLO_HEADER_SIZE       = 16;
    private static final byte HELLO_VERSION           = 0x01;
    /** HELLO byte[5] codec flag set by host when stream is HEVC/H.265. */
    private static final byte HELLO_CODEC_HEVC        = 0x01;
    /** HELLO byte[5] codec flag set by host when stream payload is PNG frames. */
    private static final byte HELLO_CODEC_PNG         = 0x02;
    private static final int  HELLO_MODE_MASK         = 0x30;
    private static final int  HELLO_MODE_ULTRA        = 0x10;
    private static final int  HELLO_MODE_QUALITY      = 0x30;

    // ── Decode pipeline limits ────────────────────────────────────────────────
    private static final int  DECODE_QUEUE_MAX_FRAMES = 2;
    private static final int  RENDER_QUEUE_MAX_FRAMES = 1;

    // ── Black-screen watchdog (C5 ladder) ─────────────────────────────────────
    private static final long NO_PRESENT_FLUSH_MS      = 1_500L;
    private static final long NO_PRESENT_RECONNECT_MS  = 3_000L;
    private static final long NO_PRESENT_HARD_RESET_MS = 5_000L;
    private static final int  NO_PRESENT_MIN_IN_FRAMES_FLUSH      = 12;
    private static final int  NO_PRESENT_MIN_IN_FRAMES_RECONNECT  = 24;
    private static final int  NO_PRESENT_MIN_IN_FRAMES_HARD       = 30;

    // ── Instance fields ───────────────────────────────────────────────────────
    private final Surface        surface;
    private final StatusListener statusListener;
    private final int            decodeWidth;
    private final int            decodeHeight;
    private final long           frameUs;
    private final int            frameBufferBudgetFrames;

    private volatile boolean running;
    private volatile long    reconnectDelayMs = 800L;
    private Thread           thread;
    private Socket           socket;
    private long reconnects    = 0L;
    private long droppedTotal  = 0L;
    private long tooLateTotal  = 0L;
    /** P2.2: incremented per TCP connection for correlated log tracing. */
    private long sessionConnectId = 0L;
    /** P2.2: incremented per stats window. */
    private long sampleSeq        = 0L;

    // ── Constructor ───────────────────────────────────────────────────────────

    public H264TcpPlayer(
            Surface surface,
            StatusListener statusListener,
            int decodeWidth,
            int decodeHeight,
            long frameUs
    ) {
        this.surface        = surface;
        this.statusListener = statusListener;
        this.decodeWidth    = decodeWidth;
        this.decodeHeight   = decodeHeight;
        this.frameUs        = frameUs;
        this.frameBufferBudgetFrames = StreamBufferMath.computeFrameBufferBudget(
                frameUs,
                PNG_BUFFER_MIN_FRAMES,
                PNG_BUFFER_MAX_FRAMES
        );
    }

    private static String resolveHost() {
        String configured = BuildConfig.WBEAM_STREAM_HOST;
        if (configured == null || configured.trim().isEmpty()) {
            configured = BuildConfig.WBEAM_HOST;
        }
        if (configured == null) {
            return "127.0.0.1";
        }
        String trimmed = configured.trim();
        return trimmed.isEmpty() ? "127.0.0.1" : trimmed;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void start() {
        if (running) return;
        running = true;
        Log.i(TAG, String.format(Locale.US,
                "start stream endpoint=tcp://%s:%d decode=%dx%d frameUs=%d",
                HOST, PORT, decodeWidth, decodeHeight, frameUs));
        thread = new Thread(this::runLoop, "wbeam-h264-player");
        thread.start();
    }

    public void stop() {
        running = false;
        closeSocket();
        if (thread != null) thread.interrupt();
    }

    public boolean isRunning() {
        return running;
    }

    // ── Main loop ─────────────────────────────────────────────────────────────

    private void runLoop() {
        PlayerRuntimeState runtimeState = new PlayerRuntimeState();

        new StreamReconnectLoop(
                TAG,
                HOST,
                PORT,
                SOCKET_RECV_BUFFER_SIZE,
                runtimeState,
                statusListener,
                this::runFramedDecodeLoop,
                STATE_CONNECTING,
                STATE_STREAMING,
                STATE_ERROR
        ).run();
    }

    // ── Legacy AnnexB / AVCC decode loop ──────────────────────────────────────

    private void framedDecodeLoop(InputStream input, MediaCodec[] codecRef) throws IOException {
        byte[] helloBuf = new byte[HELLO_HEADER_SIZE];
        byte[] hdrBuf = new byte[FRAME_HEADER_SIZE];
        byte[] payloadBuf = new byte[FRAME_PAYLOAD_INITIAL_CAP];

        WbtpProtocol.Hello hello = WbtpProtocol.readHello(
                input,
                helloBuf,
                HELLO_MAGIC,
                HELLO_VERSION,
                HELLO_HEADER_SIZE
        );
        final int helloFlags = hello.flags;
        final int streamMode = helloFlags & HELLO_MODE_MASK;
        final boolean isPng = (helloFlags & HELLO_CODEC_PNG) != 0;
        final boolean isUltraMode = streamMode == HELLO_MODE_ULTRA;
        long streamSessionId = hello.sessionId;
        String modeLabel;
        if (streamMode == HELLO_MODE_ULTRA) {
            modeLabel = "ultra";
        } else if (streamMode == HELLO_MODE_QUALITY) {
            modeLabel = "quality";
        } else {
            modeLabel = "stable";
        }
        boolean isHevc = !isPng && (helloFlags & HELLO_CODEC_HEVC) != 0;
        final String codecLabel;
        if (isPng) {
            codecLabel = "PNG";
        } else if (isHevc) {
            codecLabel = "HEVC";
        } else {
            codecLabel = "AVC";
        }
        Log.i(TAG, String.format(Locale.US, "WBTP hello session=0x%016x codec=%s mode=%s",
                streamSessionId, codecLabel, modeLabel));

        if (isPng) {
            framedDecodeLoopPng(input, hdrBuf, payloadBuf, isUltraMode);
            return;
        }

        PlayerRuntimeState runtimeState = new PlayerRuntimeState();

        new FramedVideoDecodeLoop(
                TAG,
                surface,
                statusListener,
                runtimeState,
                frameUs,
                decodeWidth,
                decodeHeight,
                HELLO_CODEC_HEVC,
                HELLO_CODEC_PNG,
                HELLO_MODE_MASK,
                HELLO_MODE_ULTRA,
                HELLO_MODE_QUALITY,
                DECODE_QUEUE_MAX_FRAMES,
                RENDER_QUEUE_MAX_FRAMES,
                FRAME_HEADER_SIZE,
                FRAME_MAGIC,
                FRAME_RESYNC_SCAN_LIMIT,
                FRAME_FLAG_KEYFRAME,
                FRAME_PAYLOAD_INITIAL_CAP,
                FRAME_PAYLOAD_HARD_CAP,
                NO_PRESENT_FLUSH_MS,
                NO_PRESENT_RECONNECT_MS,
                NO_PRESENT_HARD_RESET_MS,
                NO_PRESENT_MIN_IN_FRAMES_FLUSH,
                NO_PRESENT_MIN_IN_FRAMES_RECONNECT,
                NO_PRESENT_MIN_IN_FRAMES_HARD,
                STATE_CONNECTING,
                STATE_STREAMING
        ).run(input, codecRef, helloBuf, hdrBuf, payloadBuf);
    }

    private void runFramedDecodeLoop(InputStream input, MediaCodec[] codecRef) throws StreamException {
        try {
            framedDecodeLoop(input, codecRef);
        } catch (IOException e) {
            throw new StreamException("framed decode loop failed", e);
        }
    }

    private void framedDecodeLoopPng(InputStream input, byte[] hdrBuf, byte[] payloadBuf, boolean isUltraMode) throws IOException {
        PlayerRuntimeState runtimeState = new PlayerRuntimeState();
        FramedPngLoop.Config pngConfig = FramedPngLoop.Config.builder()
                .frameUs(frameUs)
                .frameHeaderSize(FRAME_HEADER_SIZE)
                .frameMagic(FRAME_MAGIC)
                .frameResyncScanLimit(FRAME_RESYNC_SCAN_LIMIT)
                .frameFlagKeyframe(FRAME_FLAG_KEYFRAME)
                .framePayloadHardCap(FRAME_PAYLOAD_HARD_CAP)
                .noPresentHardResetMs(NO_PRESENT_HARD_RESET_MS)
                .stateConnecting(STATE_CONNECTING)
                .stateStreaming(STATE_STREAMING)
                .frameBufferBudgetFrames(frameBufferBudgetFrames)
                .build();
        new FramedPngLoop(
                TAG,
                surface,
                statusListener,
                runtimeState,
                pngConfig
        ).run(input, hdrBuf, payloadBuf, isUltraMode);
    }

    private final class PlayerRuntimeState implements
            StreamReconnectLoop.RuntimeState,
            LegacyAnnexBDecodeLoop.RuntimeState,
            FramedPngLoop.RuntimeState,
            FramedVideoDecodeLoop.RuntimeState {

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public long getDroppedTotal() {
            return droppedTotal;
        }

        @Override
        public void addDroppedTotal(long delta) {
            droppedTotal += delta;
        }

        @Override
        public long getTooLateTotal() {
            return tooLateTotal;
        }

        @Override
        public void addTooLateTotal(long delta) {
            tooLateTotal += delta;
        }

        @Override
        public long getReconnects() {
            return reconnects;
        }

        @Override
        public long incrementReconnects() {
            reconnects++;
            return reconnects;
        }

        @Override
        public long getReconnectDelayMs() {
            return reconnectDelayMs;
        }

        @Override
        public void setReconnectDelayMs(long reconnectDelayMsValue) {
            reconnectDelayMs = reconnectDelayMsValue;
        }

        @Override
        public long incrementSessionConnectId() {
            sessionConnectId++;
            return sessionConnectId;
        }

        @Override
        public long getSessionConnectId() {
            return sessionConnectId;
        }

        @Override
        public void resetSampleSeq() {
            sampleSeq = 0;
        }

        @Override
        public long nextSampleSeq() {
            return sampleSeq++;
        }

        @Override
        public void setSocket(Socket nextSocket) {
            socket = nextSocket;
        }

        @Override
        public void closeSocket() {
            H264TcpPlayer.this.closeSocket();
        }

        @Override
        public void resetReconnectDelayMs() {
            reconnectDelayMs = 800L;
        }
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) { /* best-effort cleanup */ }
        }
    }

    static boolean isExpectedStreamClose(Exception e) {
        if (!(e instanceof IOException)) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.US);
        return m.contains("stream closed")
                || m.contains("connection reset")
                || m.contains("broken pipe")
                || m.contains("software caused connection abort");
    }

}
