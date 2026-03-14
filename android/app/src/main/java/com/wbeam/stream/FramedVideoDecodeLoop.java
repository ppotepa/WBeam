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

@SuppressWarnings("S1192")
final class FramedVideoDecodeLoop {

    private static final String PAYLOAD_LABEL = " payload=";

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
    @SuppressWarnings("S1192")
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

    @SuppressWarnings({"java:S3776", "java:S6541", "java:S135", "java:S1854"})
    void run(InputStream input, MediaCodec[] codecRef, byte[] helloBuf, byte[] hdrBuf, byte[] payloadBuf) throws IOException {
        long bytes = 0L;
        long inFrames = 0L;
        long outFrames = 0L;
        long droppedSec = 0L;
        long tooLateSec = 0L;
        int maxPayloadSeen = 0;
        long payloadGrowEvents = 0L;
        long resyncSuccessSec = 0L;
        long flushCountSec = 0L;
        long recoveryUnlockSec = 0L;
        long waitGateDropsSec = 0L;
        long decodeNsTotal = 0L;
        long[] decodeNsBuf = new long[128];
        long[] decodeNsScratch = new long[128];
        int decodeNsBufN = 0;
        long renderNsMax = 0L;
        long lastLog = SystemClock.elapsedRealtime();
        long lastPresentMs = SystemClock.elapsedRealtime();
        long totalInSincePresent = 0L;
        long lastDecodeProgressMs = SystemClock.elapsedRealtime();
        long expectedSeq = -1L;
        long lastQueuedPtsUs = -1L;
        long lastPresentedPtsUs = -1L;
        boolean flushIssued = false;
        boolean waitForKeyframe = false;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        MediaCodecBridge.DrainStats drainStats = new MediaCodecBridge.DrainStats();
        int pendingDecodeQueue = 0;

        WbtpProtocol.Hello hello = WbtpProtocol.readHello(input, helloBuf, 0x57425331, (byte) 0x01, 16);
        final int helloFlags = hello.flags;
        final boolean isPng = (helloFlags & helloCodecPng) != 0;
        final boolean isHevc = !isPng && (helloFlags & helloCodecHevc) != 0;
        final int streamMode = helloFlags & helloModeMask;
        final boolean isUltraMode = streamMode == helloModeUltra;
        final String videoMime;
        if (isPng) {
            videoMime = "image/png";
        } else if (isHevc) {
            videoMime = "video/hevc";
        } else {
            videoMime = "video/avc";
        }
        long streamSessionId = hello.sessionId;
        final String modeLabel;
        if (streamMode == helloModeUltra) {
            modeLabel = "ultra";
        } else if (streamMode == helloModeQuality) {
            modeLabel = "quality";
        } else {
            modeLabel = "stable";
        }
        final String codecLabel = determineCodecLabel(isPng, isHevc);
        Log.i(tag, String.format(Locale.US, "WBTP hello session=0x%016x codec=%s mode=%s",
                streamSessionId, codecLabel, modeLabel));

        if (isPng) {
            throw new IOException("WBTP PNG lane passed to video decode loop");
        }

        final int seqGapBudget = StreamBufferMath.computeSeqGapBudget(frameUs, isUltraMode);
        final boolean dropLateOutput = isUltraMode;

        if (isHevc && !DecoderSupport.codecSupported(videoMime)) {
            throw new IOException(
                    "HEVC (video/hevc) decoder not available on this device "
                            + "(API " + Build.VERSION.SDK_INT + "). "
                            + "Configure the host to use H.264 (encoder=h264).");
        }

        final boolean legacyAvcBootstrap = !isHevc
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1;
        byte[] legacySps = null;
        byte[] legacyPps = null;

        MediaCodec codec = null;
        if (!legacyAvcBootstrap) {
            try {
                codec = MediaCodec.createDecoderByType(videoMime);
                MediaFormat fmt = MediaFormat.createVideoFormat(videoMime, decodeWidth, decodeHeight);
                codec.configure(fmt, surface, null, 0);
                codec.start();
                codecRef[0] = codec;
            } catch (Exception e) {
                throw new IOException("decoder init failed for " + videoMime, e);
            }
        } else {
            waitForKeyframe = true;
            Log.i(tag, "legacy AVC bootstrap enabled (API " + Build.VERSION.SDK_INT + ")");
        }

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
            boolean frameIsKey = frameHeader.frameIsKey;
            long seqU32 = frameHeader.seqU32;
            long ptsUs = frameHeader.ptsUs;
            int payloadLen = frameHeader.payloadLen;

            WbtpPayloadBuffer.validatePayloadLength(payloadLen, framePayloadHardCap);
            byte[] grownPayloadBuf = WbtpPayloadBuffer.ensureCapacity(
                    payloadBuf,
                    payloadLen,
                    framePayloadHardCap,
                    tag,
                    "WBTP payload buffer grow "
                            + " seq=" + seqU32 + PAYLOAD_LABEL + payloadLen + " mode=" + modeLabel + " "
            );
            if (grownPayloadBuf != payloadBuf) {
                payloadBuf = grownPayloadBuf;
                payloadGrowEvents++;
            }
            maxPayloadSeen = Math.max(maxPayloadSeen, payloadLen);

            WbtpFrameIo.readFully(input, payloadBuf, payloadLen);
            bytes += frameHeaderSize + payloadLen;

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

            if (legacyAvcBootstrap && codec == null) {
                StreamNalUtils.AvcCsd avcCsd = StreamNalUtils.extractAvcCsd(payloadBuf, payloadLen);
                if (avcCsd.sps != null) {
                    legacySps = avcCsd.sps;
                }
                if (avcCsd.pps != null) {
                    legacyPps = avcCsd.pps;
                }
                if (legacySps != null && legacyPps != null) {
                    try {
                        codec = MediaCodecBridge.createAvcDecoderWithCsd(
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
                        waitForKeyframe = true;
                        Log.i(tag, "legacy AVC decoder configured from in-stream SPS/PPS");
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IOException("legacy AVC bootstrap failed", e);
                    }
                } else {
                    droppedSec++;
                    expectedSeq = seqU32 + 1;
                    continue;
                }
            }

            if (codec == null) {
                droppedSec++;
                expectedSeq = seqU32 + 1;
                continue;
            }

            MediaCodecBridge.drainLatestFrame(
                    codec,
                    bufferInfo,
                    drainStats,
                    dropLateOutput,
                    pendingDecodeQueue >= decodeQueueMaxFrames ? 16_000 : 5_000
            );
            long nowAfterDrain = SystemClock.elapsedRealtime();
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

            boolean isRecovery = frameIsKey || StreamNalUtils.containsRecoveryNal(
                    payloadBuf, payloadLen, isHevc, frameResyncScanLimit
            );
            if (waitForKeyframe && isRecovery) {
                waitForKeyframe = false;
                recoveryUnlockSec++;
                Log.w(tag, "recovery-unlock: seq=" + seqU32 + " key=" + frameIsKey
                        + PAYLOAD_LABEL + payloadLen + " qDecode=" + pendingDecodeQueue);
            }
            boolean canQueue = !waitForKeyframe
                    && (pendingDecodeQueue < decodeQueueMaxFrames || isRecovery);
            if (canQueue) {
                long t0 = SystemClock.elapsedRealtimeNanos();
                if (MediaCodecBridge.queueNal(codec, payloadBuf, 0, payloadLen, ptsUs, 1_000)) {
                    long dn = SystemClock.elapsedRealtimeNanos() - t0;
                    decodeNsTotal += dn;
                    decodeNsBuf[(decodeNsBufN++) & 127] = dn;
                    inFrames++;
                    totalInSincePresent++;
                    pendingDecodeQueue = Math.min(decodeQueueMaxFrames, pendingDecodeQueue + 1);
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
                        Log.w(tag, "waitForKeyframe drop: seq=" + seqU32
                                + " payload=" + payloadLen
                                + " dropped=" + waitGateDropsSec
                                + " qDecode=" + pendingDecodeQueue);
                    }
                }
                waitForKeyframe = true;
                expectedSeq = seqU32 + 1;
            }

            long nowMs = SystemClock.elapsedRealtime();
            long noPresentMs = nowMs - lastPresentMs;
            if (!flushIssued
                    && totalInSincePresent >= noPresentMinInFramesFlush
                    && noPresentMs >= noPresentFlushMs) {
                try {
                    codec.flush();
                    pendingDecodeQueue = 0;
                    flushIssued = true;
                    waitForKeyframe = true;
                    flushCountSec++;
                    Log.w(tag, "C5 ladder L1: codec.flush() due to no-present");
                    statusListener.onStatus(stateConnecting, "decoder stalled: flushing codec", 0);
                } catch (Exception flushErr) {
                    throw new IOException("C5: codec.flush failed", flushErr);
                }
            }
            if (totalInSincePresent >= noPresentMinInFramesReconnect
                    && noPresentMs >= noPresentReconnectMs) {
                Log.w(tag, "C5 ladder L2: reconnect framed stream due to no-present");
                statusListener.onStatus(stateConnecting, "decoder stalled: reconnecting stream", 0);
                throw new IOException("C5: no frames presented for " + noPresentMs
                        + "ms (" + totalInSincePresent + " decoded) – reconnect");
            }
            if (totalInSincePresent >= noPresentMinInFramesHard
                    && noPresentMs >= noPresentHardResetMs) {
                Log.w(tag, "C5 ladder L3: hard reconnect watchdog");
                statusListener.onStatus(stateConnecting, "decoder watchdog: hard reconnect", 0);
                throw new IOException("C5: hard watchdog: "
                        + totalInSincePresent + " frames decoded, 0 presented for "
                        + noPresentMs + "ms – reconnect");
            }
            if (noPresentMs >= noPresentHardResetMs) {
                Log.w(tag, "C5 absolute guard: reconnect after 5s with no present");
                statusListener.onStatus(stateConnecting, "decoder stalled >5s: reconnecting", 0);
                throw new IOException("C5 absolute guard: no frame presented for " + noPresentMs + "ms");
            }

            if (nowMs - lastLog >= 1000) {
                runtimeState.addDroppedTotal(droppedSec);
                runtimeState.addTooLateTotal(tooLateSec);
                runtimeState.resetReconnectDelayMs();
                statusListener.onStatus(stateStreaming, "rendering live desktop [framed]", bytes);
                statusListener.onStats(
                        "fps in/out: " + inFrames + "/" + outFrames
                                + " | drops: " + runtimeState.getDroppedTotal()
                                + " | late: " + runtimeState.getTooLateTotal()
                                + " | q(d/r): " + pendingDecodeQueue + "/" + (drainStats.renderedCount > 0 ? 1 : 0)
                                + " | max_payload: " + (maxPayloadSeen / 1024) + "KB"
                                + " | reconnects: " + runtimeState.getReconnects()
                );
                double decodeMsP50 = inFrames > 0 ? (decodeNsTotal / 1_000_000.0) / inFrames : 0.0;
                double decodeMsP95 = StreamBufferMath.percentileMs(decodeNsBuf, Math.min(decodeNsBufN, 128), 0.95, decodeNsScratch);
                double renderMsP95 = renderNsMax / 1_000_000.0;
                long nowEpochUs = System.currentTimeMillis() * 1000L;
                long transportLagUs = (lastQueuedPtsUs > 0 && nowEpochUs > lastQueuedPtsUs)
                        ? (nowEpochUs - lastQueuedPtsUs)
                        : 0L;
                int transportQueueDepth = (int) Math.max(0L, Math.min(16L, transportLagUs / Math.max(1L, frameUs)));
                int queueDecodeDepth = Math.min(decodeQueueMaxFrames, pendingDecodeQueue);
                int queueRenderDepth = Math.min(renderQueueMaxFrames, drainStats.renderedCount > 0 ? 1 : 0);
                double e2eMs = StreamBufferMath.estimateE2eLatencyMs(lastPresentedPtsUs);
                statusListener.onClientMetrics(new ClientMetricsSample(
                        inFrames, inFrames, outFrames, bytes,
                        decodeMsP50, decodeMsP95, renderMsP95, e2eMs, e2eMs,
                        transportQueueDepth,
                        queueDecodeDepth,
                        queueRenderDepth,
                        0, runtimeState.getDroppedTotal(), runtimeState.getTooLateTotal(),
                        (runtimeState.getSessionConnectId() << 32) | (runtimeState.nextSampleSeq() & 0xFFFFFFFFL)
                ));
                if (Log.isLoggable(tag, Log.DEBUG)) {
                    Log.d(tag, String.format(Locale.US,
                            "[decode/framed] in=%d out=%d drop=%d late=%d"
                                    + " qD=%d/%d qR=%d dec_p95=%.1fms ren_p95=%.1fms"
                                    + " maxPayload=%d grow=%d flush=%d unlock=%d waitDrop=%d"
                                    + " resync_ok=%d noPresent=%d reconn=%d",
                            inFrames, outFrames, droppedSec, tooLateSec,
                            queueDecodeDepth, decodeQueueMaxFrames,
                            drainStats.renderedCount > 0 ? 1 : 0,
                            decodeMsP95, renderMsP95,
                            maxPayloadSeen, payloadGrowEvents, flushCountSec, recoveryUnlockSec, waitGateDropsSec,
                            resyncSuccessSec, totalInSincePresent, runtimeState.getReconnects()));
                }
                bytes = 0;
                inFrames = 0;
                outFrames = 0;
                droppedSec = 0;
                tooLateSec = 0;
                maxPayloadSeen = 0;
                flushCountSec = 0;
                recoveryUnlockSec = 0;
                waitGateDropsSec = 0;
                decodeNsTotal = 0;
                decodeNsBufN = 0;
                renderNsMax = 0;
                lastLog = nowMs;
            }
        }
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
}
