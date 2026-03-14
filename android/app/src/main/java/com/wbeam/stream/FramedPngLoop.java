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
    private final Config config;

    FramedPngLoop(
            String tag,
            Surface surface,
            StatusListener statusListener,
            RuntimeState runtimeState,
            Config config
    ) {
        this.tag = tag;
        this.surface = surface;
        this.statusListener = statusListener;
        this.runtimeState = runtimeState;
        this.config = config;
    }
 
    void run(InputStream input, byte[] hdrBuf, byte[] payloadBuf, boolean isUltraMode) throws IOException {
        LoopState state = new LoopState(config, isUltraMode);
        PngSurfaceRenderer pngRenderer = new PngSurfaceRenderer(surface);
        byte[] currentPayloadBuf = payloadBuf;

        while (runtimeState.isRunning()) {
            FrameContext frame = readNextFrame(input, hdrBuf, currentPayloadBuf, state);
            if (frame == null) {
                continue;
            }
            currentPayloadBuf = frame.buffer;
            frame = dropBacklogIfNeeded(input, hdrBuf, frame, state);
            currentPayloadBuf = frame.buffer;
            renderFrame(pngRenderer, frame, state);
            enforceNoPresentGuard(state);
            emitPeriodicStats(state);
        }
    }

    private FrameContext readNextFrame(
            InputStream input,
            byte[] hdrBuf,
            byte[] payloadBuf,
            LoopState state
    ) throws IOException {
        WbtpProtocol.FrameHeader header = WbtpProtocol.readFrameHeader(
                input,
                hdrBuf,
                config.frameHeaderSize,
                config.frameMagic,
                config.frameResyncScanLimit,
                config.frameFlagKeyframe
        );
        state.recordResync(header.resynced);
        if (!state.shouldProcessFrame(header.seqU32, header.ptsUs)) {
            return null;
        }
        byte[] buffer = readPayload(input, payloadBuf, header.payloadLen, state);
        return new FrameContext(header.seqU32, header.ptsUs, header.payloadLen, buffer);
    }

    private FrameContext dropBacklogIfNeeded(
            InputStream input,
            byte[] hdrBuf,
            FrameContext frame,
            LoopState state
    ) throws IOException {
        if (!state.shouldDropBacklog()) {
            state.updateBacklogEstimate(estimateBacklogFrames(input, state));
            return frame;
        }
        int backlogEstimate = estimateBacklogFrames(input, state);
        while (backlogEstimate > config.frameBufferBudgetFrames && runtimeState.isRunning()) {
            WbtpProtocol.FrameHeader skippedHeader = WbtpProtocol.readFrameHeader(
                    input,
                    hdrBuf,
                    config.frameHeaderSize,
                    config.frameMagic,
                    config.frameResyncScanLimit,
                    config.frameFlagKeyframe
            );
            state.recordResync(skippedHeader.resynced);
            byte[] buffer = readPayload(input, frame.buffer, skippedHeader.payloadLen, state);
            state.recordDrop(1);
            frame.update(skippedHeader.seqU32, skippedHeader.ptsUs, skippedHeader.payloadLen, buffer);
            backlogEstimate = estimateBacklogFrames(input, state);
        }
        state.updateBacklogEstimate(backlogEstimate);
        return frame;
    }

    private int estimateBacklogFrames(InputStream input, LoopState state) throws IOException {
        int payloadEstimate = Math.max(8 * 1024, state.maxPayloadSeen + config.frameHeaderSize);
        int backlogBytes = Math.max(0, input.available());
        return payloadEstimate > 0 ? backlogBytes / payloadEstimate : 0;
    }

    private void renderFrame(PngSurfaceRenderer renderer, FrameContext frame, LoopState state) throws IOException {
        long startNs = SystemClock.elapsedRealtimeNanos();
        PngSurfaceRenderer.RenderResult result = renderer.render(frame.buffer, frame.payloadLen);
        state.recordDecodeDuration(SystemClock.elapsedRealtimeNanos() - startNs);
        if (result.rendered) {
            state.onFrameRendered(frame.seq, frame.ptsUs);
        } else {
            state.onFrameRejected(frame.seq);
        }
    }

    private byte[] readPayload(InputStream input, byte[] payloadBuf, int payloadLen, LoopState state) throws IOException {
        WbtpPayloadBuffer.validatePayloadLength(payloadLen, config.framePayloadHardCap);
        byte[] resized = WbtpPayloadBuffer.ensureCapacity(
                payloadBuf,
                payloadLen,
                config.framePayloadHardCap,
                tag,
                "WBTP PNG payload buffer grow "
        );
        if (resized != payloadBuf) {
            state.recordPayloadGrow();
        }
        state.trackMaxPayload(payloadLen);
        WbtpFrameIo.readFully(input, resized, payloadLen);
        state.recordBytes(config.frameHeaderSize + payloadLen);
        return resized;
    }

    private void enforceNoPresentGuard(LoopState state) throws IOException {
        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - state.getLastPresentMs() < config.noPresentHardResetMs) {
            return;
        }
        Log.w(tag, "PNG absolute guard: reconnect after 5s with no present");
        statusListener.onStatus(config.stateConnecting, "png stalled >5s: reconnecting", 0);
        throw new IOException("PNG absolute guard: no frame presented for " + (nowMs - state.getLastPresentMs()) + "ms");
    }

    private void emitPeriodicStats(LoopState state) {
        long nowMs = SystemClock.elapsedRealtime();
        if (!state.shouldEmitStats(nowMs)) {
            return;
        }

        runtimeState.addDroppedTotal(state.droppedFrames);
        runtimeState.resetReconnectDelayMs();

        statusListener.onStatus(config.stateStreaming, "rendering live desktop [framed/png]", state.bytes);
        statusListener.onStats(
                "fps in/out: " + state.inFrames + "/" + state.outFrames
                        + " | drops: " + runtimeState.getDroppedTotal()
                        + " | late: " + runtimeState.getTooLateTotal()
                        + " | q(d/r): " + Math.min(state.backlogFramesEstimate, config.frameBufferBudgetFrames) + "/0"
                        + " | max_payload: " + (state.maxPayloadSeen / 1024) + "KB"
                        + " | reconnects: " + runtimeState.getReconnects()
        );

        double decodeMsP50 = state.inFrames > 0 ? (state.decodeNsTotal / 1_000_000.0) / state.inFrames : 0.0;
        double decodeMsP95 = StreamBufferMath.percentileMs(
                state.decodeNsBuf,
                Math.min(state.decodeNsBufN, 128),
                0.95,
                state.decodeNsScratch
        );
        long transportLagUs = state.computeTransportLagUs();
        int transportQueueDepth = (int) Math.max(0L, Math.min(16L, transportLagUs / Math.max(1L, config.frameUs)));
        statusListener.onClientMetrics(new ClientMetricsSample(
                state.inFrames, state.inFrames, state.outFrames, state.bytes,
                decodeMsP50, decodeMsP95, 0.0, 0.0, 0.0,
                transportQueueDepth,
                0,
                0,
                0, runtimeState.getDroppedTotal(), runtimeState.getTooLateTotal(),
                (runtimeState.getSessionConnectId() << 32) | (runtimeState.nextSampleSeq() & 0xFFFFFFFFL)
        ));

        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, String.format(Locale.US,
                    "[decode/framed/png] in=%d out=%d drop=%d dec_p95=%.1fms maxPayload=%d grow=%d resync_ok=%d reconn=%d",
                    state.inFrames, state.outFrames, state.droppedFrames, decodeMsP95,
                    state.maxPayloadSeen, state.payloadGrowEvents, state.resyncSuccess, runtimeState.getReconnects()));
        }

        state.resetInterval(nowMs);
    }

    static final class Config {
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

        private Config(Builder builder) {
            this.frameUs = builder.frameUs;
            this.frameHeaderSize = builder.frameHeaderSize;
            this.frameMagic = builder.frameMagic;
            this.frameResyncScanLimit = builder.frameResyncScanLimit;
            this.frameFlagKeyframe = builder.frameFlagKeyframe;
            this.framePayloadHardCap = builder.framePayloadHardCap;
            this.noPresentHardResetMs = builder.noPresentHardResetMs;
            this.stateConnecting = builder.stateConnecting;
            this.stateStreaming = builder.stateStreaming;
            this.frameBufferBudgetFrames = builder.frameBufferBudgetFrames;
        }

        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private long frameUs;
            private int frameHeaderSize;
            private int frameMagic;
            private int frameResyncScanLimit;
            private int frameFlagKeyframe;
            private int framePayloadHardCap;
            private long noPresentHardResetMs;
            private String stateConnecting;
            private String stateStreaming;
            private int frameBufferBudgetFrames;

            Builder frameUs(long frameUs) {
                this.frameUs = frameUs;
                return this;
            }

            Builder frameHeaderSize(int frameHeaderSize) {
                this.frameHeaderSize = frameHeaderSize;
                return this;
            }

            Builder frameMagic(int frameMagic) {
                this.frameMagic = frameMagic;
                return this;
            }

            Builder frameResyncScanLimit(int frameResyncScanLimit) {
                this.frameResyncScanLimit = frameResyncScanLimit;
                return this;
            }

            Builder frameFlagKeyframe(int frameFlagKeyframe) {
                this.frameFlagKeyframe = frameFlagKeyframe;
                return this;
            }

            Builder framePayloadHardCap(int framePayloadHardCap) {
                this.framePayloadHardCap = framePayloadHardCap;
                return this;
            }

            Builder noPresentHardResetMs(long noPresentHardResetMs) {
                this.noPresentHardResetMs = noPresentHardResetMs;
                return this;
            }

            Builder stateConnecting(String stateConnecting) {
                this.stateConnecting = stateConnecting;
                return this;
            }

            Builder stateStreaming(String stateStreaming) {
                this.stateStreaming = stateStreaming;
                return this;
            }

            Builder frameBufferBudgetFrames(int frameBufferBudgetFrames) {
                this.frameBufferBudgetFrames = frameBufferBudgetFrames;
                return this;
            }

            Config build() {
                return new Config(this);
            }
        }
    }

    private static final class FrameContext {
        long seq;
        long ptsUs;
        int payloadLen;
        byte[] buffer;

        FrameContext(long seq, long ptsUs, int payloadLen, byte[] buffer) {
            this.seq = seq;
            this.ptsUs = ptsUs;
            this.payloadLen = payloadLen;
            this.buffer = buffer;
        }

        void update(long seq, long ptsUs, int payloadLen, byte[] buffer) {
            this.seq = seq;
            this.ptsUs = ptsUs;
            this.payloadLen = payloadLen;
            this.buffer = buffer;
        }
    }

    private static final class LoopState {
        private final Config config;
        private final boolean dropBacklogFrames;
        private final int seqGapBudget;

        private long bytes = 0L;
        private long inFrames = 0L;
        private long outFrames = 0L;
        private long droppedFrames = 0L;
        private long decodeNsTotal = 0L;
        private final long[] decodeNsBuf = new long[128];
        private final long[] decodeNsScratch = new long[128];
        private int decodeNsBufN = 0;
        private int maxPayloadSeen = 0;
        private long payloadGrowEvents = 0L;
        private long resyncSuccess = 0L;
        private long lastLog = SystemClock.elapsedRealtime();
        private long lastPresentMs = lastLog;
        private long expectedSeq = -1L;
        private long lastQueuedPtsUs = -1L;
        private int backlogFramesEstimate = 0;

        LoopState(Config config, boolean dropBacklogFrames) {
            this.config = config;
            this.dropBacklogFrames = dropBacklogFrames;
            this.seqGapBudget = StreamBufferMath.computeSeqGapBudget(config.frameUs, dropBacklogFrames);
        }

        void recordResync(boolean resynced) {
            if (resynced) {
                resyncSuccess++;
            }
        }

        boolean shouldProcessFrame(long seq, long pts) {
            if (expectedSeq < 0) {
                expectedSeq = seq;
            }
            if (seq < expectedSeq) {
                recordDrop(1);
                return false;
            }
            if (seq > expectedSeq + seqGapBudget) {
                expectedSeq = seq;
            }
            if (lastQueuedPtsUs > 0 && pts + 1_000 < lastQueuedPtsUs) {
                recordDrop(1);
                expectedSeq = seq + 1;
                return false;
            }
            return true;
        }

        void recordBytes(long delta) {
            bytes += delta;
        }

        void recordPayloadGrow() {
            payloadGrowEvents++;
        }

        void trackMaxPayload(int payloadLen) {
            maxPayloadSeen = Math.max(maxPayloadSeen, payloadLen);
        }

        void recordDecodeDuration(long durationNs) {
            decodeNsTotal += durationNs;
            decodeNsBuf[(decodeNsBufN++) & 127] = durationNs;
            inFrames++;
        }

        void onFrameRendered(long seq, long pts) {
            outFrames++;
            lastPresentMs = SystemClock.elapsedRealtime();
            lastQueuedPtsUs = pts;
            expectedSeq = seq + 1;
        }

        void onFrameRejected(long seq) {
            recordDrop(1);
            expectedSeq = seq + 1;
        }

        void recordDrop(long count) {
            droppedFrames += count;
        }

        boolean shouldDropBacklog() {
            return dropBacklogFrames;
        }

        void updateBacklogEstimate(int estimate) {
            backlogFramesEstimate = estimate;
        }

        long getLastPresentMs() {
            return lastPresentMs;
        }

        boolean shouldEmitStats(long nowMs) {
            return nowMs - lastLog >= 1000;
        }

        long computeTransportLagUs() {
            long nowEpochUs = System.currentTimeMillis() * 1000L;
            if (lastQueuedPtsUs > 0 && nowEpochUs > lastQueuedPtsUs) {
                return nowEpochUs - lastQueuedPtsUs;
            }
            return 0L;
        }

        void resetInterval(long nowMs) {
            bytes = 0;
            inFrames = 0;
            outFrames = 0;
            droppedFrames = 0;
            maxPayloadSeen = 0;
            payloadGrowEvents = 0;
            resyncSuccess = 0;
            decodeNsTotal = 0;
            decodeNsBufN = 0;
            lastLog = nowMs;
        }
    }
}
