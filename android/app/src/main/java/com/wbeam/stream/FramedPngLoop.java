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

    public static class Config {
        final String tag;
        final Surface surface;
        final StatusListener statusListener;
        final RuntimeState runtimeState;
        final long frameUs;
        final int frameHeaderSize;
        final int frameMagic;
        final int frameResyncScanLimit;
        final int frameFlagKeyframe;
        final int framePayloadHardCap;
        final long noPresentHardResetMs;
        final String stateConnecting;
        final String stateStreaming;
        final int frameBufferBudgetFrames;

        Config(
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
    }

    private final Config config;

    FramedPngLoop(Config config) {
        this.config = config;
    }

    void run(InputStream input, byte[] hdrBuf, byte[] payloadBuf, boolean isUltraMode) throws IOException {
        RunContext ctx = new RunContext(config, hdrBuf, payloadBuf, isUltraMode);
        PngSurfaceRenderer pngRenderer = new PngSurfaceRenderer(config.surface);

        while (config.runtimeState.isRunning()) {
            WbtpProtocol.FrameHeader frameHeader = readFrameHeader(input, ctx.hdrBuf);
            if (frameHeader.resynced) {
                ctx.resyncSuccessSec++;
            }

            long seqU32 = frameHeader.seqU32;
            long ptsUs = frameHeader.ptsUs;

            if (ctx.expectedSeq < 0) {
                ctx.expectedSeq = seqU32;
            }

            if (!isFrameEligible(seqU32, ptsUs, ctx)) {
                continue;
            }

            byte[] newPayloadBuf = ensurePayloadCapacity(ctx.payloadBuf, frameHeader.payloadLen, ctx);
            ctx.payloadBuf = newPayloadBuf;
            ctx.maxPayloadSeen = Math.max(ctx.maxPayloadSeen, frameHeader.payloadLen);

            WbtpFrameIo.readFully(input, ctx.payloadBuf, frameHeader.payloadLen);
            ctx.bytes += config.frameHeaderSize + frameHeader.payloadLen;

            skipBacklogFramesIfNeeded(input, ctx);

            processFrame(pngRenderer, frameHeader.payloadLen, seqU32, ptsUs, ctx);

            handlePeriodicLogging(ctx);
        }
    }

    private WbtpProtocol.FrameHeader readFrameHeader(InputStream input, byte[] hdrBuf) throws IOException {
        return WbtpProtocol.readFrameHeader(
                input,
                hdrBuf,
                config.frameHeaderSize,
                config.frameMagic,
                config.frameResyncScanLimit,
                config.frameFlagKeyframe
        );
    }

    private boolean isFrameEligible(long seqU32, long ptsUs, RunContext ctx) {
        if (seqU32 < ctx.expectedSeq) {
            ctx.droppedSec++;
            return false;
        }
        if (seqU32 > ctx.expectedSeq + ctx.seqGapBudget) {
            ctx.expectedSeq = seqU32;
        }
        if (ctx.lastQueuedPtsUs > 0 && ptsUs + 1_000 < ctx.lastQueuedPtsUs) {
            ctx.droppedSec++;
            ctx.expectedSeq = seqU32 + 1;
            return false;
        }
        return true;
    }

    private byte[] ensurePayloadCapacity(byte[] payloadBuf, int payloadLen, RunContext ctx) throws IOException {
        WbtpPayloadBuffer.validatePayloadLength(payloadLen, config.framePayloadHardCap);
        byte[] grownPayloadBuf = WbtpPayloadBuffer.ensureCapacity(
                payloadBuf,
                payloadLen,
                config.framePayloadHardCap,
                config.tag,
                "WBTP PNG payload buffer grow "
        );
        if (grownPayloadBuf != payloadBuf) {
            ctx.payloadGrowEvents++;
        }
        return grownPayloadBuf;
    }

    private void skipBacklogFramesIfNeeded(InputStream input, RunContext ctx) throws IOException {
        int payloadEstimate = Math.max(8 * 1024, ctx.maxPayloadSeen + config.frameHeaderSize);
        int backlogBytes = Math.max(0, input.available());
        int backlogFramesEstimate = payloadEstimate > 0 ? (backlogBytes / payloadEstimate) : 0;
        int skippedBacklog = 0;

        while (ctx.dropBacklogFrames && backlogFramesEstimate > config.frameBufferBudgetFrames) {
            WbtpProtocol.FrameHeader skippedFrameHeader = readFrameHeader(input, ctx.hdrBuf);
            if (skippedFrameHeader.resynced) {
                ctx.resyncSuccessSec++;
            }

            long nextLen = skippedFrameHeader.payloadLen;
            byte[] grownBacklogPayloadBuf = ensurePayloadCapacity(ctx.payloadBuf, (int) nextLen, ctx);
            ctx.payloadBuf = grownBacklogPayloadBuf;
            ctx.maxPayloadSeen = Math.max(ctx.maxPayloadSeen, (int) nextLen);
            
            WbtpFrameIo.readFully(input, ctx.payloadBuf, (int) nextLen);
            ctx.bytes += config.frameHeaderSize + nextLen;
            skippedBacklog++;

            payloadEstimate = Math.max(8 * 1024, ctx.maxPayloadSeen + config.frameHeaderSize);
            backlogBytes = Math.max(0, input.available());
            backlogFramesEstimate = payloadEstimate > 0 ? (backlogBytes / payloadEstimate) : 0;
        }

        if (skippedBacklog > 0) {
            ctx.droppedSec += skippedBacklog;
        }
    }

    private void processFrame(PngSurfaceRenderer pngRenderer, int payloadLen, long seqU32, long ptsUs, RunContext ctx) {
        long t0 = SystemClock.elapsedRealtimeNanos();
        PngSurfaceRenderer.RenderResult renderResult = pngRenderer.render(ctx.payloadBuf, payloadLen);
        if (renderResult.rendered) {
            ctx.outFrames++;
            ctx.lastPresentMs = SystemClock.elapsedRealtime();
            ctx.lastQueuedPtsUs = ptsUs;
            ctx.expectedSeq = seqU32 + 1;
        } else {
            ctx.droppedSec++;
            ctx.expectedSeq = seqU32 + 1;
        }

        long dn = SystemClock.elapsedRealtimeNanos() - t0;
        ctx.decodeNsTotal += dn;
        ctx.decodeNsBuf[(ctx.decodeNsBufN++) & 127] = dn;
        ctx.inFrames++;

        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - ctx.lastPresentMs >= config.noPresentHardResetMs) {
            Log.w(config.tag, "PNG absolute guard: reconnect after 5s with no present");
            config.statusListener.onStatus(config.stateConnecting, "png stalled >5s: reconnecting", 0);
            throw new IllegalStateException("PNG absolute guard: no frame presented for " + (nowMs - ctx.lastPresentMs) + "ms");
        }
    }

    private void handlePeriodicLogging(RunContext ctx) throws IOException {
        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - ctx.lastLog < 1000) {
            return;
        }

        config.runtimeState.addDroppedTotal(ctx.droppedSec);
        config.runtimeState.resetReconnectDelayMs();

        int payloadEstimate = Math.max(8 * 1024, ctx.maxPayloadSeen + config.frameHeaderSize);
        int backlogBytes = Math.max(0, 0);
        int backlogFramesEstimate = payloadEstimate > 0 ? (backlogBytes / payloadEstimate) : 0;

        config.statusListener.onStatus(config.stateStreaming, "rendering live desktop [framed/png]", ctx.bytes);
        config.statusListener.onStats(
                "fps in/out: " + ctx.inFrames + "/" + ctx.outFrames
                        + " | drops: " + config.runtimeState.getDroppedTotal()
                        + " | late: " + config.runtimeState.getTooLateTotal()
                        + " | q(d/r): " + Math.min(backlogFramesEstimate, config.frameBufferBudgetFrames) + "/0"
                        + " | max_payload: " + (ctx.maxPayloadSeen / 1024) + "KB"
                        + " | reconnects: " + config.runtimeState.getReconnects()
        );

        double decodeMsP50 = ctx.inFrames > 0 ? (ctx.decodeNsTotal / 1_000_000.0) / ctx.inFrames : 0.0;
        double decodeMsP95 = StreamBufferMath.percentileMs(ctx.decodeNsBuf, Math.min(ctx.decodeNsBufN, 128), 0.95, ctx.decodeNsScratch);
        long nowEpochUs = System.currentTimeMillis() * 1000L;
        long transportLagUs = (ctx.lastQueuedPtsUs > 0 && nowEpochUs > ctx.lastQueuedPtsUs)
                ? (nowEpochUs - ctx.lastQueuedPtsUs)
                : 0L;
        int transportQueueDepth = (int) Math.max(0L, Math.min(16L, transportLagUs / Math.max(1L, config.frameUs)));
        config.statusListener.onClientMetrics(new ClientMetricsSample(
                ctx.inFrames, ctx.inFrames, ctx.outFrames, ctx.bytes,
                decodeMsP50, decodeMsP95, 0.0, 0.0, 0.0,
                transportQueueDepth,
                0,
                0,
                0, config.runtimeState.getDroppedTotal(), config.runtimeState.getTooLateTotal(),
                (config.runtimeState.getSessionConnectId() << 32) | (config.runtimeState.nextSampleSeq() & 0xFFFFFFFFL)
        ));

        if (Log.isLoggable(config.tag, Log.DEBUG)) {
            Log.d(config.tag, String.format(Locale.US,
                    "[decode/framed/png] in=%d out=%d drop=%d dec_p95=%.1fms maxPayload=%d grow=%d resync_ok=%d resync_fail=%d reconn=%d",
                    ctx.inFrames, ctx.outFrames, ctx.droppedSec, decodeMsP95,
                    ctx.maxPayloadSeen, ctx.payloadGrowEvents, ctx.resyncSuccessSec, ctx.resyncFailSec, config.runtimeState.getReconnects()));
        }

        ctx.bytes = 0;
        ctx.inFrames = 0;
        ctx.outFrames = 0;
        ctx.droppedSec = 0;
        ctx.maxPayloadSeen = 0;
        ctx.payloadGrowEvents = 0;
        ctx.resyncSuccessSec = 0;
        ctx.resyncFailSec = 0;
        ctx.decodeNsTotal = 0;
        ctx.decodeNsBufN = 0;
        ctx.lastLog = nowMs;
    }

    private static class RunContext {
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
        final int seqGapBudget;
        final boolean dropBacklogFrames;
        byte[] hdrBuf;
        byte[] payloadBuf;

        RunContext(Config config, byte[] hdrBuf, byte[] payloadBuf, boolean isUltraMode) {
            this.hdrBuf = hdrBuf;
            this.payloadBuf = payloadBuf;
            this.seqGapBudget = StreamBufferMath.computeSeqGapBudget(config.frameUs, isUltraMode);
            this.dropBacklogFrames = isUltraMode;
        }
    }
}
