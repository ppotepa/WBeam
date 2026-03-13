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

    @SuppressWarnings("java:java:S3776")
    @SuppressWarnings("java:java:S6541")
    void run(InputStream input, MediaCodec codec) throws IOException {
        byte[] readBuf = new byte[64 * 1024];
        byte[] streamBuf = new byte[512 * 1024];
        int sHead = 0;
        int sTail = 0;
        int streamMode = -1;
        int avgNalSize = 1200;
        int pendingDecodeQueue = 0;
        int renderQueueDepth = 0;

        long frames = 0;
        long bytes = 0;
        long inFrames = 0;
        long outFrames = 0;
        long droppedSec = 0;
        long tooLateSec = 0;
        long decodeNsTotal = 0;
        long[] decodeNsBuf = new long[128];
        long[] decodeNsScratch = new long[128];
        int decodeNsBufN = 0;
        long renderNsMax = 0;
        long lastLog = SystemClock.elapsedRealtime();
        long lastPresentMs = SystemClock.elapsedRealtime();
        long lastPresentedPtsUs = -1L;
        long pendingWithNoPresent = 0;
        long lastDecodeProgressMs = SystemClock.elapsedRealtime();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        MediaCodecBridge.DrainStats drainStats = new MediaCodecBridge.DrainStats();

        while (runtimeState.isRunning()) {
            int count = input.read(readBuf);
            if (count < 0) {
                throw new IOException("stream closed");
            }
            if (count == 0) {
                continue;
            }

            int avail = sTail - sHead;
            if (sTail + count > streamBuf.length) {
                if (avail + count > streamBuf.length) {
                    int keep = streamBuf.length - count;
                    if (keep <= 0) {
                        sHead = 0;
                        sTail = 0;
                    } else {
                        int newHead = sHead + (avail - keep);
                        System.arraycopy(streamBuf, newHead, streamBuf, 0, keep);
                        sHead = 0;
                        sTail = keep;
                    }
                    droppedSec++;
                } else {
                    if (avail > 0) {
                        System.arraycopy(streamBuf, sHead, streamBuf, 0, avail);
                    }
                    sHead = 0;
                    sTail = avail;
                }
            }
            System.arraycopy(readBuf, 0, streamBuf, sTail, count);
            sTail += count;
            bytes += count;

            avail = sTail - sHead;
            if (streamMode < 0 && avail >= 8) {
                int probe = StreamNalUtils.findStartCode(streamBuf, sHead, Math.min(sHead + 128, sTail));
                streamMode = (probe >= 0) ? 0 : 1;
            }
 @SuppressWarnings("java:java:S135")

            if (streamMode == 1) {
                while ((sTail - sHead) >= 4) {
                    int nalSize = ((streamBuf[sHead] & 0xFF) << 24)
                            | ((streamBuf[sHead + 1] & 0xFF) << 16)
                            | ((streamBuf[sHead + 2] & 0xFF) << 8)
                            | (streamBuf[sHead + 3] & 0xFF);
                    if (nalSize <= 0 || nalSize > streamBuf.length) {
                        sHead += 1;
                        droppedSec++;
                        continue;
                    }
                    if ((sTail - sHead) < 4 + nalSize) {
                        break;
                    }

                    frames++;
                    MediaCodecBridge.drainLatestFrame(
                            codec,
                            bufferInfo,
                            drainStats,
                            true,
                            pendingDecodeQueue >= decodeQueueMaxFrames ? 16_000 : 5_000
                    );
                    pendingDecodeQueue = Math.max(0, pendingDecodeQueue - drainStats.releasedCount);
                    if (drainStats.releasedCount > 0) {
                        lastDecodeProgressMs = SystemClock.elapsedRealtime();
                    } else if (pendingDecodeQueue >= decodeQueueMaxFrames
                            && (SystemClock.elapsedRealtime() - lastDecodeProgressMs) > 300) {
                        pendingDecodeQueue = decodeQueueMaxFrames - 1;
                    }
                    outFrames += drainStats.renderedCount;
                    tooLateSec += drainStats.droppedLateCount;
                    renderNsMax = Math.max(renderNsMax, drainStats.renderNsMax);
                    renderQueueDepth = drainStats.renderedCount > 0 ? 1 : 0;

                    if (pendingDecodeQueue >= decodeQueueMaxFrames) {
                        if (StreamNalUtils.isRecoveryNal(streamBuf, sHead + 4, nalSize)) {
                            long t0 = SystemClock.elapsedRealtimeNanos();
                            if (MediaCodecBridge.queueNal(codec, streamBuf, sHead + 4, nalSize, frames * frameUs, 1_000)) {
                                long dn = SystemClock.elapsedRealtimeNanos() - t0;
                                decodeNsTotal += dn;
                                decodeNsBuf[(decodeNsBufN++) & 127] = dn;
                                avgNalSize = ((avgNalSize * 7) + nalSize) / 8;
                                inFrames++;
                                pendingDecodeQueue = Math.min(decodeQueueMaxFrames, pendingDecodeQueue + 1);
                                lastDecodeProgressMs = SystemClock.elapsedRealtime();
                            } else {
                                droppedSec++;
                            }
                        } else {
                            droppedSec++;
                        }
                    } else {
                        long t0 = SystemClock.elapsedRealtimeNanos();
                        if (MediaCodecBridge.queueNal(codec, streamBuf, sHead + 4, nalSize, frames * frameUs, 1_000)) {
                            long dn = SystemClock.elapsedRealtimeNanos() - t0;
                            decodeNsTotal += dn;
                            decodeNsBuf[(decodeNsBufN++) & 127] = dn;
                            avgNalSize = ((avgNalSize * 7) + nalSize) / 8;
                            inFrames++;
                            pendingDecodeQueue++;
                            lastDecodeProgressMs = SystemClock.elapsedRealtime();
                        } else {
                            droppedSec++;
                        }
                    }
                    sHead += 4 + nalSize;
                }
            } else {
                int nalStart = StreamNalUtils.findStartCode(streamBuf, sHead, sTail);
                if (nalStart < 0) {
                    sHead = Math.max(sHead, sTail - 3);
                } else {
                    sHead = nalStart;
                    while (true) {
                        int next = StreamNalUtils.findStartCode(streamBuf, sHead + 3, sTail);
                        if (next < 0) {
                            break;
                        }
                        int nalSize = next - sHead;
                        if (nalSize > 0) {
                            frames++;
                            MediaCodecBridge.drainLatestFrame(
                                    codec,
                                    bufferInfo,
                                    drainStats,
                                    true,
                                    pendingDecodeQueue >= decodeQueueMaxFrames ? 16_000 : 5_000
                            );
                            pendingDecodeQueue = Math.max(0, pendingDecodeQueue - drainStats.releasedCount);
                            if (drainStats.releasedCount > 0) {
                                lastDecodeProgressMs = SystemClock.elapsedRealtime();
                            } else if (pendingDecodeQueue >= decodeQueueMaxFrames
                                    && (SystemClock.elapsedRealtime() - lastDecodeProgressMs) > 300) {
                                pendingDecodeQueue = decodeQueueMaxFrames - 1;
                            }
                            outFrames += drainStats.renderedCount;
                            tooLateSec += drainStats.droppedLateCount;
                            renderNsMax = Math.max(renderNsMax, drainStats.renderNsMax);
                            renderQueueDepth = drainStats.renderedCount > 0 ? 1 : 0;

                            if (pendingDecodeQueue >= decodeQueueMaxFrames) {
                                if (StreamNalUtils.isRecoveryNal(streamBuf, sHead, nalSize)) {
                                    long t0 = SystemClock.elapsedRealtimeNanos();
                                    if (MediaCodecBridge.queueNal(codec, streamBuf, sHead, nalSize, frames * frameUs, 1_000)) {
                                        long dn = SystemClock.elapsedRealtimeNanos() - t0;
                                        decodeNsTotal += dn;
                                        decodeNsBuf[(decodeNsBufN++) & 127] = dn;
                                        avgNalSize = ((avgNalSize * 7) + nalSize) / 8;
                                        inFrames++;
                                        pendingDecodeQueue = Math.min(decodeQueueMaxFrames, pendingDecodeQueue + 1);
                                        lastDecodeProgressMs = SystemClock.elapsedRealtime();
                                    } else {
                                        droppedSec++;
                                    }
                                } else {
                                    droppedSec++;
                                }
                            } else {
                                long t0 = SystemClock.elapsedRealtimeNanos();
                                if (MediaCodecBridge.queueNal(codec, streamBuf, sHead, nalSize, frames * frameUs, 1_000)) {
                                    long dn = SystemClock.elapsedRealtimeNanos() - t0;
                                    decodeNsTotal += dn;
                                    decodeNsBuf[(decodeNsBufN++) & 127] = dn;
                                    avgNalSize = ((avgNalSize * 7) + nalSize) / 8;
                                    inFrames++;
                                    pendingDecodeQueue++;
                                    lastDecodeProgressMs = SystemClock.elapsedRealtime();
                                } else {
                                    droppedSec++;
                                }
                            }
                        }
                        sHead = next;
                    }
                }
            }

            if (drainStats.renderedCount > 0) {
                lastPresentMs = SystemClock.elapsedRealtime();
                pendingWithNoPresent = 0;
            } else if (inFrames > 0) {
                pendingWithNoPresent++;
            }
            if (drainStats.renderedCount > 0 && drainStats.lastRenderedPtsUs > 0) {
                lastPresentedPtsUs = drainStats.lastRenderedPtsUs;
            }
            if (pendingWithNoPresent > 300 && (SystemClock.elapsedRealtime() - lastPresentMs) > 5_000) {
                throw new IOException("C5: black-screen watchdog: 0 frames presented in 5s with "
                        + pendingWithNoPresent + " decoded – reconnecting");
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastLog >= 1000) {
                runtimeState.addDroppedTotal(droppedSec);
                runtimeState.addTooLateTotal(tooLateSec);
                runtimeState.resetReconnectDelayMs();
                statusListener.onStatus(stateStreaming, "rendering live desktop", bytes);
                statusListener.onStats(
                        "fps in/out: " + inFrames + "/" + outFrames
                                + " | drops: " + runtimeState.getDroppedTotal()
                                + " | late: " + runtimeState.getTooLateTotal()
                                + " | q(t/d/r): "
                                + StreamBufferMath.estimateTransportDepthFrames(sTail - sHead, avgNalSize) + "/"
                                + Math.min(decodeQueueMaxFrames, pendingDecodeQueue) + "/"
                                + renderQueueDepth
                                + " | reconnects: " + runtimeState.getReconnects()
                );
                double decodeMsP50 = inFrames > 0 ? (decodeNsTotal / 1_000_000.0) / inFrames : 0.0;
                double decodeMsP95 = StreamBufferMath.percentileMs(decodeNsBuf, Math.min(decodeNsBufN, 128), 0.95, decodeNsScratch);
                double renderMsP95 = renderNsMax / 1_000_000.0;
                double e2eMs = StreamBufferMath.estimateE2eLatencyMs(lastPresentedPtsUs);
                statusListener.onClientMetrics(new ClientMetricsSample(
                        inFrames, inFrames, outFrames, bytes,
                        decodeMsP50, decodeMsP95, renderMsP95, e2eMs, e2eMs,
                        StreamBufferMath.estimateTransportDepthFrames(sTail - sHead, avgNalSize),
                        Math.min(decodeQueueMaxFrames, pendingDecodeQueue),
                        Math.min(renderQueueMaxFrames, renderQueueDepth),
                        0, runtimeState.getDroppedTotal(), runtimeState.getTooLateTotal(),
                        (runtimeState.getSessionConnectId() << 32) | (runtimeState.nextSampleSeq() & 0xFFFFFFFFL)
                ));
                if (Log.isLoggable(tag, Log.DEBUG)) {
                    Log.d(tag, String.format(Locale.US,
                            "[decode/legacy] in=%d out=%d drop=%d late=%d qD=%d/%d qR=%d/%d"
                                    + " dec_p95=%.1fms ren_p95=%.1fms noPresent=%d reconn=%d",
                            inFrames, outFrames, droppedSec, tooLateSec,
                            Math.min(decodeQueueMaxFrames, pendingDecodeQueue), decodeQueueMaxFrames,
                            renderQueueDepth, renderQueueMaxFrames,
                            decodeMsP95, renderMsP95, pendingWithNoPresent, runtimeState.getReconnects()));
                }
                bytes = 0;
                inFrames = 0;
                outFrames = 0;
                droppedSec = 0;
                tooLateSec = 0;
                decodeNsTotal = 0;
                decodeNsBufN = 0;
                renderNsMax = 0;
                lastLog = now;
            }
        }
    }
}
