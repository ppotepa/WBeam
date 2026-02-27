package com.proto.demo.transport;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import com.proto.demo.config.StreamConfig;
import com.proto.demo.ui.StatusUpdater;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.PriorityQueue;

/**
 * H264 over WBH1 framing → MediaCodec Surface decode.
 * Minimal latency path; assumes Annex B byte-stream with SPS/PPS present.
 */
public class H264Transport implements Transport {

    private static final String TAG = "WBeamH264";
    private static final int    PORT = StreamConfig.ADB_PORT;
    private static final int    MAX_NAL = 2 * 1024 * 1024; // guard
    private static final int    MAX_AU = 4 * 1024 * 1024; // one access unit (frame) may contain many NALs
    private static final int    NAL_NON_IDR = 1;
    private static final int    NAL_IDR = 5;
    private static final int    NAL_SPS = 7;
    private static final int    NAL_PPS = 8;
    private static final int    NAL_AUD = 9;
    private static final int    FLUSH_QUEUED = 1;
    private static final int    FLUSH_NOTHING = 0;
    private static final int    FLUSH_DROP_SOFT = -1;
    private static final int    FLUSH_DROP_HARD = -2;
    private static final int    QUEUE_OK = 1;
    private static final int    QUEUE_BUSY = 0;
    private static final int    QUEUE_ERROR = -1;
    private static final int    MAX_REORDER_FRAMES = 8;
    private static final long   REORDER_WAIT_MS = 12;
    private static final long   STAT_INTERVAL_MS = 2_000;
    private static final long   DECODER_STALL_MS = 3_500;
    private static final long   DECODER_RESTART_COOLDOWN_MS = 8_000;
    private static final long   DECODER_STALL_MIN_QUEUED = 120;
    private static final int    DECODER_MAX_RESTARTS_PER_WINDOW = 3;
    private static final long   DECODER_RESTART_WINDOW_MS = 60_000;

    private final StatusUpdater status;
    private final Surface       surface;
    private final String        sizeHint; // e.g., "1280x800"
    private final boolean       useReorder;

    private volatile boolean running = true;

    private static final class EncodedFrame {
        final long   seq;
        final long   tsMs;
        final byte[] data;
        final int    len;
        final int    nalType;

        EncodedFrame(long seq, long tsMs, byte[] data, int len, int nalType) {
            this.seq = seq;
            this.tsMs = tsMs;
            this.data = data;
            this.len = len;
            this.nalType = nalType;
        }
    }

    private static final class AccessUnitAccumulator {
        long tsMs = -1;
        int  len = 0;
        int  lastNalType = -1;
        boolean hasIdr = false;
        boolean hasSps = false;
        boolean hasPps = false;
        boolean hasSlice = false;

        void reset() {
            tsMs = -1;
            len = 0;
            lastNalType = -1;
            hasIdr = false;
            hasSps = false;
            hasPps = false;
            hasSlice = false;
        }
    }

