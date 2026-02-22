package com.wbeam.stream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.wbeam.BuildConfig;
import com.wbeam.ClientMetricsSample;
import com.wbeam.api.StatusListener;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
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
    private static final int    PORT = 5000;

    // ── State strings ─────────────────────────────────────────────────────────
    private static final String STATE_CONNECTING = "connecting";
    private static final String STATE_STREAMING  = "streaming";
    private static final String STATE_ERROR      = "error";

    // ── WBTP/1 framing constants ──────────────────────────────────────────────
    private static final int  FRAME_MAGIC             = 0x57425450; // "WBTP"
    private static final byte FRAME_VERSION           = 0x01;
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
    private static final int  HELLO_MODE_STABLE       = 0x20;
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
        this.frameBufferBudgetFrames = computeFrameBufferBudget(frameUs);
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
        // P1.1: elevate to realtime audio priority to reduce decode jitter
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

        while (running) {
            final MediaCodec[] codecHolder = {null};
            try {
                statusListener.onStatus(STATE_CONNECTING, "connecting to " + HOST + ":" + PORT, 0);
                statusListener.onStats(
                        "fps in/out: - | drops: " + droppedTotal
                                + " | late: " + tooLateTotal
                                + " | reconnects: " + reconnects
                );

                socket = new Socket();
                socket.connect(new InetSocketAddress(HOST, PORT), 2000);
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(SOCKET_RECV_BUFFER_SIZE);
                socket.setSoTimeout(5_000); // P1.1: cap blocking read to 5s
                sessionConnectId++;
                sampleSeq = 0;

                // Codec is created inside framedDecodeLoop after reading the
                // WBTP HELLO which carries the codec-type flag (H.264 or HEVC).
                statusListener.onStatus(STATE_STREAMING, "connected [framed]", 0);
                // C3: framed-only transport for deterministic frame boundaries and metrics.
                framedDecodeLoop(new BufferedInputStream(socket.getInputStream(), 256 * 1024), codecHolder);

            } catch (Throwable e) {
                if (running) {
                    reconnects++;
                    reconnectDelayMs = Math.min(5000, reconnectDelayMs + 400);
                    String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                    boolean isException = (e instanceof Exception);
                    if (isException && isExpectedStreamClose((Exception) e)) {
                        Log.w(TAG, "stream worker reconnect #" + reconnects
                                + " delay_ms=" + reconnectDelayMs + " reason=" + reason);
                        statusListener.onStatus(STATE_CONNECTING, "stream reconnecting: " + reason, 0);
                    } else {
                        Log.e(TAG, "stream worker failed", e);
                        statusListener.onStatus(STATE_ERROR, "stream error: " + e.getClass().getSimpleName(), 0);
                    }
                    statusListener.onStats(
                            "fps in/out: - | drops: " + droppedTotal
                                    + " | late: " + tooLateTotal
                                    + " | reconnects: " + reconnects
                    );
                }
            } finally {
                closeSocket();
                MediaCodec c = codecHolder[0];
                if (c != null) {
                    try { c.stop(); } catch (Exception ignored) {}
                    try { c.release(); } catch (Exception ignored) {}
                }
            }

            if (running) {
                long jitterBound = Math.max(1L, reconnectDelayMs / 4L + 1L);
                long jitterMs = (long) (Math.random() * jitterBound);
                SystemClock.sleep(reconnectDelayMs + jitterMs);
            }
        }
    }

    // ── Legacy AnnexB / AVCC decode loop ──────────────────────────────────────

    @SuppressWarnings("unused")
    private void decodeLoop(InputStream input, MediaCodec codec) throws IOException {
        byte[] readBuf  = new byte[64 * 1024];
        byte[] streamBuf = new byte[512 * 1024];
        int sHead = 0;
        int sTail = 0;
        int streamMode = -1;
        int avgNalSize = 1200;
        int pendingDecodeQueue = 0;
        int renderQueueDepth   = 0;

        long frames        = 0;
        long bytes         = 0;
        long inFrames      = 0;
        long outFrames     = 0;
        long droppedSec    = 0;
        long tooLateSec    = 0;
        long   decodeNsTotal = 0;
        long[] decodeNsBuf   = new long[128];
        long[] decodeNsScratch = new long[128];
        int    decodeNsBufN  = 0;
        long   renderNsMax   = 0;
        long lastLog = SystemClock.elapsedRealtime();
        long lastPresentMs   = SystemClock.elapsedRealtime();
        long pendingWithNoPresent = 0;
        long lastDecodeProgressMs = SystemClock.elapsedRealtime();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        DrainStats drainStats = new DrainStats();

        while (running) {
            int count = input.read(readBuf);
            if (count < 0) throw new IOException("stream closed");
            if (count == 0) continue;

            int avail = sTail - sHead;
            if (sTail + count > streamBuf.length) {
                if (avail + count > streamBuf.length) {
                    int keep = streamBuf.length - count;
                    if (keep <= 0) { sHead = 0; sTail = 0; avail = 0; }
                    else {
                        int newHead = sHead + (avail - keep);
                        System.arraycopy(streamBuf, newHead, streamBuf, 0, keep);
                        sHead = 0; sTail = keep; avail = keep;
                    }
                    droppedSec++;
                } else {
                    if (avail > 0) System.arraycopy(streamBuf, sHead, streamBuf, 0, avail);
                    sHead = 0; sTail = avail;
                }
            }
            System.arraycopy(readBuf, 0, streamBuf, sTail, count);
            sTail += count;
            bytes += count;

            avail = sTail - sHead;
            if (streamMode < 0 && avail >= 8) {
                int probe = findStartCode(streamBuf, sHead, Math.min(sHead + 128, sTail));
                streamMode = (probe >= 0) ? 0 : 1;
            }

            if (streamMode == 1) {
                while ((sTail - sHead) >= 4) {
                    int nalSize =
                            ((streamBuf[sHead]     & 0xFF) << 24) |
                            ((streamBuf[sHead + 1] & 0xFF) << 16) |
                            ((streamBuf[sHead + 2] & 0xFF) << 8)  |
                            ((streamBuf[sHead + 3] & 0xFF));
                    if (nalSize <= 0 || nalSize > streamBuf.length) { sHead += 1; droppedSec++; continue; }
                    if ((sTail - sHead) < 4 + nalSize) break;

                    frames++;
                        drainLatestFrame(codec, bufferInfo, drainStats,
                            true,
                            pendingDecodeQueue >= DECODE_QUEUE_MAX_FRAMES ? 16_000 : 5_000);
                    pendingDecodeQueue = Math.max(0, pendingDecodeQueue - drainStats.releasedCount);
                    if (drainStats.releasedCount > 0) lastDecodeProgressMs = SystemClock.elapsedRealtime();
                    else if (pendingDecodeQueue >= DECODE_QUEUE_MAX_FRAMES
                            && (SystemClock.elapsedRealtime() - lastDecodeProgressMs) > 300) {
                        pendingDecodeQueue = DECODE_QUEUE_MAX_FRAMES - 1;
                    }
                    outFrames  += drainStats.renderedCount;
                    tooLateSec += drainStats.droppedLateCount;
                    renderNsMax = Math.max(renderNsMax, drainStats.renderNsMax);
                    renderQueueDepth = drainStats.renderedCount > 0 ? 1 : 0;

                    if (pendingDecodeQueue >= DECODE_QUEUE_MAX_FRAMES) {
                        if (isRecoveryNal(streamBuf, sHead + 4, nalSize)) {
                            long t0 = SystemClock.elapsedRealtimeNanos();
                            if (queueNal(codec, streamBuf, sHead + 4, nalSize, frames * frameUs, 1_000)) {
                                long dn = SystemClock.elapsedRealtimeNanos() - t0;
                                decodeNsTotal += dn; decodeNsBuf[(decodeNsBufN++) & 127] = dn;
                                avgNalSize = ((avgNalSize * 7) + nalSize) / 8;
                                inFrames++; pendingDecodeQueue = Math.min(DECODE_QUEUE_MAX_FRAMES, pendingDecodeQueue + 1);
                                lastDecodeProgressMs = SystemClock.elapsedRealtime();
                            } else { droppedSec++; }
                        } else { droppedSec++; }
                    } else {
                        long t0 = SystemClock.elapsedRealtimeNanos();
                        if (queueNal(codec, streamBuf, sHead + 4, nalSize, frames * frameUs, 1_000)) {
                            long dn = SystemClock.elapsedRealtimeNanos() - t0;
                            decodeNsTotal += dn; decodeNsBuf[(decodeNsBufN++) & 127] = dn;
                            avgNalSize = ((avgNalSize * 7) + nalSize) / 8;
                            inFrames++; pendingDecodeQueue++;
                            lastDecodeProgressMs = SystemClock.elapsedRealtime();
                        } else { droppedSec++; }
                    }
                    sHead += 4 + nalSize;
                }
            } else {
                int nalStart = findStartCode(streamBuf, sHead, sTail);
                if (nalStart < 0) {
                    sHead = Math.max(sHead, sTail - 3);
                } else {
                    sHead = nalStart;
                    while (true) {
                        int next = findStartCode(streamBuf, sHead + 3, sTail);
                        if (next < 0) break;
                        int nalSize = next - sHead;
                        if (nalSize > 0) {
                            frames++;
                                drainLatestFrame(codec, bufferInfo, drainStats,
                                    true,
                                    pendingDecodeQueue >= DECODE_QUEUE_MAX_FRAMES ? 16_000 : 5_000);
                            pendingDecodeQueue = Math.max(0, pendingDecodeQueue - drainStats.releasedCount);
                            if (drainStats.releasedCount > 0) lastDecodeProgressMs = SystemClock.elapsedRealtime();
                            else if (pendingDecodeQueue >= DECODE_QUEUE_MAX_FRAMES
                                    && (SystemClock.elapsedRealtime() - lastDecodeProgressMs) > 300) {
                                pendingDecodeQueue = DECODE_QUEUE_MAX_FRAMES - 1;
                            }
                            outFrames  += drainStats.renderedCount;
                            tooLateSec += drainStats.droppedLateCount;
                            renderNsMax = Math.max(renderNsMax, drainStats.renderNsMax);
                            renderQueueDepth = drainStats.renderedCount > 0 ? 1 : 0;

                            if (pendingDecodeQueue >= DECODE_QUEUE_MAX_FRAMES) {
                                if (isRecoveryNal(streamBuf, sHead, nalSize)) {
                                    long t0 = SystemClock.elapsedRealtimeNanos();
                                    if (queueNal(codec, streamBuf, sHead, nalSize, frames * frameUs, 1_000)) {
                                        long dn = SystemClock.elapsedRealtimeNanos() - t0;
                                        decodeNsTotal += dn; decodeNsBuf[(decodeNsBufN++) & 127] = dn;
                                        avgNalSize = ((avgNalSize * 7) + nalSize) / 8;
                                        inFrames++; pendingDecodeQueue = Math.min(DECODE_QUEUE_MAX_FRAMES, pendingDecodeQueue + 1);
                                        lastDecodeProgressMs = SystemClock.elapsedRealtime();
                                    } else { droppedSec++; }
                                } else { droppedSec++; }
                            } else {
                                long t0 = SystemClock.elapsedRealtimeNanos();
                                if (queueNal(codec, streamBuf, sHead, nalSize, frames * frameUs, 1_000)) {
                                    long dn = SystemClock.elapsedRealtimeNanos() - t0;
                                    decodeNsTotal += dn; decodeNsBuf[(decodeNsBufN++) & 127] = dn;
                                    avgNalSize = ((avgNalSize * 7) + nalSize) / 8;
                                    inFrames++; pendingDecodeQueue++;
                                    lastDecodeProgressMs = SystemClock.elapsedRealtime();
                                } else { droppedSec++; }
                            }
                        }
                        sHead = next;
                    }
                }
            }

            if (drainStats.renderedCount > 0) { lastPresentMs = SystemClock.elapsedRealtime(); pendingWithNoPresent = 0; }
            else if (inFrames > 0)            { pendingWithNoPresent++; }
            if (pendingWithNoPresent > 300 && (SystemClock.elapsedRealtime() - lastPresentMs) > 5_000) {
                throw new IOException("C5: black-screen watchdog: 0 frames presented in 5s with "
                        + pendingWithNoPresent + " decoded – reconnecting");
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastLog >= 1000) {
                droppedTotal += droppedSec; tooLateTotal += tooLateSec; reconnectDelayMs = 800;
                statusListener.onStatus(STATE_STREAMING, "rendering live desktop", bytes);
                statusListener.onStats(
                        "fps in/out: " + inFrames + "/" + outFrames
                                + " | drops: " + droppedTotal
                                + " | late: " + tooLateTotal
                                + " | q(t/d/r): "
                                + estimateTransportDepthFrames(sTail - sHead, avgNalSize) + "/"
                                + Math.min(DECODE_QUEUE_MAX_FRAMES, pendingDecodeQueue) + "/"
                                + renderQueueDepth
                                + " | reconnects: " + reconnects
                );
                double decodeMsP50 = inFrames > 0 ? (decodeNsTotal / 1_000_000.0) / inFrames : 0.0;
                double decodeMsP95 = percentileMs(decodeNsBuf, Math.min(decodeNsBufN, 128), 0.95, decodeNsScratch);
                double renderMsP95 = renderNsMax / 1_000_000.0;
                statusListener.onClientMetrics(new ClientMetricsSample(
                        inFrames, inFrames, outFrames, bytes,
                        decodeMsP50, decodeMsP95, renderMsP95, 0.0, 0.0,
                        estimateTransportDepthFrames(sTail - sHead, avgNalSize),
                        Math.min(DECODE_QUEUE_MAX_FRAMES, pendingDecodeQueue),
                        Math.min(RENDER_QUEUE_MAX_FRAMES, renderQueueDepth),
                        0, droppedTotal, tooLateTotal,
                        (sessionConnectId << 32) | (sampleSeq++ & 0xFFFFFFFFL)
                ));
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, String.format(Locale.US,
                        "[decode/legacy] in=%d out=%d drop=%d late=%d qD=%d/%d qR=%d/%d"
                            + " dec_p95=%.1fms ren_p95=%.1fms noPresent=%d reconn=%d",
                        inFrames, outFrames, droppedSec, tooLateSec,
                        Math.min(DECODE_QUEUE_MAX_FRAMES, pendingDecodeQueue), DECODE_QUEUE_MAX_FRAMES,
                        renderQueueDepth, RENDER_QUEUE_MAX_FRAMES,
                        decodeMsP95, renderMsP95, pendingWithNoPresent, reconnects));
                }
                bytes = 0; inFrames = 0; outFrames = 0; droppedSec = 0; tooLateSec = 0;
                decodeNsTotal = 0; decodeNsBufN = 0; renderNsMax = 0; lastLog = now;
            }
        }
    }

    // ── WBTP/1 framed decode loop ─────────────────────────────────────────────

    /**
     * WBTP/1 framed decode loop — host sends 22-byte WBTP/1 header + payload per access unit.
     * Header: magic(4) ver(1) flags(1) seq(4) capture_ts_us(8) payload_len(4)
     * Eliminates AnnexB start-code scanning entirely and gives exact PTS per frame.
     */
    private void framedDecodeLoop(InputStream input, MediaCodec[] codecRef) throws IOException {
        byte[] helloBuf   = new byte[HELLO_HEADER_SIZE];
        byte[] hdrBuf     = new byte[FRAME_HEADER_SIZE];
        byte[] payloadBuf = new byte[FRAME_PAYLOAD_INITIAL_CAP];
        long   bytes      = 0L;
        long   inFrames   = 0L;
        long   outFrames  = 0L;
        long   droppedSec = 0L;
        long   tooLateSec = 0L;
        int    maxPayloadSeen      = 0;
        long   payloadGrowEvents   = 0L;
        long   resyncSuccessSec    = 0L;
        long   resyncFailSec       = 0L;
        long   flushCountSec       = 0L;
        long   recoveryUnlockSec   = 0L;
        long   waitGateDropsSec    = 0L;
        long   decodeNsTotal       = 0L;
        long[] decodeNsBuf         = new long[128];
        long[] decodeNsScratch     = new long[128];
        int    decodeNsBufN        = 0;
        long   renderNsMax         = 0L;
        long   lastLog             = SystemClock.elapsedRealtime();
        long   lastPresentMs       = SystemClock.elapsedRealtime();
        long   totalInSincePresent = 0L;
        long   lastDecodeProgressMs = SystemClock.elapsedRealtime();
        long   expectedSeq = -1L;
        long   lastQueuedPtsUs = -1L;
        boolean flushIssued        = false;
        // After codec.flush() the decoder has no reference frames — P-frames
        // queued before the next IDR will produce corrupted blocks.  This flag
        // blocks non-IDR frames until the codec gets a clean anchor.
        boolean waitForKeyframe    = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        DrainStats drainStats = new DrainStats();
        int pendingDecodeQueue = 0;

        // ── Read WBTP HELLO handshake ─────────────────────────────────────────
        readFully(input, helloBuf, HELLO_HEADER_SIZE);
        int helloMagic = ((helloBuf[0] & 0xFF) << 24) | ((helloBuf[1] & 0xFF) << 16)
                | ((helloBuf[2] & 0xFF) << 8) | (helloBuf[3] & 0xFF);
        int helloLen = ((helloBuf[6] & 0xFF) << 8) | (helloBuf[7] & 0xFF);
        if (helloMagic != HELLO_MAGIC || helloBuf[4] != HELLO_VERSION
                || helloLen != HELLO_HEADER_SIZE) {
            throw new IOException("WBTP: bad stream hello magic/version/len");
        }
        // byte[5]: codec flags + mode bits
        final int helloFlags    = helloBuf[5] & 0xFF;
        final boolean isPng     = (helloFlags & HELLO_CODEC_PNG) != 0;
        final boolean isHevc    = !isPng && (helloFlags & HELLO_CODEC_HEVC) != 0;
        final int streamMode    = helloFlags & HELLO_MODE_MASK;
        final boolean isUltraMode = streamMode == HELLO_MODE_ULTRA;
        final String  videoMime = isPng ? "image/png" : (isHevc ? "video/hevc" : "video/avc");
        long streamSessionId =
                ((helloBuf[8]  & 0xFFL) << 56) | ((helloBuf[9]  & 0xFFL) << 48)
              | ((helloBuf[10] & 0xFFL) << 40) | ((helloBuf[11] & 0xFFL) << 32)
              | ((helloBuf[12] & 0xFFL) << 24) | ((helloBuf[13] & 0xFFL) << 16)
              | ((helloBuf[14] & 0xFFL) <<  8) |  (helloBuf[15] & 0xFFL);
        String modeLabel = streamMode == HELLO_MODE_ULTRA
                ? "ultra"
                : (streamMode == HELLO_MODE_QUALITY ? "quality" : "stable");
        Log.i(TAG, String.format(Locale.US, "WBTP hello session=0x%016x codec=%s mode=%s",
            streamSessionId, isPng ? "PNG" : (isHevc ? "HEVC" : "AVC"), modeLabel));

        if (isPng) {
            framedDecodeLoopPng(input, hdrBuf, payloadBuf, isUltraMode);
            return;
        }

        final int seqGapBudget = computeSeqGapBudget(frameUs, isUltraMode);
        final boolean dropLateOutput = isUltraMode;

        // ── Capability guard – reject HEVC early on devices without a decoder ──
        if (isHevc && !codecSupported(videoMime)) {
            throw new IOException(
                "HEVC (video/hevc) decoder not available on this device "
                + "(API " + Build.VERSION.SDK_INT + "). "
                + "Configure the host to use H.264 (encoder=h264).");
        }

        // ── Create MediaCodec for the codec type signalled in HELLO ───────────
        final MediaCodec codec;
        try {
            codec = MediaCodec.createDecoderByType(videoMime);
            MediaFormat fmt = MediaFormat.createVideoFormat(videoMime, decodeWidth, decodeHeight);
            codec.configure(fmt, surface, null, 0);
            codec.start();
            codecRef[0] = codec;
        } catch (Exception e) {
            throw new IOException("decoder init failed for " + videoMime, e);
        }

        while (running) {
            // ── Read 22-byte header ───────────────────────────────────────────
            readFully(input, hdrBuf, FRAME_HEADER_SIZE);
            int magic = parseFrameMagic(hdrBuf);
            if (magic != FRAME_MAGIC) {
                boolean resynced = tryResyncHeader(input, hdrBuf, FRAME_RESYNC_SCAN_LIMIT);
                if (!resynced) {
                    resyncFailSec++;
                    throw new IOException("WBTP: bad frame magic 0x" + Integer.toHexString(magic)
                            + " – resync failed");
                }
                resyncSuccessSec++;
            }
            // byte[4]=version  byte[5]=flags (0x02=keyframe 0x04=eos)
            // seq: bytes 6..9  capture_ts_us: bytes 10..17  payload_len: bytes 18..21
                boolean frameIsKey = (hdrBuf[5] & FRAME_FLAG_KEYFRAME) != 0;
                long seqU32 = ((hdrBuf[6] & 0xFFL) << 24) | ((hdrBuf[7] & 0xFFL) << 16)
                    | ((hdrBuf[8] & 0xFFL) << 8) | (hdrBuf[9] & 0xFFL);
            long ptsUs = ((hdrBuf[10] & 0xFFL) << 56) | ((hdrBuf[11] & 0xFFL) << 48)
                    | ((hdrBuf[12] & 0xFFL) << 40) | ((hdrBuf[13] & 0xFFL) << 32)
                    | ((hdrBuf[14] & 0xFFL) << 24) | ((hdrBuf[15] & 0xFFL) << 16)
                    | ((hdrBuf[16] & 0xFFL) <<  8) |  (hdrBuf[17] & 0xFFL);
            int payloadLen = ((hdrBuf[18] & 0xFF) << 24) | ((hdrBuf[19] & 0xFF) << 16)
                    | ((hdrBuf[20] & 0xFF) << 8) | (hdrBuf[21] & 0xFF);

            if (payloadLen <= 0 || payloadLen > FRAME_PAYLOAD_HARD_CAP) {
                throw new IOException("WBTP: bad payload length " + payloadLen);
            }
            if (payloadLen > payloadBuf.length) {
                int newCap = Math.min(FRAME_PAYLOAD_HARD_CAP, nextPowerOfTwo(payloadLen));
                if (newCap < payloadLen) {
                    throw new IOException("WBTP: payload exceeds dynamic cap " + payloadLen);
                }
                Log.w(TAG, "WBTP payload buffer grow " + payloadBuf.length + " -> " + newCap
                        + " seq=" + seqU32 + " payload=" + payloadLen + " mode=" + modeLabel);
                payloadBuf = new byte[newCap];
                payloadGrowEvents++;
            }
            maxPayloadSeen = Math.max(maxPayloadSeen, payloadLen);

            // ── Read payload ──────────────────────────────────────────────────
            readFully(input, payloadBuf, payloadLen);
            bytes += FRAME_HEADER_SIZE + payloadLen;

            // Sequence gate: never display stale/out-of-order frames.
            if (expectedSeq < 0) {
                expectedSeq = seqU32;
            }
            if (seqU32 < expectedSeq) {
                droppedSec++;
                continue;
            }
            if (seqU32 > expectedSeq + seqGapBudget) {
                expectedSeq = seqU32;
            }

            // Timestamp gate: do not queue older capture timestamps.
            if (lastQueuedPtsUs > 0 && ptsUs + 1_000 < lastQueuedPtsUs) {
                droppedSec++;
                expectedSeq = seqU32 + 1;
                continue;
            }

                drainLatestFrame(codec, bufferInfo, drainStats,
                    dropLateOutput,
                    pendingDecodeQueue >= DECODE_QUEUE_MAX_FRAMES ? 16_000 : 5_000);
            long nowAfterDrain = SystemClock.elapsedRealtime();
            pendingDecodeQueue = Math.max(0, pendingDecodeQueue - drainStats.releasedCount);
            if (drainStats.releasedCount > 0) lastDecodeProgressMs = nowAfterDrain;
            else if (pendingDecodeQueue >= DECODE_QUEUE_MAX_FRAMES
                    && (nowAfterDrain - lastDecodeProgressMs) > 300) {
                pendingDecodeQueue = DECODE_QUEUE_MAX_FRAMES - 1;
            }
            if (drainStats.renderedCount > 0) {
                outFrames += drainStats.renderedCount;
                lastPresentMs = nowAfterDrain;
                totalInSincePresent = 0;
                flushIssued = false;
            }
            tooLateSec  += drainStats.droppedLateCount;
            renderNsMax  = Math.max(renderNsMax, drainStats.renderNsMax);

            // Gate 1: don't feed P-frames to a freshly flushed codec.
            // Gate 2: normal decode-queue depth + recovery-NAL bypass.
            boolean isRecovery = frameIsKey || containsRecoveryNal(payloadBuf, payloadLen, isHevc);
                if (waitForKeyframe && isRecovery) {
                waitForKeyframe = false;
                recoveryUnlockSec++;
                Log.w(TAG, "recovery-unlock: seq=" + seqU32 + " key=" + frameIsKey
                    + " payload=" + payloadLen + " qDecode=" + pendingDecodeQueue);
                }
            boolean canQueue = !waitForKeyframe
                    && (pendingDecodeQueue < DECODE_QUEUE_MAX_FRAMES || isRecovery);
            if (canQueue) {
                long t0 = SystemClock.elapsedRealtimeNanos();
                if (queueNal(codec, payloadBuf, 0, payloadLen, ptsUs, 1_000)) {
                    long dn = SystemClock.elapsedRealtimeNanos() - t0;
                    decodeNsTotal += dn;
                    decodeNsBuf[(decodeNsBufN++) & 127] = dn;
                    inFrames++;
                    totalInSincePresent++;
                    pendingDecodeQueue = Math.min(DECODE_QUEUE_MAX_FRAMES, pendingDecodeQueue + 1);
                    lastDecodeProgressMs = nowAfterDrain;
                    lastQueuedPtsUs = ptsUs;
                    expectedSeq = seqU32 + 1;
                } else {
                    droppedSec++;
                    expectedSeq = seqU32 + 1;
                }
            } else {
                droppedSec++;
                if (waitForKeyframe && !isRecovery) {
                    waitGateDropsSec++;
                    if ((waitGateDropsSec & 31) == 1) {
                        Log.w(TAG, "waitForKeyframe drop: seq=" + seqU32
                                + " payload=" + payloadLen
                                + " dropped=" + waitGateDropsSec
                                + " qDecode=" + pendingDecodeQueue);
                    }
                }
                waitForKeyframe = true;
                expectedSeq = seqU32 + 1;
            }

            // ── C5 recovery ladder ────────────────────────────────────────────
            long nowMs = SystemClock.elapsedRealtime();
            long noPresentMs = nowMs - lastPresentMs;
            if (!flushIssued
                    && totalInSincePresent >= NO_PRESENT_MIN_IN_FRAMES_FLUSH
                    && noPresentMs >= NO_PRESENT_FLUSH_MS) {
                try {
                    codec.flush();
                    pendingDecodeQueue = 0;
                    flushIssued = true;
                    waitForKeyframe = true; // block P-frames until next IDR
                    flushCountSec++;
                    Log.w(TAG, "C5 ladder L1: codec.flush() due to no-present");
                    statusListener.onStatus(STATE_CONNECTING, "decoder stalled: flushing codec", 0);
                } catch (Exception flushErr) {
                    throw new IOException("C5: codec.flush failed", flushErr);
                }
            }
            if (totalInSincePresent >= NO_PRESENT_MIN_IN_FRAMES_RECONNECT
                    && noPresentMs >= NO_PRESENT_RECONNECT_MS) {
                Log.w(TAG, "C5 ladder L2: reconnect framed stream due to no-present");
                statusListener.onStatus(STATE_CONNECTING, "decoder stalled: reconnecting stream", 0);
                throw new IOException("C5: no frames presented for " + noPresentMs
                        + "ms (" + totalInSincePresent + " decoded) – reconnect");
            }
            if (totalInSincePresent >= NO_PRESENT_MIN_IN_FRAMES_HARD
                    && noPresentMs >= NO_PRESENT_HARD_RESET_MS) {
                Log.w(TAG, "C5 ladder L3: hard reconnect watchdog");
                statusListener.onStatus(STATE_CONNECTING, "decoder watchdog: hard reconnect", 0);
                throw new IOException("C5: hard watchdog: "
                        + totalInSincePresent + " frames decoded, 0 presented for "
                        + noPresentMs + "ms – reconnect");
            }
            // Absolute guard: if nothing was presented for 5s, always reconnect.
            if (noPresentMs >= NO_PRESENT_HARD_RESET_MS) {
                Log.w(TAG, "C5 absolute guard: reconnect after 5s with no present");
                statusListener.onStatus(STATE_CONNECTING, "decoder stalled >5s: reconnecting", 0);
                throw new IOException("C5 absolute guard: no frame presented for " + noPresentMs + "ms");
            }

            // ── 1-second stats ────────────────────────────────────────────────
            if (nowMs - lastLog >= 1000) {
                droppedTotal += droppedSec; tooLateTotal += tooLateSec; reconnectDelayMs = 800;
                statusListener.onStatus(STATE_STREAMING, "rendering live desktop [framed]", bytes);
                statusListener.onStats(
                        "fps in/out: " + inFrames + "/" + outFrames
                                + " | drops: " + droppedTotal
                                + " | late: " + tooLateTotal
                                + " | q(d/r): " + pendingDecodeQueue + "/" + (drainStats.renderedCount > 0 ? 1 : 0)
                                + " | max_payload: " + (maxPayloadSeen / 1024) + "KB"
                                + " | reconnects: " + reconnects
                );
                double decodeMsP50 = inFrames > 0 ? (decodeNsTotal / 1_000_000.0) / inFrames : 0.0;
                double decodeMsP95 = percentileMs(decodeNsBuf, Math.min(decodeNsBufN, 128), 0.95, decodeNsScratch);
                double renderMsP95 = renderNsMax / 1_000_000.0;
                long nowEpochUs = System.currentTimeMillis() * 1000L;
                long transportLagUs = (lastQueuedPtsUs > 0 && nowEpochUs > lastQueuedPtsUs)
                    ? (nowEpochUs - lastQueuedPtsUs)
                    : 0L;
                int transportQueueDepth = (int) Math.max(0L, Math.min(
                    16L,
                    transportLagUs / Math.max(1L, frameUs)
                ));
                int queueDecodeDepth = Math.min(DECODE_QUEUE_MAX_FRAMES, pendingDecodeQueue);
                int queueRenderDepth = Math.min(RENDER_QUEUE_MAX_FRAMES, drainStats.renderedCount > 0 ? 1 : 0);
                statusListener.onClientMetrics(new ClientMetricsSample(
                        inFrames, inFrames, outFrames, bytes,
                        decodeMsP50, decodeMsP95, renderMsP95, 0.0, 0.0,
                    transportQueueDepth,
                    queueDecodeDepth,
                    queueRenderDepth,
                        0, droppedTotal, tooLateTotal,
                        (sessionConnectId << 32) | (sampleSeq++ & 0xFFFFFFFFL)
                ));
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, String.format(Locale.US,
                        "[decode/framed] in=%d out=%d drop=%d late=%d"
                            + " qD=%d/%d qR=%d dec_p95=%.1fms ren_p95=%.1fms"
                            + " maxPayload=%d grow=%d flush=%d unlock=%d waitDrop=%d"
                            + " resync_ok=%d resync_fail=%d noPresent=%d reconn=%d",
                        inFrames, outFrames, droppedSec, tooLateSec,
                        queueDecodeDepth, DECODE_QUEUE_MAX_FRAMES,
                        drainStats.renderedCount > 0 ? 1 : 0,
                        decodeMsP95, renderMsP95,
                        maxPayloadSeen, payloadGrowEvents, flushCountSec, recoveryUnlockSec, waitGateDropsSec,
                        resyncSuccessSec, resyncFailSec,
                        totalInSincePresent, reconnects));
                }
                bytes = 0; inFrames = 0; outFrames = 0; droppedSec = 0; tooLateSec = 0;
                maxPayloadSeen = 0; resyncSuccessSec = 0; resyncFailSec = 0;
                flushCountSec = 0; recoveryUnlockSec = 0; waitGateDropsSec = 0;
                decodeNsTotal = 0; decodeNsBufN = 0; renderNsMax = 0; lastLog = nowMs;
            }
        }
    }

    /**
     * PNG framed decode path: each WBTP payload is one PNG frame.
     * Decodes bitmap on CPU and blits to Surface canvas.
     */
    private void framedDecodeLoopPng(InputStream input, byte[] hdrBuf, byte[] payloadBuf, boolean isUltraMode) throws IOException {
        long bytes = 0L;
        long inFrames = 0L;
        long outFrames = 0L;
        long droppedSec = 0L;
        long decodeNsTotal = 0L;
        long[] decodeNsBuf = new long[128];
        long[] decodeNsScratch = new long[128];
        int decodeNsBufN = 0;
        int maxPayloadSeen = 0;
        long payloadGrowEvents = 0L;
        long resyncSuccessSec = 0L;
        long resyncFailSec = 0L;
        long lastLog = SystemClock.elapsedRealtime();
        long lastPresentMs = SystemClock.elapsedRealtime();
        long expectedSeq = -1L;
        long lastQueuedPtsUs = -1L;
        final int seqGapBudget = computeSeqGapBudget(frameUs, isUltraMode);
        final boolean dropBacklogFrames = isUltraMode;
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Rect dstRect = new Rect();
        final int backlogFrameBudget = frameBufferBudgetFrames;

        while (running) {
            readFully(input, hdrBuf, FRAME_HEADER_SIZE);
            int magic = parseFrameMagic(hdrBuf);
            if (magic != FRAME_MAGIC) {
                boolean resynced = tryResyncHeader(input, hdrBuf, FRAME_RESYNC_SCAN_LIMIT);
                if (!resynced) {
                    resyncFailSec++;
                    throw new IOException("WBTP: bad frame magic 0x" + Integer.toHexString(magic)
                            + " – resync failed");
                }
                resyncSuccessSec++;
            }

            long seqU32 = ((hdrBuf[6] & 0xFFL) << 24) | ((hdrBuf[7] & 0xFFL) << 16)
                    | ((hdrBuf[8] & 0xFFL) << 8) | (hdrBuf[9] & 0xFFL);
            long ptsUs = ((hdrBuf[10] & 0xFFL) << 56) | ((hdrBuf[11] & 0xFFL) << 48)
                    | ((hdrBuf[12] & 0xFFL) << 40) | ((hdrBuf[13] & 0xFFL) << 32)
                    | ((hdrBuf[14] & 0xFFL) << 24) | ((hdrBuf[15] & 0xFFL) << 16)
                    | ((hdrBuf[16] & 0xFFL) << 8) | (hdrBuf[17] & 0xFFL);

            if (expectedSeq < 0) {
                expectedSeq = seqU32;
            }
            if (seqU32 < expectedSeq) {
                droppedSec++;
                continue;
            }
            if (seqU32 > expectedSeq + seqGapBudget) {
                expectedSeq = seqU32;
            }
            if (lastQueuedPtsUs > 0 && ptsUs + 1_000 < lastQueuedPtsUs) {
                droppedSec++;
                expectedSeq = seqU32 + 1;
                continue;
            }

            int payloadLen = ((hdrBuf[18] & 0xFF) << 24) | ((hdrBuf[19] & 0xFF) << 16)
                    | ((hdrBuf[20] & 0xFF) << 8) | (hdrBuf[21] & 0xFF);
            if (payloadLen <= 0 || payloadLen > FRAME_PAYLOAD_HARD_CAP) {
                throw new IOException("WBTP: bad payload length " + payloadLen);
            }

            if (payloadLen > payloadBuf.length) {
                int newCap = Math.min(FRAME_PAYLOAD_HARD_CAP, nextPowerOfTwo(payloadLen));
                if (newCap < payloadLen) {
                    throw new IOException("WBTP: payload exceeds dynamic cap " + payloadLen);
                }
                Log.w(TAG, "WBTP PNG payload buffer grow " + payloadBuf.length + " -> " + newCap);
                payloadBuf = new byte[newCap];
                payloadGrowEvents++;
            }
            maxPayloadSeen = Math.max(maxPayloadSeen, payloadLen);

            readFully(input, payloadBuf, payloadLen);
            bytes += FRAME_HEADER_SIZE + payloadLen;

            int payloadEstimate = Math.max(8 * 1024, maxPayloadSeen + FRAME_HEADER_SIZE);
            int backlogBytes = Math.max(0, input.available());
            int backlogFramesEstimate = payloadEstimate > 0 ? (backlogBytes / payloadEstimate) : 0;
            int skippedBacklog = 0;

            while (dropBacklogFrames && backlogFramesEstimate > backlogFrameBudget) {
                readFully(input, hdrBuf, FRAME_HEADER_SIZE);
                int magic2 = parseFrameMagic(hdrBuf);
                if (magic2 != FRAME_MAGIC) {
                    boolean resynced2 = tryResyncHeader(input, hdrBuf, FRAME_RESYNC_SCAN_LIMIT);
                    if (!resynced2) {
                        resyncFailSec++;
                        throw new IOException("WBTP: bad frame magic 0x" + Integer.toHexString(magic2)
                                + " – resync failed");
                    }
                    resyncSuccessSec++;
                }

                seqU32 = ((hdrBuf[6] & 0xFFL) << 24) | ((hdrBuf[7] & 0xFFL) << 16)
                        | ((hdrBuf[8] & 0xFFL) << 8) | (hdrBuf[9] & 0xFFL);
                ptsUs = ((hdrBuf[10] & 0xFFL) << 56) | ((hdrBuf[11] & 0xFFL) << 48)
                        | ((hdrBuf[12] & 0xFFL) << 40) | ((hdrBuf[13] & 0xFFL) << 32)
                        | ((hdrBuf[14] & 0xFFL) << 24) | ((hdrBuf[15] & 0xFFL) << 16)
                        | ((hdrBuf[16] & 0xFFL) << 8) | (hdrBuf[17] & 0xFFL);

                int nextLen = ((hdrBuf[18] & 0xFF) << 24) | ((hdrBuf[19] & 0xFF) << 16)
                        | ((hdrBuf[20] & 0xFF) << 8) | (hdrBuf[21] & 0xFF);
                if (nextLen <= 0 || nextLen > FRAME_PAYLOAD_HARD_CAP) {
                    throw new IOException("WBTP: bad payload length " + nextLen);
                }
                if (nextLen > payloadBuf.length) {
                    int newCap = Math.min(FRAME_PAYLOAD_HARD_CAP, nextPowerOfTwo(nextLen));
                    if (newCap < nextLen) {
                        throw new IOException("WBTP: payload exceeds dynamic cap " + nextLen);
                    }
                    Log.w(TAG, "WBTP PNG payload buffer grow " + payloadBuf.length + " -> " + newCap);
                    payloadBuf = new byte[newCap];
                    payloadGrowEvents++;
                }

                payloadLen = nextLen;
                maxPayloadSeen = Math.max(maxPayloadSeen, payloadLen);
                readFully(input, payloadBuf, payloadLen);
                bytes += FRAME_HEADER_SIZE + payloadLen;
                skippedBacklog++;

                payloadEstimate = Math.max(8 * 1024, maxPayloadSeen + FRAME_HEADER_SIZE);
                backlogBytes = Math.max(0, input.available());
                backlogFramesEstimate = payloadEstimate > 0 ? (backlogBytes / payloadEstimate) : 0;
            }

            if (skippedBacklog > 0) {
                droppedSec += skippedBacklog;
            }

            long t0 = SystemClock.elapsedRealtimeNanos();
            Bitmap bitmap = BitmapFactory.decodeByteArray(payloadBuf, 0, payloadLen);
            if (bitmap == null) {
                droppedSec++;
                expectedSeq = seqU32 + 1;
            } else {
                Canvas canvas = null;
                try {
                    canvas = surface.lockCanvas(null);
                    if (canvas != null) {
                        dstRect.set(0, 0, canvas.getWidth(), canvas.getHeight());
                        canvas.drawBitmap(bitmap, null, dstRect, paint);
                        outFrames++;
                        lastPresentMs = SystemClock.elapsedRealtime();
                        lastQueuedPtsUs = ptsUs;
                        expectedSeq = seqU32 + 1;
                    } else {
                        droppedSec++;
                        expectedSeq = seqU32 + 1;
                    }
                } catch (Exception e) {
                    droppedSec++;
                    expectedSeq = seqU32 + 1;
                } finally {
                    if (canvas != null) {
                        try {
                            surface.unlockCanvasAndPost(canvas);
                        } catch (Exception ignored) {
                        }
                    }
                }
                bitmap.recycle();
            }

            long dn = SystemClock.elapsedRealtimeNanos() - t0;
            decodeNsTotal += dn;
            decodeNsBuf[(decodeNsBufN++) & 127] = dn;
            inFrames++;

            long nowMs = SystemClock.elapsedRealtime();
            if (nowMs - lastPresentMs >= NO_PRESENT_HARD_RESET_MS) {
                Log.w(TAG, "PNG absolute guard: reconnect after 5s with no present");
                statusListener.onStatus(STATE_CONNECTING, "png stalled >5s: reconnecting", 0);
                throw new IOException("PNG absolute guard: no frame presented for " + (nowMs - lastPresentMs) + "ms");
            }
            if (nowMs - lastLog >= 1000) {
                droppedTotal += droppedSec;
                reconnectDelayMs = 800;

                statusListener.onStatus(STATE_STREAMING, "rendering live desktop [framed/png]", bytes);
                statusListener.onStats(
                        "fps in/out: " + inFrames + "/" + outFrames
                                + " | drops: " + droppedTotal
                                + " | late: " + tooLateTotal
                        + " | q(d/r): " + Math.min(backlogFramesEstimate, backlogFrameBudget) + "/0"
                                + " | max_payload: " + (maxPayloadSeen / 1024) + "KB"
                                + " | reconnects: " + reconnects
                );

                double decodeMsP50 = inFrames > 0 ? (decodeNsTotal / 1_000_000.0) / inFrames : 0.0;
                double decodeMsP95 = percentileMs(decodeNsBuf, Math.min(decodeNsBufN, 128), 0.95, decodeNsScratch);
                long nowEpochUs = System.currentTimeMillis() * 1000L;
                long transportLagUs = (lastQueuedPtsUs > 0 && nowEpochUs > lastQueuedPtsUs)
                    ? (nowEpochUs - lastQueuedPtsUs)
                    : 0L;
                int transportQueueDepth = (int) Math.max(0L, Math.min(
                    16L,
                    transportLagUs / Math.max(1L, frameUs)
                ));
                statusListener.onClientMetrics(new ClientMetricsSample(
                        inFrames, inFrames, outFrames, bytes,
                        decodeMsP50, decodeMsP95, 0.0, 0.0, 0.0,
                    transportQueueDepth,
                        0,
                        0,
                        0, droppedTotal, tooLateTotal,
                        (sessionConnectId << 32) | (sampleSeq++ & 0xFFFFFFFFL)
                ));

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, String.format(Locale.US,
                            "[decode/framed/png] in=%d out=%d drop=%d dec_p95=%.1fms maxPayload=%d grow=%d resync_ok=%d resync_fail=%d reconn=%d",
                            inFrames, outFrames, droppedSec, decodeMsP95,
                            maxPayloadSeen, payloadGrowEvents, resyncSuccessSec, resyncFailSec, reconnects));
                }

                bytes = 0;
                inFrames = 0;
                outFrames = 0;
                droppedSec = 0;
                maxPayloadSeen = 0;
                payloadGrowEvents = 0;
                resyncSuccessSec = 0;
                resyncFailSec = 0;
                decodeNsTotal = 0;
                decodeNsBufN = 0;
                lastLog = nowMs;
            }
        }
    }

    // ── Static utilities ──────────────────────────────────────────────────────

    private static int parseFrameMagic(byte[] hdrBuf) {
        return ((hdrBuf[0] & 0xFF) << 24) | ((hdrBuf[1] & 0xFF) << 16)
                | ((hdrBuf[2] & 0xFF) << 8) | (hdrBuf[3] & 0xFF);
    }

    private static int computeFrameBufferBudget(long frameUs) {
        long safeFrameUs = Math.max(1L, frameUs);
        long fps = Math.max(1L, 1_000_000L / safeFrameUs);
        long budget = (fps + 9L) / 10L;
        return (int) Math.max(PNG_BUFFER_MIN_FRAMES, Math.min(PNG_BUFFER_MAX_FRAMES, budget));
    }

    private static int computeSeqGapBudget(long frameUs, boolean isUltraMode) {
        long safeFrameUs = Math.max(1L, frameUs);
        long fps = Math.max(1L, 1_000_000L / safeFrameUs);
        long base = isUltraMode ? Math.max(30L, fps / 2L) : fps;
        return (int) Math.max(60L, Math.min(240L, base));
    }

    private static void readFully(InputStream input, byte[] buf, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = input.read(buf, read, len - read);
            if (n < 0) throw new IOException("stream closed");
            read += n;
        }
    }

    private static boolean tryResyncHeader(InputStream input, byte[] hdrBuf, int scanLimit)
            throws IOException {
        int scanned = 0;
        while (scanned < scanLimit) {
            System.arraycopy(hdrBuf, 1, hdrBuf, 0, FRAME_HEADER_SIZE - 1);
            int n = input.read(hdrBuf, FRAME_HEADER_SIZE - 1, 1);
            if (n < 0) return false;
            scanned++;
            if (parseFrameMagic(hdrBuf) == FRAME_MAGIC) return true;
        }
        return false;
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) return 1;
        if (value >= (1 << 30)) return 1 << 30;
        return Integer.highestOneBit(value - 1) << 1;
    }

    /**
     * Percentile in ms from a nanosecond sample buffer without heap allocation.
     * Caller provides reusable scratch buffer to avoid Arrays.copyOf churn.
     */
    private static double percentileMs(long[] buf, int n, double q, long[] scratch) {
        if (n <= 0) return 0.0;
        System.arraycopy(buf, 0, scratch, 0, n);
        int idx = (int) Math.min(n - 1, Math.max(0, (int) (n * q)));
        long selected = selectKth(scratch, n, idx);
        return selected / 1_000_000.0;
    }

    private static long selectKth(long[] values, int n, int k) {
        int left = 0;
        int right = n - 1;
        while (left < right) {
            int pivotIndex = partition(values, left, right, (left + right) >>> 1);
            if (k == pivotIndex) return values[k];
            if (k < pivotIndex) right = pivotIndex - 1;
            else left = pivotIndex + 1;
        }
        return values[left];
    }

    private static int partition(long[] values, int left, int right, int pivotIndex) {
        long pivotValue = values[pivotIndex];
        swap(values, pivotIndex, right);
        int store = left;
        for (int i = left; i < right; i++) {
            if (values[i] < pivotValue) {
                swap(values, store, i);
                store++;
            }
        }
        swap(values, right, store);
        return store;
    }

    private static void swap(long[] values, int i, int j) {
        long t = values[i];
        values[i] = values[j];
        values[j] = t;
    }

    private static boolean queueNal(
            MediaCodec codec,
            byte[]     data,
            int        offset,
            int        size,
            long       ptsUs,
            long       inputTimeoutUs
    ) {
        int inputIndex = codec.dequeueInputBuffer(inputTimeoutUs);
        if (inputIndex < 0) return false;
        ByteBuffer inputBuffer;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            inputBuffer = codec.getInputBuffer(inputIndex);
        } else {
            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            inputBuffer = (inputBuffers != null && inputIndex < inputBuffers.length)
                    ? inputBuffers[inputIndex]
                    : null;
        }
        if (inputBuffer == null) {
            codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0);
            return false;
        }
        inputBuffer.clear();
        if (size > inputBuffer.remaining()) {
            codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0);
            return false;
        }
        inputBuffer.put(data, offset, size);
        codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0);
        return true;
    }

    private static void drainLatestFrame(
            MediaCodec codec,
            MediaCodec.BufferInfo info,
            DrainStats stats,
            boolean dropLateOutput,
            long firstTimeoutUs
    ) {
        stats.reset();
        int  latestRenderableIndex = -1;
        long timeoutUs             = firstTimeoutUs;

        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, timeoutUs);
            timeoutUs = 0; // non-blocking for all subsequent backlog frames
            if (outputIndex >= 0) {
                stats.releasedCount++;
                boolean renderable = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                if (!renderable) { codec.releaseOutputBuffer(outputIndex, false); continue; }
                if (dropLateOutput) {
                    if (latestRenderableIndex >= 0) {
                        codec.releaseOutputBuffer(latestRenderableIndex, false);
                        stats.droppedLateCount++;
                    }
                    latestRenderableIndex = outputIndex;
                } else {
                    long renderStartNs = SystemClock.elapsedRealtimeNanos();
                    codec.releaseOutputBuffer(outputIndex, true);
                    stats.renderedCount++;
                    stats.renderNsMax = Math.max(
                            stats.renderNsMax,
                            SystemClock.elapsedRealtimeNanos() - renderStartNs
                    );
                }
                continue;
            }
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER
                    || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break;
            break;
        }

        if (dropLateOutput && latestRenderableIndex >= 0) {
            long renderStartNs = SystemClock.elapsedRealtimeNanos();
            codec.releaseOutputBuffer(latestRenderableIndex, true);
            stats.renderedCount = 1;
            stats.renderNsMax   = SystemClock.elapsedRealtimeNanos() - renderStartNs;
        }
    }

    private static int estimateTransportDepthFrames(int streamLen, int avgNalSize) {
        int denom = Math.max(512, avgNalSize);
        if (streamLen <= 0) return 0;
        return Math.min(8, streamLen / denom);
    }

    private static int findStartCode(byte[] data, int from, int toExclusive) {
        int limit = toExclusive - 3;
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) return i;
                if (i + 3 < toExclusive && data[i + 2] == 0 && data[i + 3] == 1) return i;
            }
        }
        return -1;
    }

    private static boolean isRecoveryNal(byte[] data, int offset, int size) {
        int type = firstNalType(data, offset, size);
        return type == 5 || type == 7 || type == 8; // IDR/SPS/PPS
    }

    /**
     * Returns true if this device has a hardware or software decoder for the
     * given MIME type.  Uses the deprecated static MediaCodecList API that
     * works on API 16+ (the new instance-based API requires API 21).
     */
    @SuppressWarnings("deprecation")
    private static boolean codecSupported(String mimeType) {
        int count = MediaCodecList.getCodecCount();
        for (int i = 0; i < count; i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mimeType)) return true;
            }
        }
        return false;
    }

    private static boolean containsRecoveryNal(byte[] data, int size, boolean isHevc) {
        // Single-pass: find start code, read NAL type byte directly.
        // H.264: type = data[nalByte] & 0x1F;  recovery = {5,7,8} (IDR/SPS/PPS)
        // H.265: type = (data[nalByte] >> 1) & 0x3F;  recovery = {19,20,21,32,33,34}
        int limit = Math.min(size, FRAME_RESYNC_SCAN_LIMIT);
        int i = 0;
        while (i < limit - 3) {
            if (data[i] != 0 || data[i + 1] != 0) { i++; continue; }
            final int nalByte;
            if (data[i + 2] == 1) {
                nalByte = i + 3;
            } else if (i + 3 < limit && data[i + 2] == 0 && data[i + 3] == 1) {
                nalByte = i + 4;
            } else { i++; continue; }
            if (nalByte >= limit) break;
            final int type = isHevc
                    ? ((data[nalByte] & 0x7E) >> 1)   // HEVC: bits[6:1] of first header byte
                    : (data[nalByte] & 0x1F);          // H.264: bits[4:0]
            if (isHevc
                    ? (type == 19 || type == 20 || type == 21 || type == 32 || type == 33 || type == 34)
                    : (type == 5  || type == 7  || type == 8))
                return true;
            i = nalByte + 1;
        }
        return false;
    }

    private static int firstNalType(byte[] data, int offset, int size) {
        if (size <= 0 || offset < 0 || offset >= data.length) return -1;
        int end = Math.min(data.length, offset + size);
        int i = offset;
        if (i + 3 < end && data[i] == 0 && data[i + 1] == 0) {
            if (data[i + 2] == 1)
                i += 3;
            else if (i + 4 < end && data[i + 2] == 0 && data[i + 3] == 1)
                i += 4;
        }
        if (i >= end) return -1;
        return data[i] & 0x1F;
    }

    private void closeSocket() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) {}
        }
    }

    private static boolean isExpectedStreamClose(Exception e) {
        if (!(e instanceof IOException)) return false;
        String msg = e.getMessage();
        if (msg == null) return false;
        String m = msg.toLowerCase(Locale.US);
        return m.contains("stream closed")
                || m.contains("connection reset")
                || m.contains("broken pipe")
                || m.contains("software caused connection abort");
    }

    // ── Inner helpers ─────────────────────────────────────────────────────────

    private static final class DrainStats {
        int  releasedCount;
        int  renderedCount;
        int  droppedLateCount;
        long renderNsMax;

        void reset() {
            releasedCount  = 0;
            renderedCount  = 0;
            droppedLateCount = 0;
            renderNsMax    = 0;
        }
    }
}
