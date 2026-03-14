package com.wbeam.stream;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.wbeam.ClientMetricsSample;
import com.wbeam.api.StatusListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class FramedVideoDecodeLoop {

    private static final String PAYLOAD_LABEL = " payload=";
    private static final String MIME_PNG = "image/png";
    private static final String MIME_HEVC = "video/hevc";
    private static final String MIME_AVC = "video/avc";

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
    private final Surface surface;
    private final StatusListener statusListener;
    private final RuntimeState runtimeState;
    private final long frameUs;
    private final int decodeWidth;
    private final int decodeHeight;
    private final int helloCodecHevc;
    private final int helloCodecPng;
    private final int helloModeMask;
    private final int helloModeUltra;
    private final int helloModeQuality;
    private final int decodeQueueMaxFrames;
    private final int renderQueueMaxFrames;
    private final int frameHeaderSize;
    private final int frameMagic;
    private final int frameResyncScanLimit;
    private final int frameFlagKeyframe;
    private final int framePayloadInitialCap;
    private final int framePayloadHardCap;
    private final long noPresentFlushMs;
    private final long noPresentReconnectMs;
    private final long noPresentHardResetMs;
    private final int noPresentMinInFramesFlush;
    private final int noPresentMinInFramesReconnect;
    private final int noPresentMinInFramesHard;
    private final String stateConnecting;
    private final String stateStreaming;

    @SuppressWarnings("java:S107")
    FramedVideoDecodeLoop(
            String tag,
            Surface surface,
            StatusListener statusListener,
            RuntimeState runtimeState,
            long frameUs,
            int decodeWidth,
            int decodeHeight,
            int helloCodecHevc,
            int helloCodecPng,
            int helloModeMask,
            int helloModeUltra,
            int helloModeQuality,
            int decodeQueueMaxFrames,
            int renderQueueMaxFrames,
            int frameHeaderSize,
            int frameMagic,
            int frameResyncScanLimit,
            int frameFlagKeyframe,
            int framePayloadInitialCap,
            int framePayloadHardCap,
            long noPresentFlushMs,
            long noPresentReconnectMs,
            long noPresentHardResetMs,
            int noPresentMinInFramesFlush,
            int noPresentMinInFramesReconnect,
            int noPresentMinInFramesHard,
            String stateConnecting,
            String stateStreaming
    ) {
        this.tag = tag;
        this.surface = surface;
        this.statusListener = statusListener;
        this.runtimeState = runtimeState;
        this.frameUs = frameUs;
        this.decodeWidth = decodeWidth;
        this.decodeHeight = decodeHeight;
        this.helloCodecHevc = helloCodecHevc;
        this.helloCodecPng = helloCodecPng;
        this.helloModeMask = helloModeMask;
        this.helloModeUltra = helloModeUltra;
        this.helloModeQuality = helloModeQuality;
        this.decodeQueueMaxFrames = decodeQueueMaxFrames;
        this.renderQueueMaxFrames = renderQueueMaxFrames;
        this.frameHeaderSize = frameHeaderSize;
        this.frameMagic = frameMagic;
        this.frameResyncScanLimit = frameResyncScanLimit;
        this.frameFlagKeyframe = frameFlagKeyframe;
        this.framePayloadInitialCap = framePayloadInitialCap;
        this.framePayloadHardCap = framePayloadHardCap;
        this.noPresentFlushMs = noPresentFlushMs;
        this.noPresentReconnectMs = noPresentReconnectMs;
        this.noPresentHardResetMs = noPresentHardResetMs;
        this.noPresentMinInFramesFlush = noPresentMinInFramesFlush;
        this.noPresentMinInFramesReconnect = noPresentMinInFramesReconnect;
        this.noPresentMinInFramesHard = noPresentMinInFramesHard;
        this.stateConnecting = stateConnecting;
        this.stateStreaming = stateStreaming;
    }

    void run(InputStream input, MediaCodec[] codecRef, byte[] helloBuf, byte[] hdrBuf, byte[] payloadBuf) throws IOException {
        DecodeLoopState state = new DecodeLoopState(decodeQueueMaxFrames, renderQueueMaxFrames, frameUs);
        StreamProfile profile = readHello(input, helloBuf);
        DecoderSession session = initializeDecoderSession(profile, codecRef);
        byte[] currentPayloadBuf = payloadBuf;

        while (runtimeState.isRunning()) {
            FrameContext frame = readFrame(input, hdrBuf, currentPayloadBuf, state, profile.modeLabel);
            currentPayloadBuf = frame.buffer;
            if (!state.shouldProcessFrame(frame.seqU32, frame.ptsUs, profile.seqGapBudget)) {
                continue;
            }

            session.codec = bootstrapLegacyDecoderIfNeeded(session, frame, codecRef);
            if (session.codec == null) {
                state.onRejectedFrame(frame.seqU32);
                continue;
            }

            drainCodec(session, state);
            boolean isRecoveryFrame = isRecoveryFrame(frame, profile.isHevc);
            session.recoveryGate.unlockIfNeeded(frame, isRecoveryFrame, state.getPendingDecodeQueue(), tag);
            if (session.recoveryGate.canQueueFrame(isRecoveryFrame, state.getPendingDecodeQueue(), decodeQueueMaxFrames)) {
                queueFrame(session.codec, frame, state, session.drainContext.drainTimeMs);
            } else {
                state.onRejectedFrame(frame.seqU32);
                session.recoveryGate.recordBlockedFrame(frame, isRecoveryFrame, state.getPendingDecodeQueue(), tag);
            }

            WatchdogAction action = evaluateWatchdog(state);
            if (action != WatchdogAction.NONE) {
                executeWatchdogAction(session.codec, session.recoveryGate, state, action);
            }
            emitPeriodicStats(state, session);
        }
    }

    private StreamProfile readHello(InputStream input, byte[] helloBuf) throws IOException {
        WbtpProtocol.Hello hello = WbtpProtocol.readHello(input, helloBuf, 0x57425331, (byte) 0x01, 16);
        StreamProfile profile = new StreamProfile(
                hello.flags,
                hello.sessionId,
                helloCodecHevc,
                helloCodecPng,
                helloModeMask,
                helloModeUltra,
                helloModeQuality,
                frameUs
        );
        Log.i(tag, String.format(Locale.US, "WBTP hello session=0x%016x codec=%s mode=%s",
                profile.streamSessionId, profile.codecLabel, profile.modeLabel));

        if (profile.isPng) {
            throw new IOException("WBTP PNG lane passed to video decode loop");
        }
        if (profile.isHevc && !DecoderSupport.codecSupported(profile.videoMime)) {
            throw new IOException(
                    "HEVC (" + profile.videoMime + ") decoder not available on this device "
                            + "(API " + Build.VERSION.SDK_INT + "). "
                            + "Configure the host to use H.264 (encoder=h264)."
            );
        }
        return profile;
    }

    private DecoderSession initializeDecoderSession(StreamProfile profile, MediaCodec[] codecRef) throws IOException {
        MediaCodec codec = null;
        RecoveryGate recoveryGate = new RecoveryGate(false);
        if (!profile.legacyAvcBootstrap) {
            codec = createDecoder(profile.videoMime);
            codecRef[0] = codec;
        } else {
            recoveryGate = new RecoveryGate(true);
            Log.i(tag, "legacy AVC bootstrap enabled (API " + Build.VERSION.SDK_INT + ")");
        }
        return new DecoderSession(profile, codec, recoveryGate);
    }

    private MediaCodec createDecoder(String videoMime) throws IOException {
        try {
            MediaCodec codec = MediaCodec.createDecoderByType(videoMime);
            MediaFormat format = MediaFormat.createVideoFormat(videoMime, decodeWidth, decodeHeight);
            codec.configure(format, surface, null, 0);
            codec.start();
            return codec;
        } catch (Exception e) {
            throw new IOException("decoder init failed for " + videoMime, e);
        }
    }

    private FrameContext readFrame(
            InputStream input,
            byte[] hdrBuf,
            byte[] payloadBuf,
            DecodeLoopState state,
            String modeLabel
    ) throws IOException {
        WbtpProtocol.FrameHeader frameHeader = WbtpProtocol.readFrameHeader(
                input,
                hdrBuf,
                frameHeaderSize,
                frameMagic,
                frameResyncScanLimit,
                frameFlagKeyframe
        );
        state.recordResync(frameHeader.resynced);
        WbtpPayloadBuffer.validatePayloadLength(frameHeader.payloadLen, framePayloadHardCap);
        byte[] grownPayloadBuf = WbtpPayloadBuffer.ensureCapacity(
                payloadBuf,
                frameHeader.payloadLen,
                framePayloadHardCap,
                tag,
                "WBTP payload buffer grow seq="
                        + frameHeader.seqU32 + PAYLOAD_LABEL + frameHeader.payloadLen + " mode=" + modeLabel + " "
        );
        state.recordPayloadGrowth(grownPayloadBuf != payloadBuf);
        state.trackMaxPayload(frameHeader.payloadLen);
        WbtpFrameIo.readFully(input, grownPayloadBuf, frameHeader.payloadLen);
        state.recordBytes(frameHeaderSize + frameHeader.payloadLen);
        return new FrameContext(
                frameHeader.frameIsKey,
                frameHeader.seqU32,
                frameHeader.ptsUs,
                frameHeader.payloadLen,
                grownPayloadBuf
        );
    }

    private MediaCodec bootstrapLegacyDecoderIfNeeded(
            DecoderSession session,
            FrameContext frame,
            MediaCodec[] codecRef
    ) throws IOException {
        if (!session.profile.legacyAvcBootstrap || session.codec != null) {
            return session.codec;
        }
        MediaCodec codec = session.legacyBootstrap.tryCreateCodec(frame, codecRef);
        if (codec != null) {
            session.recoveryGate.enterRecoveryWait();
            Log.i(tag, "legacy AVC decoder configured from in-stream SPS/PPS");
        }
        return codec;
    }

    private void drainCodec(DecoderSession session, DecodeLoopState state) {
        int drainTimeoutUs = state.getPendingDecodeQueue() >= decodeQueueMaxFrames ? 16_000 : 5_000;
        MediaCodecBridge.drainLatestFrame(
                session.codec,
                session.drainContext.bufferInfo,
                session.drainContext.stats,
                session.profile.dropLateOutput,
                drainTimeoutUs
        );
        long nowAfterDrain = SystemClock.elapsedRealtime();
        session.drainContext.drainTimeMs = nowAfterDrain;
        state.onDrain(session.drainContext.stats, nowAfterDrain);
    }

    private boolean isRecoveryFrame(FrameContext frame, boolean isHevc) {
        return frame.frameIsKey
                || StreamNalUtils.containsRecoveryNal(frame.buffer, frame.payloadLen, isHevc, frameResyncScanLimit);
    }

    private void queueFrame(
            MediaCodec codec,
            FrameContext frame,
            DecodeLoopState state,
            long nowAfterDrainMs
    ) {
        long queueStartNs = SystemClock.elapsedRealtimeNanos();
        if (MediaCodecBridge.queueNal(codec, frame.buffer, 0, frame.payloadLen, frame.ptsUs, 1_000)) {
            long decodeDurationNs = SystemClock.elapsedRealtimeNanos() - queueStartNs;
            state.onFrameQueued(frame.seqU32, frame.ptsUs, decodeDurationNs, nowAfterDrainMs);
            return;
        }
        state.onRejectedFrame(frame.seqU32);
    }

    private WatchdogAction evaluateWatchdog(DecodeLoopState state) {
        long noPresentMs = state.getNoPresentMs();
        if (!state.hasFlushIssued()
                && state.getTotalInSincePresent() >= noPresentMinInFramesFlush
                && noPresentMs >= noPresentFlushMs) {
            return WatchdogAction.FLUSH;
        }
        if (state.getTotalInSincePresent() >= noPresentMinInFramesReconnect
                && noPresentMs >= noPresentReconnectMs) {
            return WatchdogAction.RECONNECT;
        }
        if (state.getTotalInSincePresent() >= noPresentMinInFramesHard
                && noPresentMs >= noPresentHardResetMs) {
            return WatchdogAction.HARD_RECONNECT;
        }
        if (noPresentMs >= noPresentHardResetMs) {
            return WatchdogAction.ABSOLUTE_RECONNECT;
        }
        return WatchdogAction.NONE;
    }

    private void executeWatchdogAction(
            MediaCodec codec,
            RecoveryGate recoveryGate,
            DecodeLoopState state,
            WatchdogAction action
    ) throws IOException {
        switch (action) {
            case FLUSH:
                try {
                    codec.flush();
                    state.onFlush();
                    recoveryGate.enterRecoveryWait();
                    Log.w(tag, "C5 ladder L1: codec.flush() due to no-present");
                    statusListener.onStatus(stateConnecting, "decoder stalled: flushing codec", 0);
                    return;
                } catch (Exception flushErr) {
                    throw new IOException("C5: codec.flush failed", flushErr);
                }
            case RECONNECT:
                Log.w(tag, "C5 ladder L2: reconnect framed stream due to no-present");
                statusListener.onStatus(stateConnecting, "decoder stalled: reconnecting stream", 0);
                throw new IOException("C5: no frames presented for " + state.getNoPresentMs()
                        + "ms (" + state.getTotalInSincePresent() + " decoded) – reconnect");
            case HARD_RECONNECT:
                Log.w(tag, "C5 ladder L3: hard reconnect watchdog");
                statusListener.onStatus(stateConnecting, "decoder watchdog: hard reconnect", 0);
                throw new IOException("C5: hard watchdog: "
                        + state.getTotalInSincePresent() + " frames decoded, 0 presented for "
                        + state.getNoPresentMs() + "ms – reconnect");
            case ABSOLUTE_RECONNECT:
                Log.w(tag, "C5 absolute guard: reconnect after 5s with no present");
                statusListener.onStatus(stateConnecting, "decoder stalled >5s: reconnecting", 0);
                throw new IOException("C5 absolute guard: no frame presented for " + state.getNoPresentMs() + "ms");
            case NONE:
            default:
                return;
        }
    }

    private void emitPeriodicStats(DecodeLoopState state, DecoderSession session) {
        long nowMs = SystemClock.elapsedRealtime();
        if (!state.shouldEmitStats(nowMs)) {
            return;
        }

        runtimeState.addDroppedTotal(state.getDroppedSec());
        runtimeState.addTooLateTotal(state.getTooLateSec());
        runtimeState.resetReconnectDelayMs();
        statusListener.onStatus(stateStreaming, "rendering live desktop [framed]", state.getBytes());
        statusListener.onStats(
                "fps in/out: " + state.getInFrames() + "/" + state.getOutFrames()
                        + " | drops: " + runtimeState.getDroppedTotal()
                        + " | late: " + runtimeState.getTooLateTotal()
                        + " | q(d/r): " + state.getQueueDecodeDepth() + "/" + state.getQueueRenderDepth(session.drainContext.stats)
                        + " | max_payload: " + (state.getMaxPayloadSeen() / 1024) + "KB"
                        + " | reconnects: " + runtimeState.getReconnects()
        );

        double decodeMsP50 = state.computeDecodeMsP50();
        double decodeMsP95 = state.computeDecodeMsP95();
        double renderMsP95 = state.computeRenderMsP95();
        double e2eMs = StreamBufferMath.estimateE2eLatencyMs(state.getLastPresentedPtsUs());
        int transportQueueDepth = state.computeTransportQueueDepth();

        statusListener.onClientMetrics(new ClientMetricsSample(
                state.getInFrames(), state.getInFrames(), state.getOutFrames(), state.getBytes(),
                decodeMsP50, decodeMsP95, renderMsP95, e2eMs, e2eMs,
                transportQueueDepth,
                state.getQueueDecodeDepth(),
                state.getQueueRenderDepth(session.drainContext.stats),
                0, runtimeState.getDroppedTotal(), runtimeState.getTooLateTotal(),
                (runtimeState.getSessionConnectId() << 32) | (runtimeState.nextSampleSeq() & 0xFFFFFFFFL)
        ));

        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, String.format(Locale.US,
                    "[decode/framed] in=%d out=%d drop=%d late=%d qD=%d/%d qR=%d dec_p95=%.1fms ren_p95=%.1fms"
                            + " maxPayload=%d grow=%d flush=%d unlock=%d waitDrop=%d resync_ok=%d noPresent=%d reconn=%d",
                    state.getInFrames(), state.getOutFrames(), state.getDroppedSec(), state.getTooLateSec(),
                    state.getQueueDecodeDepth(), decodeQueueMaxFrames,
                    state.getQueueRenderDepth(session.drainContext.stats),
                    decodeMsP95, renderMsP95,
                    state.getMaxPayloadSeen(), state.getPayloadGrowEvents(), state.getFlushCountSec(),
                    session.recoveryGate.getRecoveryUnlocks(), session.recoveryGate.getWaitGateDrops(),
                    state.getResyncSuccessSec(), state.getTotalInSincePresent(), runtimeState.getReconnects()));
        }

        state.resetInterval(nowMs);
    }

    private static String determineCodecLabel(boolean isPng, boolean isHevc) {
        if (isPng) {
            return "PNG";
        }
        if (isHevc) {
            return "HEVC";
        }
        return "AVC";
    }

    private enum WatchdogAction {
        NONE,
        FLUSH,
        RECONNECT,
        HARD_RECONNECT,
        ABSOLUTE_RECONNECT
    }

    private static final class StreamProfile {
        final boolean isPng;
        final boolean isHevc;
        final boolean isUltraMode;
        final boolean dropLateOutput;
        final boolean legacyAvcBootstrap;
        final int seqGapBudget;
        final String videoMime;
        final String modeLabel;
        final String codecLabel;
        final long streamSessionId;

        StreamProfile(
                int helloFlags,
                long streamSessionId,
                int helloCodecHevc,
                int helloCodecPng,
                int helloModeMask,
                int helloModeUltra,
                int helloModeQuality,
                long frameUs
        ) {
            this.isPng = (helloFlags & helloCodecPng) != 0;
            this.isHevc = !isPng && (helloFlags & helloCodecHevc) != 0;
            int streamMode = helloFlags & helloModeMask;
            this.isUltraMode = streamMode == helloModeUltra;
            this.dropLateOutput = isUltraMode;
            this.videoMime = determineVideoMime(isPng, isHevc);
            this.modeLabel = determineModeLabel(streamMode, helloModeUltra, helloModeQuality);
            this.codecLabel = determineCodecLabel(isPng, isHevc);
            this.streamSessionId = streamSessionId;
            this.seqGapBudget = StreamBufferMath.computeSeqGapBudget(frameUs, isUltraMode);
            this.legacyAvcBootstrap = !isHevc
                    && Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1;
        }

        private static String determineVideoMime(boolean isPng, boolean isHevc) {
            if (isPng) {
                return MIME_PNG;
            }
            if (isHevc) {
                return MIME_HEVC;
            }
            return MIME_AVC;
        }

        private static String determineModeLabel(int streamMode, int helloModeUltra, int helloModeQuality) {
            if (streamMode == helloModeUltra) {
                return "ultra";
            }
            if (streamMode == helloModeQuality) {
                return "quality";
            }
            return "stable";
        }
    }

    private final class DecoderSession {
        final StreamProfile profile;
        final LegacyBootstrapState legacyBootstrap = new LegacyBootstrapState();
        final RecoveryGate recoveryGate;
        final DrainContext drainContext = new DrainContext();
        MediaCodec codec;

        DecoderSession(StreamProfile profile, MediaCodec codec, RecoveryGate recoveryGate) {
            this.profile = profile;
            this.codec = codec;
            this.recoveryGate = recoveryGate;
        }
    }

    private final class LegacyBootstrapState {
        private byte[] legacySps;
        private byte[] legacyPps;

        MediaCodec tryCreateCodec(FrameContext frame, MediaCodec[] codecRef) throws IOException {
            StreamNalUtils.AvcCsd avcCsd = StreamNalUtils.extractAvcCsd(frame.buffer, frame.payloadLen);
            if (avcCsd.sps != null) {
                legacySps = avcCsd.sps;
            }
            if (avcCsd.pps != null) {
                legacyPps = avcCsd.pps;
            }
            if (legacySps == null || legacyPps == null) {
                return null;
            }
            try {
                MediaCodec codec = MediaCodecBridge.createAvcDecoderWithCsd(
                        legacySps,
                        legacyPps,
                        decodeWidth,
                        decodeHeight,
                        framePayloadInitialCap,
                        surface
                );
                codecRef[0] = codec;
                MediaCodecBridge.queueCodecConfig(codec, legacySps, 2_000);
                MediaCodecBridge.queueCodecConfig(codec, legacyPps, 2_000);
                return codec;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("legacy AVC bootstrap failed", e);
            }
        }
    }

    private static final class RecoveryGate {
        private boolean waitForKeyframe;
        private long recoveryUnlocks;
        private long waitGateDrops;

        RecoveryGate(boolean waitForKeyframe) {
            this.waitForKeyframe = waitForKeyframe;
        }

        void unlockIfNeeded(FrameContext frame, boolean isRecoveryFrame, int pendingDecodeQueue, String tag) {
            if (!waitForKeyframe || !isRecoveryFrame) {
                return;
            }
            waitForKeyframe = false;
            recoveryUnlocks++;
            Log.w(tag, "recovery-unlock: seq=" + frame.seqU32 + " key=" + frame.frameIsKey
                    + PAYLOAD_LABEL + frame.payloadLen + " qDecode=" + pendingDecodeQueue);
        }

        boolean canQueueFrame(boolean isRecoveryFrame, int pendingDecodeQueue, int decodeQueueMaxFrames) {
            return !waitForKeyframe && (pendingDecodeQueue < decodeQueueMaxFrames || isRecoveryFrame);
        }

        void recordBlockedFrame(FrameContext frame, boolean isRecoveryFrame, int pendingDecodeQueue, String tag) {
            boolean alreadyWaiting = waitForKeyframe;
            if (alreadyWaiting && !isRecoveryFrame) {
                waitGateDrops++;
                if ((waitGateDrops & 31) == 1) {
                    Log.w(tag, "waitForKeyframe drop: seq=" + frame.seqU32
                            + PAYLOAD_LABEL + frame.payloadLen
                            + " dropped=" + waitGateDrops
                            + " qDecode=" + pendingDecodeQueue);
                }
            }
            waitForKeyframe = true;
        }

        void enterRecoveryWait() {
            waitForKeyframe = true;
        }

        long getRecoveryUnlocks() {
            return recoveryUnlocks;
        }

        long getWaitGateDrops() {
            return waitGateDrops;
        }
    }

    private static final class DrainContext {
        final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        final MediaCodecBridge.DrainStats stats = new MediaCodecBridge.DrainStats();
        long drainTimeMs;
    }

    private static final class FrameContext {
        final boolean frameIsKey;
        final long seqU32;
        final long ptsUs;
        final int payloadLen;
        final byte[] buffer;

        FrameContext(boolean frameIsKey, long seqU32, long ptsUs, int payloadLen, byte[] buffer) {
            this.frameIsKey = frameIsKey;
            this.seqU32 = seqU32;
            this.ptsUs = ptsUs;
            this.payloadLen = payloadLen;
            this.buffer = buffer;
        }
    }

    private static final class DecodeLoopState {
        private final int decodeQueueMaxFrames;
        private final int renderQueueMaxFrames;
        private final long frameUs;
        private final long[] decodeNsBuf = new long[128];
        private final long[] decodeNsScratch = new long[128];

        private long bytes;
        private long inFrames;
        private long outFrames;
        private long droppedSec;
        private long tooLateSec;
        private int maxPayloadSeen;
        private long payloadGrowEvents;
        private long resyncSuccessSec;
        private long flushCountSec;
        private long decodeNsTotal;
        private int decodeNsBufN;
        private long renderNsMax;
        private long lastLog = SystemClock.elapsedRealtime();
        private long lastPresentMs = lastLog;
        private long totalInSincePresent;
        private long lastDecodeProgressMs = lastLog;
        private long expectedSeq = -1L;
        private long lastQueuedPtsUs = -1L;
        private long lastPresentedPtsUs = -1L;
        private boolean flushIssued;
        private int pendingDecodeQueue;

        DecodeLoopState(int decodeQueueMaxFrames, int renderQueueMaxFrames, long frameUs) {
            this.decodeQueueMaxFrames = decodeQueueMaxFrames;
            this.renderQueueMaxFrames = renderQueueMaxFrames;
            this.frameUs = frameUs;
        }

        void recordResync(boolean resynced) {
            if (resynced) {
                resyncSuccessSec++;
            }
        }

        void recordPayloadGrowth(boolean grew) {
            if (grew) {
                payloadGrowEvents++;
            }
        }

        void trackMaxPayload(int payloadLen) {
            maxPayloadSeen = Math.max(maxPayloadSeen, payloadLen);
        }

        void recordBytes(long delta) {
            bytes += delta;
        }

        boolean shouldProcessFrame(long seqU32, long ptsUs, int seqGapBudget) {
            if (expectedSeq < 0) {
                expectedSeq = seqU32;
            }
            if (seqU32 < expectedSeq) {
                droppedSec++;
                return false;
            }
            if (seqU32 > expectedSeq + seqGapBudget) {
                expectedSeq = seqU32;
            }
            if (lastQueuedPtsUs > 0 && ptsUs + 1_000 < lastQueuedPtsUs) {
                onRejectedFrame(seqU32);
                return false;
            }
            return true;
        }

        void onRejectedFrame(long seqU32) {
            droppedSec++;
            expectedSeq = seqU32 + 1;
        }

        void onDrain(MediaCodecBridge.DrainStats drainStats, long nowAfterDrain) {
            pendingDecodeQueue = Math.max(0, pendingDecodeQueue - drainStats.releasedCount);
            if (drainStats.releasedCount > 0) {
                lastDecodeProgressMs = nowAfterDrain;
            } else if (pendingDecodeQueue >= decodeQueueMaxFrames
                    && (nowAfterDrain - lastDecodeProgressMs) > 300) {
                pendingDecodeQueue = decodeQueueMaxFrames - 1;
            }
            if (drainStats.renderedCount > 0) {
                outFrames += drainStats.renderedCount;
                lastPresentMs = nowAfterDrain;
                totalInSincePresent = 0;
                flushIssued = false;
                if (drainStats.lastRenderedPtsUs > 0) {
                    lastPresentedPtsUs = drainStats.lastRenderedPtsUs;
                }
            }
            tooLateSec += drainStats.droppedLateCount;
            renderNsMax = Math.max(renderNsMax, drainStats.renderNsMax);
        }

        void onFrameQueued(long seqU32, long ptsUs, long decodeDurationNs, long nowAfterDrainMs) {
            decodeNsTotal += decodeDurationNs;
            decodeNsBuf[(decodeNsBufN++) & 127] = decodeDurationNs;
            inFrames++;
            totalInSincePresent++;
            pendingDecodeQueue = Math.min(decodeQueueMaxFrames, pendingDecodeQueue + 1);
            lastDecodeProgressMs = nowAfterDrainMs;
            lastQueuedPtsUs = ptsUs;
            expectedSeq = seqU32 + 1;
        }

        void onFlush() {
            pendingDecodeQueue = 0;
            flushIssued = true;
            flushCountSec++;
        }

        boolean hasFlushIssued() {
            return flushIssued;
        }

        long getNoPresentMs() {
            return SystemClock.elapsedRealtime() - lastPresentMs;
        }

        long getTotalInSincePresent() {
            return totalInSincePresent;
        }

        boolean shouldEmitStats(long nowMs) {
            return nowMs - lastLog >= 1000;
        }

        double computeDecodeMsP50() {
            return inFrames > 0 ? (decodeNsTotal / 1_000_000.0) / inFrames : 0.0;
        }

        double computeDecodeMsP95() {
            return StreamBufferMath.percentileMs(
                    decodeNsBuf,
                    Math.min(decodeNsBufN, decodeNsBuf.length),
                    0.95,
                    decodeNsScratch
            );
        }

        double computeRenderMsP95() {
            return renderNsMax / 1_000_000.0;
        }

        int computeTransportQueueDepth() {
            long nowEpochUs = System.currentTimeMillis() * 1000L;
            long transportLagUs = (lastQueuedPtsUs > 0 && nowEpochUs > lastQueuedPtsUs)
                    ? (nowEpochUs - lastQueuedPtsUs)
                    : 0L;
            return (int) Math.max(0L, Math.min(16L, transportLagUs / Math.max(1L, frameUs)));
        }

        int getQueueDecodeDepth() {
            return Math.min(decodeQueueMaxFrames, pendingDecodeQueue);
        }

        int getQueueRenderDepth(MediaCodecBridge.DrainStats drainStats) {
            return Math.min(renderQueueMaxFrames, drainStats.renderedCount > 0 ? 1 : 0);
        }

        void resetInterval(long nowMs) {
            bytes = 0L;
            inFrames = 0L;
            outFrames = 0L;
            droppedSec = 0L;
            tooLateSec = 0L;
            maxPayloadSeen = 0;
            payloadGrowEvents = 0L;
            resyncSuccessSec = 0L;
            flushCountSec = 0L;
            decodeNsTotal = 0L;
            decodeNsBufN = 0;
            renderNsMax = 0L;
            lastLog = nowMs;
        }

        long getBytes() {
            return bytes;
        }

        long getInFrames() {
            return inFrames;
        }

        long getOutFrames() {
            return outFrames;
        }

        long getDroppedSec() {
            return droppedSec;
        }

        long getTooLateSec() {
            return tooLateSec;
        }

        int getMaxPayloadSeen() {
            return maxPayloadSeen;
        }

        long getPayloadGrowEvents() {
            return payloadGrowEvents;
        }

        long getResyncSuccessSec() {
            return resyncSuccessSec;
        }

        long getFlushCountSec() {
            return flushCountSec;
        }

        long getLastPresentedPtsUs() {
            return lastPresentedPtsUs;
        }

        int getPendingDecodeQueue() {
            return pendingDecodeQueue;
        }
    }
}
