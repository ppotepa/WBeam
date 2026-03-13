package com.wbeam.stream;

import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.wbeam.ClientMetricsSample;
import com.wbeam.api.StatusListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class FramedPngLoop {

    interface RuntimeState {
        boolean isRunning();
        long getDroppedTotal();
        void addDroppedTotal(long delta);
        long getTooLateTotal();
        long getReconnects();
        long getSessionConnectId();
        long nextSampleSeq();
        void resetReconnectDelayMs();
    }

    private final String tag;
    private final Surface surface;
    private final StatusListener statusListener;
    private final RuntimeState runtimeState;
    private final long frameUs;
    private final int frameHeaderSize;
    private final int frameMagic;
    private final int frameResyncScanLimit;
    private final int frameFlagKeyframe;
    private final int framePayloadHardCap;
    private final long noPresentHardResetMs;
    private final String stateConnecting;
    private final String stateStreaming;
    private final int frameBufferBudgetFrames;

    @SuppressWarnings("java:java:S107")
    FramedPngLoop(
            String tag,
            Surface surface,
            StatusListener statusListener,
            RuntimeState runtimeState,
            long frameUs,
            int frameHeaderSize,
            int frameMagic,
            int frameResyncScanLimit,
            int frameFlagKeyframe,
            int framePayloadHardCap,
            long noPresentHardResetMs,
            String stateConnecting,
            String stateStreaming,
            int frameBufferBudgetFrames
    ) {
        this.tag = tag;
        this.surface = surface;
        this.statusListener = statusListener;
        this.runtimeState = runtimeState;
        this.frameUs = frameUs;
        this.frameHeaderSize = frameHeaderSize;
        this.frameMagic = frameMagic;
        this.frameResyncScanLimit = frameResyncScanLimit;
        this.frameFlagKeyframe = frameFlagKeyframe;
        this.framePayloadHardCap = framePayloadHardCap;
        this.noPresentHardResetMs = noPresentHardResetMs;
        this.stateConnecting = stateConnecting;
        this.stateStreaming = stateStreaming;
        this.frameBufferBudgetFrames = frameBufferBudgetFrames;
    }
 @SuppressWarnings("java:java:S6541")
 @SuppressWarnings("java:java:S3776")

    void run(InputStream input, byte[] hdrBuf, byte[] payloadBuf, boolean isUltraMode) throws IOException {
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
        final int seqGapBudget = StreamBufferMath.computeSeqGapBudget(frameUs, isUltraMode);
        final boolean dropBacklogFrames = isUltraMode;
        @SuppressWarnings("java:java:S135")
        final int backlogFrameBudget = frameBufferBudgetFrames;
        PngSurfaceRenderer pngRenderer = new PngSurfaceRenderer(surface);

        while (runtimeState.isRunning()) {
            WbtpProtocol.FrameHeader frameHeader = WbtpProtocol.readFrameHeader(
                    input,
                    hdrBuf,
                    frameHeaderSize,
                    frameMagic,
                    frameResyncScanLimit,
                    frameFlagKeyframe
            );
            if (frameHeader.resynced) {
                resyncSuccessSec++;
            }

            long seqU32 = frameHeader.seqU32;
            long ptsUs = frameHeader.ptsUs;

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

            int payloadLen = frameHeader.payloadLen;
            WbtpPayloadBuffer.validatePayloadLength(payloadLen, framePayloadHardCap);
            byte[] grownPayloadBuf = WbtpPayloadBuffer.ensureCapacity(
                    payloadBuf,
                    payloadLen,
                    framePayloadHardCap,
                    tag,
                    "WBTP PNG payload buffer grow "
            );
            if (grownPayloadBuf != payloadBuf) {
                payloadBuf = grownPayloadBuf;
                payloadGrowEvents++;
            }
            maxPayloadSeen = Math.max(maxPayloadSeen, payloadLen);

            WbtpFrameIo.readFully(input, payloadBuf, payloadLen);
            bytes += frameHeaderSize + payloadLen;

            int payloadEstimate = Math.max(8 * 1024, maxPayloadSeen + frameHeaderSize);
            int backlogBytes = Math.max(0, input.available());
            int backlogFramesEstimate = payloadEstimate > 0 ? (backlogBytes / payloadEstimate) : 0;
            int skippedBacklog = 0;

            while (dropBacklogFrames && backlogFramesEstimate > backlogFrameBudget) {
                WbtpProtocol.FrameHeader skippedFrameHeader = WbtpProtocol.readFrameHeader(
                        input,
                        hdrBuf,
                        frameHeaderSize,
                        frameMagic,
                        frameResyncScanLimit,
                        frameFlagKeyframe
                );
                if (skippedFrameHeader.resynced) {
                    resyncSuccessSec++;
                }

                seqU32 = skippedFrameHeader.seqU32;
                ptsUs = skippedFrameHeader.ptsUs;

                int nextLen = skippedFrameHeader.payloadLen;
                WbtpPayloadBuffer.validatePayloadLength(nextLen, framePayloadHardCap);
                byte[] grownBacklogPayloadBuf = WbtpPayloadBuffer.ensureCapacity(
                        payloadBuf,
                        nextLen,
                        framePayloadHardCap,
                        tag,
                        "WBTP PNG payload buffer grow "
                );
                if (grownBacklogPayloadBuf != payloadBuf) {
                    payloadBuf = grownBacklogPayloadBuf;
                    payloadGrowEvents++;
                }

                payloadLen = nextLen;
                maxPayloadSeen = Math.max(maxPayloadSeen, payloadLen);
                WbtpFrameIo.readFully(input, payloadBuf, payloadLen);
                bytes += frameHeaderSize + payloadLen;
                skippedBacklog++;

                payloadEstimate = Math.max(8 * 1024, maxPayloadSeen + frameHeaderSize);
                backlogBytes = Math.max(0, input.available());
                backlogFramesEstimate = payloadEstimate > 0 ? (backlogBytes / payloadEstimate) : 0;
            }

            if (skippedBacklog > 0) {
                droppedSec += skippedBacklog;
            }

            long t0 = SystemClock.elapsedRealtimeNanos();
            PngSurfaceRenderer.RenderResult renderResult = pngRenderer.render(payloadBuf, payloadLen);
            if (renderResult.rendered) {
                outFrames++;
                lastPresentMs = SystemClock.elapsedRealtime();
                lastQueuedPtsUs = ptsUs;
                expectedSeq = seqU32 + 1;
            } else {
                droppedSec++;
                expectedSeq = seqU32 + 1;
            }

            long dn = SystemClock.elapsedRealtimeNanos() - t0;
            decodeNsTotal += dn;
            decodeNsBuf[(decodeNsBufN++) & 127] = dn;
            inFrames++;

            long nowMs = SystemClock.elapsedRealtime();
            if (nowMs - lastPresentMs >= noPresentHardResetMs) {
                Log.w(tag, "PNG absolute guard: reconnect after 5s with no present");
                statusListener.onStatus(stateConnecting, "png stalled >5s: reconnecting", 0);
                throw new IOException("PNG absolute guard: no frame presented for " + (nowMs - lastPresentMs) + "ms");
            }
            if (nowMs - lastLog >= 1000) {
                runtimeState.addDroppedTotal(droppedSec);
                runtimeState.resetReconnectDelayMs();

                statusListener.onStatus(stateStreaming, "rendering live desktop [framed/png]", bytes);
                statusListener.onStats(
                        "fps in/out: " + inFrames + "/" + outFrames
                                + " | drops: " + runtimeState.getDroppedTotal()
                                + " | late: " + runtimeState.getTooLateTotal()
                                + " | q(d/r): " + Math.min(backlogFramesEstimate, backlogFrameBudget) + "/0"
                                + " | max_payload: " + (maxPayloadSeen / 1024) + "KB"
                                + " | reconnects: " + runtimeState.getReconnects()
                );

                double decodeMsP50 = inFrames > 0 ? (decodeNsTotal / 1_000_000.0) / inFrames : 0.0;
                double decodeMsP95 = StreamBufferMath.percentileMs(decodeNsBuf, Math.min(decodeNsBufN, 128), 0.95, decodeNsScratch);
                long nowEpochUs = System.currentTimeMillis() * 1000L;
                long transportLagUs = (lastQueuedPtsUs > 0 && nowEpochUs > lastQueuedPtsUs)
                        ? (nowEpochUs - lastQueuedPtsUs)
                        : 0L;
                int transportQueueDepth = (int) Math.max(0L, Math.min(16L, transportLagUs / Math.max(1L, frameUs)));
                statusListener.onClientMetrics(new ClientMetricsSample(
                        inFrames, inFrames, outFrames, bytes,
                        decodeMsP50, decodeMsP95, 0.0, 0.0, 0.0,
                        transportQueueDepth,
                        0,
                        0,
                        0, runtimeState.getDroppedTotal(), runtimeState.getTooLateTotal(),
                        (runtimeState.getSessionConnectId() << 32) | (runtimeState.nextSampleSeq() & 0xFFFFFFFFL)
                ));

                if (Log.isLoggable(tag, Log.DEBUG)) {
                    Log.d(tag, String.format(Locale.US,
                            "[decode/framed/png] in=%d out=%d drop=%d dec_p95=%.1fms maxPayload=%d grow=%d resync_ok=%d resync_fail=%d reconn=%d",
                            inFrames, outFrames, droppedSec, decodeMsP95,
                            maxPayloadSeen, payloadGrowEvents, resyncSuccessSec, resyncFailSec, runtimeState.getReconnects()));
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
}
