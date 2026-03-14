package com.wbeam.stream;

import android.media.MediaCodec;
import android.os.SystemClock;
import android.util.Log;

import com.wbeam.ClientMetricsSample;
import com.wbeam.api.StatusListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class LegacyAnnexBDecodeLoop {

    interface RuntimeState {
        boolean isRunning();
        long getDroppedTotal();
        void addDroppedTotal(long delta);
        long getTooLateTotal();
        void addTooLateTotal(long delta);
        long getReconnects();
        long getSessionConnectId();
        long nextSampleSeq();
        void resetReconnectDelayMs();
    }

    private final String tag;
    private final StatusListener statusListener;
    private final RuntimeState runtimeState;
    private final long frameUs;
    private final int decodeQueueMaxFrames;
    private final int renderQueueMaxFrames;
    private final String stateStreaming;

    LegacyAnnexBDecodeLoop(
            String tag,
            StatusListener statusListener,
            RuntimeState runtimeState,
            long frameUs,
            int decodeQueueMaxFrames,
            int renderQueueMaxFrames,
            String stateStreaming
    ) {
        this.tag = tag;
        this.statusListener = statusListener;
        this.runtimeState = runtimeState;
        this.frameUs = frameUs;
        this.decodeQueueMaxFrames = decodeQueueMaxFrames;
        this.renderQueueMaxFrames = renderQueueMaxFrames;
        this.stateStreaming = stateStreaming;
    }

    void run(InputStream input, MediaCodec codec) throws IOException {
        LegacyDecodeState state = new LegacyDecodeState();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        MediaCodecBridge.DrainStats drainStats = new MediaCodecBridge.DrainStats();

        while (runtimeState.isRunning()) {
            int count = readChunk(input, state.readBuf);
            if (count > 0) {
                appendToStreamBuffer(state, count);
                detectStreamMode(state);
                processAvailableNals(codec, state, bufferInfo, drainStats);
                updatePresentationWatchdog(state, drainStats);
                emitPeriodicStats(state);
            }
        }
    }

    private int readChunk(InputStream input, byte[] readBuf) throws IOException {
        int count = input.read(readBuf);
        if (count < 0) {
            throw new IOException("stream closed");
        }
        return count;
    }

    private void appendToStreamBuffer(LegacyDecodeState state, int count) {
        int availableBytes = state.sTail - state.sHead;
        if (state.sTail + count > state.streamBuf.length) {
            if (availableBytes + count > state.streamBuf.length) {
                int keep = state.streamBuf.length - count;
                if (keep <= 0) {
                    state.sHead = 0;
                    state.sTail = 0;
                } else {
                    int newHead = state.sHead + (availableBytes - keep);
                    System.arraycopy(state.streamBuf, newHead, state.streamBuf, 0, keep);
                    state.sHead = 0;
                    state.sTail = keep;
                }
                state.droppedSec++;
            } else {
                if (availableBytes > 0) {
                    System.arraycopy(state.streamBuf, state.sHead, state.streamBuf, 0, availableBytes);
                }
                state.sHead = 0;
                state.sTail = availableBytes;
            }
        }
        System.arraycopy(state.readBuf, 0, state.streamBuf, state.sTail, count);
        state.sTail += count;
        state.bytes += count;
    }

    private void detectStreamMode(LegacyDecodeState state) {
        int availableBytes = state.sTail - state.sHead;
        if (state.streamMode < 0 && availableBytes >= 8) {
            int probe = StreamNalUtils.findStartCode(
                    state.streamBuf,
                    state.sHead,
                    Math.min(state.sHead + 128, state.sTail)
            );
            state.streamMode = (probe >= 0) ? 0 : 1;
        }
    }

    private void processAvailableNals(
            MediaCodec codec,
            LegacyDecodeState state,
            MediaCodec.BufferInfo bufferInfo,
            MediaCodecBridge.DrainStats drainStats
    ) {
        if (state.streamMode == 1) {
            processLengthPrefixedNals(codec, state, bufferInfo, drainStats);
            return;
        }
        processAnnexBNals(codec, state, bufferInfo, drainStats);
    }

    private void processLengthPrefixedNals(
            MediaCodec codec,
            LegacyDecodeState state,
            MediaCodec.BufferInfo bufferInfo,
            MediaCodecBridge.DrainStats drainStats
    ) {
        while ((state.sTail - state.sHead) >= 4) {
            int nalSize = ((state.streamBuf[state.sHead] & 0xFF) << 24)
                    | ((state.streamBuf[state.sHead + 1] & 0xFF) << 16)
                    | ((state.streamBuf[state.sHead + 2] & 0xFF) << 8)
                    | (state.streamBuf[state.sHead + 3] & 0xFF);
            if (nalSize <= 0 || nalSize > state.streamBuf.length) {
                state.sHead++;
                state.droppedSec++;
            } else if ((state.sTail - state.sHead) < 4 + nalSize) {
                return;
            } else {
                processNal(codec, state, bufferInfo, drainStats, state.sHead + 4, nalSize);
                state.sHead += 4 + nalSize;
            }
        }
    }

    private void processAnnexBNals(
            MediaCodec codec,
            LegacyDecodeState state,
            MediaCodec.BufferInfo bufferInfo,
            MediaCodecBridge.DrainStats drainStats
    ) {
        int nalStart = StreamNalUtils.findStartCode(state.streamBuf, state.sHead, state.sTail);
        if (nalStart < 0) {
            state.sHead = Math.max(state.sHead, state.sTail - 3);
            return;
        }

        state.sHead = nalStart;
        int next;
        while ((next = StreamNalUtils.findStartCode(state.streamBuf, state.sHead + 3, state.sTail)) >= 0) {
            int nalSize = next - state.sHead;
            if (nalSize > 0) {
                processNal(codec, state, bufferInfo, drainStats, state.sHead, nalSize);
            }
            state.sHead = next;
        }
    }

    private void processNal(
            MediaCodec codec,
            LegacyDecodeState state,
            MediaCodec.BufferInfo bufferInfo,
            MediaCodecBridge.DrainStats drainStats,
            int nalOffset,
            int nalSize
    ) {
        state.frames++;
        drainCodec(codec, state, bufferInfo, drainStats);
        if (canQueueNal(state, nalOffset, nalSize)) {
            queueNal(codec, state, nalOffset, nalSize);
        } else {
            state.droppedSec++;
        }
    }

    private void drainCodec(
            MediaCodec codec,
            LegacyDecodeState state,
            MediaCodec.BufferInfo bufferInfo,
            MediaCodecBridge.DrainStats drainStats
    ) {
        MediaCodecBridge.drainLatestFrame(
                codec,
                bufferInfo,
                drainStats,
                true,
                state.pendingDecodeQueue >= decodeQueueMaxFrames ? 16_000 : 5_000
        );
        long nowMs = SystemClock.elapsedRealtime();
        state.pendingDecodeQueue = Math.max(0, state.pendingDecodeQueue - drainStats.releasedCount);
        if (drainStats.releasedCount > 0) {
            state.lastDecodeProgressMs = nowMs;
        } else if (state.pendingDecodeQueue >= decodeQueueMaxFrames
                && (nowMs - state.lastDecodeProgressMs) > 300) {
            state.pendingDecodeQueue = decodeQueueMaxFrames - 1;
        }
        state.outFrames += drainStats.renderedCount;
        state.tooLateSec += drainStats.droppedLateCount;
        state.renderNsMax = Math.max(state.renderNsMax, drainStats.renderNsMax);
        state.renderQueueDepth = drainStats.renderedCount > 0 ? 1 : 0;
    }

    private boolean canQueueNal(LegacyDecodeState state, int nalOffset, int nalSize) {
        return state.pendingDecodeQueue < decodeQueueMaxFrames
                || StreamNalUtils.isRecoveryNal(state.streamBuf, nalOffset, nalSize);
    }

    private void queueNal(MediaCodec codec, LegacyDecodeState state, int nalOffset, int nalSize) {
        long queueStartNs = SystemClock.elapsedRealtimeNanos();
        if (MediaCodecBridge.queueNal(codec, state.streamBuf, nalOffset, nalSize, state.frames * frameUs, 1_000)) {
            long decodeDurationNs = SystemClock.elapsedRealtimeNanos() - queueStartNs;
            state.decodeNsTotal += decodeDurationNs;
            state.decodeNsBuf[(state.decodeNsBufN++) & 127] = decodeDurationNs;
            state.avgNalSize = ((state.avgNalSize * 7) + nalSize) / 8;
            state.inFrames++;
            state.pendingDecodeQueue = Math.min(decodeQueueMaxFrames, state.pendingDecodeQueue + 1);
            state.lastDecodeProgressMs = SystemClock.elapsedRealtime();
        } else {
            state.droppedSec++;
        }
    }

    private void updatePresentationWatchdog(LegacyDecodeState state, MediaCodecBridge.DrainStats drainStats) throws IOException {
        if (drainStats.renderedCount > 0) {
            state.lastPresentMs = SystemClock.elapsedRealtime();
            state.pendingWithNoPresent = 0;
        } else if (state.inFrames > 0) {
            state.pendingWithNoPresent++;
        }
        if (drainStats.renderedCount > 0 && drainStats.lastRenderedPtsUs > 0) {
            state.lastPresentedPtsUs = drainStats.lastRenderedPtsUs;
        }
        if (state.pendingWithNoPresent > 300 && (SystemClock.elapsedRealtime() - state.lastPresentMs) > 5_000) {
            throw new IOException("C5: black-screen watchdog: 0 frames presented in 5s with "
                    + state.pendingWithNoPresent + " decoded – reconnecting");
        }
    }

    private void emitPeriodicStats(LegacyDecodeState state) {
        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - state.lastLog < 1000) {
            return;
        }

        runtimeState.addDroppedTotal(state.droppedSec);
        runtimeState.addTooLateTotal(state.tooLateSec);
        runtimeState.resetReconnectDelayMs();
        statusListener.onStatus(stateStreaming, "rendering live desktop", state.bytes);
        statusListener.onStats(
                "fps in/out: " + state.inFrames + "/" + state.outFrames
                        + " | drops: " + runtimeState.getDroppedTotal()
                        + " | late: " + runtimeState.getTooLateTotal()
                        + " | q(t/d/r): "
                        + StreamBufferMath.estimateTransportDepthFrames(state.sTail - state.sHead, state.avgNalSize) + "/"
                        + Math.min(decodeQueueMaxFrames, state.pendingDecodeQueue) + "/"
                        + state.renderQueueDepth
                        + " | reconnects: " + runtimeState.getReconnects()
        );
        double decodeMsP50 = state.inFrames > 0 ? (state.decodeNsTotal / 1_000_000.0) / state.inFrames : 0.0;
        double decodeMsP95 = StreamBufferMath.percentileMs(
                state.decodeNsBuf,
                Math.min(state.decodeNsBufN, 128),
                0.95,
                state.decodeNsScratch
        );
        double renderMsP95 = state.renderNsMax / 1_000_000.0;
        double e2eMs = StreamBufferMath.estimateE2eLatencyMs(state.lastPresentedPtsUs);
        statusListener.onClientMetrics(new ClientMetricsSample(
                state.inFrames, state.inFrames, state.outFrames, state.bytes,
                decodeMsP50, decodeMsP95, renderMsP95, e2eMs, e2eMs,
                StreamBufferMath.estimateTransportDepthFrames(state.sTail - state.sHead, state.avgNalSize),
                Math.min(decodeQueueMaxFrames, state.pendingDecodeQueue),
                Math.min(renderQueueMaxFrames, state.renderQueueDepth),
                0, runtimeState.getDroppedTotal(), runtimeState.getTooLateTotal(),
                (runtimeState.getSessionConnectId() << 32) | (runtimeState.nextSampleSeq() & 0xFFFFFFFFL)
        ));
        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, String.format(Locale.US,
                    "[decode/legacy] in=%d out=%d drop=%d late=%d qD=%d/%d qR=%d/%d"
                            + " dec_p95=%.1fms ren_p95=%.1fms noPresent=%d reconn=%d",
                    state.inFrames, state.outFrames, state.droppedSec, state.tooLateSec,
                    Math.min(decodeQueueMaxFrames, state.pendingDecodeQueue), decodeQueueMaxFrames,
                    state.renderQueueDepth, renderQueueMaxFrames,
                    decodeMsP95, renderMsP95, state.pendingWithNoPresent, runtimeState.getReconnects()));
        }
        state.resetInterval(nowMs);
    }

    private static final class LegacyDecodeState {
        final byte[] readBuf = new byte[64 * 1024];
        final byte[] streamBuf = new byte[512 * 1024];
        final long[] decodeNsBuf = new long[128];
        final long[] decodeNsScratch = new long[128];
        int sHead;
        int sTail;
        int streamMode = -1;
        int avgNalSize = 1200;
        int pendingDecodeQueue;
        int renderQueueDepth;
        long frames;
        long bytes;
        long inFrames;
        long outFrames;
        long droppedSec;
        long tooLateSec;
        long decodeNsTotal;
        int decodeNsBufN;
        long renderNsMax;
        long lastLog = SystemClock.elapsedRealtime();
        long lastPresentMs = lastLog;
        long lastPresentedPtsUs = -1L;
        long pendingWithNoPresent;
        long lastDecodeProgressMs = lastLog;

        void resetInterval(long nowMs) {
            bytes = 0L;
            inFrames = 0L;
            outFrames = 0L;
            droppedSec = 0L;
            tooLateSec = 0L;
            decodeNsTotal = 0L;
            decodeNsBufN = 0;
            renderNsMax = 0L;
            lastLog = nowMs;
        }
    }
}