    public H264Transport(StatusUpdater status, Surface surface, String sizeHint, boolean useReorder) {
        this.status   = status;
        this.surface  = surface;
        this.sizeHint = sizeHint;
        this.useReorder = useReorder;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        status.set("H264: listening on :" + PORT);

        ServerSocket server = bind();
        if (server == null) {
            status.set("H264: bind failed");
            return;
        }

        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                try (Socket client = server.accept()) {
                    if (surface == null) {
                        status.set("H264: no surface");
                        continue;
                    }
                    client.setTcpNoDelay(true);
                    client.setReceiveBufferSize(1 * 1024 * 1024);
                    // Keep socket blocking; portal/source stalls should not force reconnect storms.
                    client.setSoTimeout(0);
                    status.set("H264: client connected");
                    decodeStream(new BufferedInputStream(client.getInputStream(), 512 * 1024));
                } catch (IOException e) {
                    if (running) status.set("H264: connection lost (" + describeError(e) + ")");
                }
                sleep(150);
            }
        } catch (Exception e) {
            if (running) status.set("H264: error " + describeError(e));
        } finally {
            try { server.close(); } catch (IOException ignore) {}
        }
    }

    private ServerSocket bind() {
        for (int i = 0; i < 70 && running; i++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.setReceiveBufferSize(1 * 1024 * 1024);
                ss.bind(new InetSocketAddress(PORT));
                return ss;
            } catch (IOException e) {
                status.set("H264: port busy (" + (70 - i) + "s) " + describeError(e));
                sleep(1000);
            }
        }
        return null;
    }

    private void decodeStream(final BufferedInputStream in) throws IOException {
        if (useReorder) {
            decodeStreamReorder(in);
            return;
        }
        decodeStreamDirect(in);
    }

    // Direct decode path (no jitter buffer) - lowest latency and best for weak API17 devices.
    private void decodeStreamDirect(final BufferedInputStream in) throws IOException {
        MediaCodec codec = null;
        byte[] header = new byte[24]; // WBH1 header
        byte[] nalBuf = new byte[MAX_NAL];
        byte[] auBuf = new byte[MAX_AU];

        try {
            codec = MediaCodec.createDecoderByType("video/avc");
            if (codec == null) {
                status.set("H264: no decoder");
                return;
            }

            boolean configured = false;
            boolean sawSps = false;
            boolean sawPps = false;
            byte[] sps = null;
            byte[] pps = null;
            long queuedFrames = 0;
            long[] presentedFrames = new long[]{0};
            long dropCount = 0;
            long statStart = System.currentTimeMillis();
            long expectedSeq = -1;
            boolean waitingForIdr = false;
            AccessUnitAccumulator au = new AccessUnitAccumulator();
            long queuedSinceOutput = 0;
            long lastOutputWallMs = statStart;
            long lastDecoderRestartWallMs = 0;
            long restartCount = 0;
            int restartsInWindow = 0;
            long restartWindowStartMs = statStart;

            while (running && !Thread.currentThread().isInterrupted()) {
                IoUtils.readFully(in, header, 24);
                if (!(header[0] == 'W' && header[1] == 'B' && header[2] == 'H' && header[3] == '1')) {
                    throw new IOException("bad magic");
                }
                long seq = u64be(header, 4);
                long tsMs = u64be(header, 12);
                int nalLen = IoUtils.u32be(header, 20);
                if (nalLen <= 0 || nalLen > MAX_NAL) throw new IOException("bad len " + nalLen);
                IoUtils.readFully(in, nalBuf, nalLen);

                if (expectedSeq < 0) {
                    expectedSeq = seq;
                }
                if (seq < expectedSeq) {
                    // Late/duplicate unit - stale by sequence, skip quickly.
                    dropCount++;
                    continue;
                }
                if (seq > expectedSeq) {
                    // Sequence jump indicates missing units; track as drops.
                    dropCount += (seq - expectedSeq);
                    // We lost part of the decode chain; wait for next IDR to re-sync cleanly.
                    waitingForIdr = true;
                    au.reset();
                }
                expectedSeq = seq + 1;

                int nalHdrOff = findNalHeaderOffset(nalBuf, nalLen);
                int nalType = (nalHdrOff >= 0 && nalHdrOff < nalLen) ? (nalBuf[nalHdrOff] & 0x1F) : -1;
                if (nalType == NAL_SPS && nalHdrOff >= 0) { sawSps = true; sps = Arrays.copyOfRange(nalBuf, nalHdrOff, nalLen); } // SPS
                if (nalType == NAL_PPS && nalHdrOff >= 0) { sawPps = true; pps = Arrays.copyOfRange(nalBuf, nalHdrOff, nalLen); } // PPS

                if (!configured && sawSps && sawPps) {
                    configured = configureWithCsd(codec, sps, pps);
                    if (configured) {
                        status.set("H264: configured");
                        queueConfig(codec, sps, true);
                        queueConfig(codec, pps, false);
                    } else {
                        status.set("H264: configure fail");
                        continue;
                    }
                }

                // Stamp + sequence based frame assembly:
                // host may split one encoded frame into many WBH1 NAL units.
                // We batch same-ts units into one codec input buffer (1 queue per frame).
                if (au.tsMs >= 0 && tsMs != au.tsMs) {
                    int flush = flushAccessUnit(codec, auBuf, au, configured, waitingForIdr, presentedFrames);
                    if (flush == FLUSH_QUEUED) {
                        queuedFrames++;
                        queuedSinceOutput++;
                        waitingForIdr = false;
                    } else if (flush == FLUSH_DROP_HARD) {
                        dropCount++;
                        waitingForIdr = true;
                    } else if (flush == FLUSH_DROP_SOFT) {
                        dropCount++;
                    }
                }

                // If timestamps repeat, detect likely new frame boundary by NAL type to avoid
                // merging two frames into one corrupted AU.
                if (isLikelyBoundaryWithinSameTimestamp(au, nalBuf, nalLen, nalHdrOff, nalType)) {
                    int flush = flushAccessUnit(codec, auBuf, au, configured, waitingForIdr, presentedFrames);
                    if (flush == FLUSH_QUEUED) {
                        queuedFrames++;
                        queuedSinceOutput++;
                        waitingForIdr = false;
                    } else if (flush == FLUSH_DROP_HARD) {
                        dropCount++;
                        waitingForIdr = true;
                    } else if (flush == FLUSH_DROP_SOFT) {
                        dropCount++;
                    }
                }

                if (au.tsMs < 0 || tsMs != au.tsMs) {
                    au.tsMs = tsMs;
                }

                if (nalLen > (auBuf.length - au.len)) {
                    // AU overflow: flush what we have and start fresh to avoid unbounded memory.
                    int flush = flushAccessUnit(codec, auBuf, au, configured, waitingForIdr, presentedFrames);
                    if (flush == FLUSH_QUEUED) {
                        queuedFrames++;
                        queuedSinceOutput++;
                        waitingForIdr = false;
                    } else if (flush == FLUSH_DROP_HARD) {
                        dropCount++;
                        waitingForIdr = true;
                    } else if (flush == FLUSH_DROP_SOFT) {
                        dropCount++;
                    }
                    if (nalLen > auBuf.length) {
                        dropCount++;
                        continue;
                    }
                    au.tsMs = tsMs;
                }

                System.arraycopy(nalBuf, 0, auBuf, au.len, nalLen);
                au.len += nalLen;
                au.lastNalType = nalType;
                if (nalType == NAL_IDR) au.hasIdr = true;
                if (nalType == NAL_SPS) au.hasSps = true;
                if (nalType == NAL_PPS) au.hasPps = true;
                if (nalType == NAL_NON_IDR || nalType == NAL_IDR) au.hasSlice = true;
                // MediaCodec output can be drained only after configure()+start().
                if (configured) {
                    int released = drainOutput(codec);
                    presentedFrames[0] += released;
                    if (released > 0) {
                        queuedSinceOutput = 0;
                        lastOutputWallMs = System.currentTimeMillis();
                    }
                }

                long now = System.currentTimeMillis();
                if (now - restartWindowStartMs >= DECODER_RESTART_WINDOW_MS) {
                    restartWindowStartMs = now;
                    restartsInWindow = 0;
                }
                if (configured
                        && sawSps
                        && sawPps
                        && queuedSinceOutput >= DECODER_STALL_MIN_QUEUED
                        && (now - lastOutputWallMs) >= DECODER_STALL_MS
                        && (now - lastDecoderRestartWallMs) >= DECODER_RESTART_COOLDOWN_MS
                        && restartsInWindow < DECODER_MAX_RESTARTS_PER_WINDOW) {
                    Log.w(TAG, "decoder stall detected: queuedSinceOutput=" + queuedSinceOutput
                            + " noOutputMs=" + (now - lastOutputWallMs));
                    status.set("H264: decoder stall, restarting");
                    MediaCodec restarted = recreateDecoder(codec, sps, pps);
                    if (restarted != null) {
                        codec = restarted;
                        configured = true;
                        waitingForIdr = true;
                        au.reset();
                        queuedSinceOutput = 0;
                        lastOutputWallMs = now;
                        lastDecoderRestartWallMs = now;
                        restartCount++;
                        restartsInWindow++;
                    } else {
                        configured = false;
                        waitingForIdr = true;
                        au.reset();
                        lastDecoderRestartWallMs = now;
                        restartsInWindow++;
                    }
                }
                if (configured
                        && sawSps
                        && sawPps
                        && queuedSinceOutput >= DECODER_STALL_MIN_QUEUED
                        && (now - lastOutputWallMs) >= DECODER_STALL_MS
                        && restartsInWindow >= DECODER_MAX_RESTARTS_PER_WINDOW) {
                    waitingForIdr = true;
                    au.reset();
                    status.set("H264: decoder stalled, waiting for keyframe");
                }
                if (now - statStart >= STAT_INTERVAL_MS) {
                    double outFps = presentedFrames[0] * 1000.0 / (now - statStart);
                    double inFps = queuedFrames * 1000.0 / (now - statStart);
                    status.set(String.format(Locale.US, "H264 out=%.1f in=%.1f drop=%d rst=%d", outFps, inFps, dropCount, restartCount));
                    queuedFrames = 0;
                    presentedFrames[0] = 0;
                    dropCount = 0;
                    statStart = now;
                }
            }

            int flush = flushAccessUnit(codec, auBuf, au, configured, waitingForIdr, presentedFrames);
            if (flush == FLUSH_QUEUED) {
                queuedFrames++;
                queuedSinceOutput++;
            } else if (flush == FLUSH_DROP_HARD || flush == FLUSH_DROP_SOFT) {
                dropCount++;
            }
            if (configured) {
                presentedFrames[0] += drainOutput(codec);
            }
        } finally {
            releaseCodec(codec);
        }
    }

    // Optional jitter-buffer path for out-of-order mitigation (higher CPU/latency).
    private void decodeStreamReorder(final BufferedInputStream in) throws IOException {
        MediaCodec codec = null;
        final Object queueLock = new Object();
        final PriorityQueue<EncodedFrame> reorder = new PriorityQueue<>(
            MAX_REORDER_FRAMES + 2,
            new Comparator<EncodedFrame>() {
                @Override
                public int compare(EncodedFrame a, EncodedFrame b) {
                    if (a.seq < b.seq) return -1;
                    if (a.seq > b.seq) return 1;
                    if (a.tsMs < b.tsMs) return -1;
                    if (a.tsMs > b.tsMs) return 1;
                    return 0;
                }
            });
        final boolean[] rxDone = new boolean[]{false};
        final IOException[] rxErr = new IOException[1];
        final long[] reorderDrops = new long[]{0};

        Thread rxThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] header = new byte[24]; // WBH1 header
                byte[] nalBuf = new byte[MAX_NAL];

                try {
                    while (running && !Thread.currentThread().isInterrupted()) {
                        IoUtils.readFully(in, header, 24);
                        if (!(header[0] == 'W' && header[1] == 'B' && header[2] == 'H' && header[3] == '1')) {
                            throw new IOException("bad magic");
                        }
                        long seq = u64be(header, 4);
                        long tsMs = u64be(header, 12);
                        int nalLen = IoUtils.u32be(header, 20);
                        if (nalLen <= 0 || nalLen > MAX_NAL) throw new IOException("bad len " + nalLen);
                        IoUtils.readFully(in, nalBuf, nalLen);

                        int nalHdrOff = findNalHeaderOffset(nalBuf, nalLen);
                        int nalType = (nalHdrOff >= 0 && nalHdrOff < nalLen) ? (nalBuf[nalHdrOff] & 0x1F) : -1;
                        byte[] payload = Arrays.copyOf(nalBuf, nalLen);

                        synchronized (queueLock) {
                            if (reorder.size() >= MAX_REORDER_FRAMES) {
                                reorder.poll();
                                reorderDrops[0]++;
                            }
                            reorder.offer(new EncodedFrame(seq, tsMs, payload, nalLen, nalType));
                            queueLock.notifyAll();
                        }
                    }
                } catch (IOException e) {
                    rxErr[0] = e;
                } finally {
                    synchronized (queueLock) {
                        rxDone[0] = true;
                        queueLock.notifyAll();
                    }
                }
            }
        }, "wbeam-h264-rx");
        rxThread.start();

        try {
            codec = MediaCodec.createDecoderByType("video/avc");
            if (codec == null) {
                status.set("H264: no decoder");
                return;
            }

            boolean configured = false;
            boolean sawSps = false;
            boolean sawPps = false;
            byte[] sps = null;
            byte[] pps = null;
            long nextSeq = -1;
            long queuedFrames = 0;
            long presentedFrames = 0;
            long statStart = System.currentTimeMillis();

            while (running && !Thread.currentThread().isInterrupted()) {
                EncodedFrame frame = null;
                synchronized (queueLock) {
                    while (running && reorder.isEmpty() && !rxDone[0]) {
                        waitNoThrow(queueLock, REORDER_WAIT_MS);
                    }
                    if (!reorder.isEmpty()) {
                        EncodedFrame head = reorder.peek();
                        if (nextSeq < 0) nextSeq = head.seq;
                        if (head.seq == nextSeq || reorder.size() >= MAX_REORDER_FRAMES || rxDone[0]) {
                            frame = reorder.poll();
                            if (frame != null) {
                                if (frame.seq > nextSeq) {
                                    reorderDrops[0] += (frame.seq - nextSeq);
                                }
                                nextSeq = frame.seq + 1;
                            }
                        }
                    }
                }

                if (frame == null) {
                    if (rxDone[0]) break;
                    continue;
                }

                int nalHdrOff = findNalHeaderOffset(frame.data, frame.len);
                int nalType = frame.nalType;
                if (nalType == 7 && nalHdrOff >= 0) { sawSps = true; sps = Arrays.copyOfRange(frame.data, nalHdrOff, frame.len); } // SPS
                if (nalType == 8 && nalHdrOff >= 0) { sawPps = true; pps = Arrays.copyOfRange(frame.data, nalHdrOff, frame.len); } // PPS

                if (!configured && sawSps && sawPps) {
                    configured = configureWithCsd(codec, sps, pps);
                    if (configured) {
                        status.set("H264: configured");
                        queueConfig(codec, sps, true);
                        queueConfig(codec, pps, false);
                    } else {
                        status.set("H264: configure fail");
                        continue;
                    }
                }

                if (!configured) continue; // wait for SPS/PPS

                int idx = codec.dequeueInputBuffer(10_000);
                if (idx >= 0) {
                    ByteBuffer buf = inBuf(codec, idx);
                    if (buf != null) {
                        buf.clear();
                        buf.put(frame.data, 0, frame.len);
                        int flags = (nalType == 5) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0; // IDR=5
                        codec.queueInputBuffer(idx, 0, frame.len, frame.tsMs * 1000L, flags);
                        queuedFrames++;
                    }
                }
                presentedFrames += drainOutput(codec);
                long now = System.currentTimeMillis();
                if (now - statStart >= STAT_INTERVAL_MS) {
                    double outFps = presentedFrames * 1000.0 / (now - statStart);
                    double inFps = queuedFrames * 1000.0 / (now - statStart);
                    long drops;
                    synchronized (queueLock) {
                        drops = reorderDrops[0];
                        reorderDrops[0] = 0;
                    }
                    status.set(String.format(Locale.US, "H264 out=%.1f in=%.1f drop=%d", outFps, inFps, drops));
                    queuedFrames = 0;
                    presentedFrames = 0;
                    statStart = now;
                }
            }

            if (rxErr[0] != null) {
                String msg = rxErr[0].getMessage();
                if (msg != null && !msg.contains("EOF")) {
                    throw rxErr[0];
                }
            }
        } finally {
            try {
                rxThread.interrupt();
                rxThread.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignore) {}
                try { codec.release(); } catch (Exception ignore) {}
            }
        }
    }

    private boolean configureWithCsd(MediaCodec codec, byte[] sps, byte[] pps) {
        try {
            int width = 1280;
            int height = 720;
            if (sizeHint != null && sizeHint.contains("x")) {
                String[] parts = sizeHint.toLowerCase().split("x");
                if (parts.length == 2) {
                    width = Integer.parseInt(parts[0]);
                    height = Integer.parseInt(parts[1]);
                }
            }

            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", width, height);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_NAL);
            // Decoder -> Surface path: forcing KEY_COLOR_FORMAT reduces compatibility on some
            // legacy devices. Let the codec pick the native output format.

            if (sps != null) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            if (pps != null) fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps));

            codec.configure(fmt, surface, null, 0);
            codec.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "configureWithCsd failed", e);
            status.set("H264: configure fail " + describeError(e));
            return false;
        }
    }

    private void queueConfig(MediaCodec codec, byte[] data, boolean sps) {
        if (data == null) return;
        try {
            int idx = codec.dequeueInputBuffer(5_000);
            if (idx < 0) return;
            ByteBuffer buf = inBuf(codec, idx);
            if (buf == null) return;
            buf.clear();
            buf.put(data, 0, data.length);
            int flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
            codec.queueInputBuffer(idx, 0, data.length, 0, flags);
        } catch (IllegalStateException e) {
            Log.w(TAG, "queueConfig: codec not ready (" + (sps ? "sps" : "pps") + ")");
        }
    }

    private static ByteBuffer inBuf(MediaCodec codec, int idx) {
        // API17-compatible path: avoids verifier warnings about API21-only methods.
        return codec.getInputBuffers()[idx];
    }

    private static int drainOutput(MediaCodec codec) {
        if (codec == null) return 0;
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIdx;
            int released = 0;
            while ((outIdx = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                codec.releaseOutputBuffer(outIdx, true);
                released++;
            }
            return released;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    private static boolean isLikelyBoundaryWithinSameTimestamp(
            AccessUnitAccumulator au,
            byte[] incomingNal,
            int incomingNalLen,
            int incomingNalHdrOff,
            int incomingNalType) {
        if (au == null || au.len <= 0) return false;
        // AUD usually starts a new access unit. Also SPS/PPS after slices indicates
        // next keyframe sequence, so flush previous frame first.
        if (incomingNalType == NAL_AUD && au.hasSlice) return true;
        if ((incomingNalType == NAL_SPS || incomingNalType == NAL_PPS) && au.hasSlice) return true;
        // For VCL slices, first_mb_in_slice == 0 means a new picture starts.
        // This prevents merging consecutive frames when timestamp granularity is too coarse.
        if (au.hasSlice && isSliceNalType(incomingNalType)) {
            int firstMb = parseFirstMbInSlice(incomingNal, incomingNalLen, incomingNalHdrOff);
            if (firstMb == 0) return true;
        }
        return false;
    }

    // Returns: 1 queued, 0 nothing to flush, -1 dropped.
    private static int flushAccessUnit(
            MediaCodec codec,
            byte[] auBuf,
            AccessUnitAccumulator au,
            boolean configured,
            boolean waitingForIdr,
            long[] presentedFrames) {
        if (au == null || au.len <= 0) {
            if (au != null) au.reset();
            return FLUSH_NOTHING;
        }

        boolean hasRecoveryPoint = au.hasIdr || (au.hasSps && au.hasPps);
        if (!configured) {
            au.reset();
            return FLUSH_DROP_SOFT;
        }
        if (waitingForIdr && !hasRecoveryPoint) {
            au.reset();
            return FLUSH_DROP_HARD;
        }

        int queueResult = queueAccessUnit(codec, auBuf, au.len, au.tsMs, au.lastNalType, presentedFrames);
        au.reset();
        if (queueResult == QUEUE_OK) return FLUSH_QUEUED;
        if (queueResult == QUEUE_BUSY) return FLUSH_DROP_SOFT;
        return FLUSH_DROP_HARD;
    }

    private static int queueAccessUnit(
            MediaCodec codec,
            byte[] auBuf,
            int auLen,
            long tsMs,
            int nalType,
            long[] presentedFrames) {
        if (codec == null || auBuf == null || auLen <= 0) return QUEUE_ERROR;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                int timeoutUs = (attempt == 0) ? 0 : 4_000;
                int idx = codec.dequeueInputBuffer(timeoutUs);
                if (idx < 0) {
                    presentedFrames[0] += drainOutput(codec);
                    continue;
                }
                ByteBuffer buf = inBuf(codec, idx);
                if (buf == null) {
                    return QUEUE_ERROR;
                }
                buf.clear();
                buf.put(auBuf, 0, auLen);
                int flags = (nalType == NAL_IDR) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0; // IDR=5
                codec.queueInputBuffer(idx, 0, auLen, tsMs * 1000L, flags);
                presentedFrames[0] += drainOutput(codec);
                return QUEUE_OK;
            }
            return QUEUE_BUSY;
        } catch (Exception e) {
            Log.w(TAG, "queueAccessUnit failed", e);
            return QUEUE_ERROR;
        }
    }

    private MediaCodec recreateDecoder(MediaCodec current, byte[] sps, byte[] pps) {
        releaseCodec(current);
        try {
            MediaCodec fresh = MediaCodec.createDecoderByType("video/avc");
            if (fresh == null) {
                return null;
            }
            if (!configureWithCsd(fresh, sps, pps)) {
                releaseCodec(fresh);
                return null;
            }
            queueConfig(fresh, sps, true);
            queueConfig(fresh, pps, false);
            return fresh;
        } catch (Exception e) {
            Log.w(TAG, "recreateDecoder failed", e);
            return null;
        }
    }

    private static void releaseCodec(MediaCodec codec) {
        if (codec == null) return;
        try { codec.stop(); } catch (Exception ignore) {}
        try { codec.release(); } catch (Exception ignore) {}
    }

    private static boolean isSliceNalType(int nalType) {
        return nalType == 1 || nalType == 2 || nalType == 5;
    }

    private static int parseFirstMbInSlice(byte[] nal, int nalLen, int nalHdrOff) {
        if (nal == null || nalLen <= 0 || nalHdrOff < 0 || nalHdrOff >= nalLen) return -1;
        int payloadOff = nalHdrOff + 1;
        if (payloadOff >= nalLen) return -1;
        RbspBitReader br = new RbspBitReader(nal, payloadOff, nalLen);
        return br.readUE();
    }

    private static final class RbspBitReader {
        private final byte[] src;
        private final int end;
        private int idx;
        private int zeros = 0;
        private int cur = 0;
        private int bitsLeft = 0;

        RbspBitReader(byte[] src, int off, int end) {
            this.src = src;
            this.idx = off;
            this.end = end;
        }

        int readUE() {
            int leadingZeros = 0;
            while (true) {
                int bit = readBit();
                if (bit < 0) return -1;
                if (bit == 0) {
                    leadingZeros++;
                    if (leadingZeros > 31) return -1;
                } else {
                    break;
                }
            }

            int codeNum = 1;
            for (int i = 0; i < leadingZeros; i++) {
                int bit = readBit();
                if (bit < 0) return -1;
                codeNum = (codeNum << 1) | bit;
            }
            return codeNum - 1;
        }

        private int readBit() {
            if (bitsLeft == 0) {
                int next = nextRbspByte();
                if (next < 0) return -1;
                cur = next;
                bitsLeft = 8;
            }
            bitsLeft--;
            return (cur >> bitsLeft) & 1;
        }

        private int nextRbspByte() {
            while (idx < end) {
                int b = src[idx++] & 0xFF;
                if (zeros >= 2 && b == 0x03) {
                    // Skip emulation prevention byte.
                    zeros = 0;
                    continue;
                }
                if (b == 0) {
                    zeros++;
                } else {
                    zeros = 0;
                }
                return b;
            }
            return -1;
        }
    }

    private static int findNalHeaderOffset(byte[] buf, int len) {
        if (buf == null || len <= 0) return -1;
        // Annex B (00 00 01 or 00 00 00 01)
        for (int i = 0; i + 4 < len; i++) {
            if (buf[i] == 0 && buf[i + 1] == 0) {
                if (buf[i + 2] == 1) return i + 3;
                if (buf[i + 2] == 0 && buf[i + 3] == 1) return i + 4;
            }
        }
        // Fallback: assume buffer begins at NAL header.
        return 0;
    }

    private static long u64be(byte[] src, int off) {
        return ((long) (src[off]     & 0xFF) << 56)
            | ((long) (src[off + 1] & 0xFF) << 48)
            | ((long) (src[off + 2] & 0xFF) << 40)
            | ((long) (src[off + 3] & 0xFF) << 32)
            | ((long) (src[off + 4] & 0xFF) << 24)
            | ((long) (src[off + 5] & 0xFF) << 16)
            | ((long) (src[off + 6] & 0xFF) << 8)
            | ((long) (src[off + 7] & 0xFF));
    }

    private static void waitNoThrow(Object lock, long ms) {
        try {
            lock.wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describeError(Throwable err) {
        if (err == null) return "unknown";
        String msg = err.getMessage();
        if (msg != null) {
            msg = msg.trim();
            if (!msg.isEmpty()) return msg;
        }
        String name = err.getClass().getSimpleName();
        return name.isEmpty() ? "unknown" : name;
    }
}
