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
    private static final long DRAIN_IDLE_CHECK_MS = 50L;

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

    @SuppressWarnings({
            "java:S3776",
            "java:S6541",
            "java:S135",
            "java:S1854",
            "java:S1481"
    })
    void run(InputStream input, MediaCodec codec) throws IOException {
        byte[] readBuf = new byte[64 * 1024];

        // ── Buffers ──────────────────────────────────────────────────────────
        // Mode 0 (Annex-B start-code framing) uses a standard compacting linear
        // buffer (sHead/sTail).  Mode 1 (length-prefixed AVCC framing, the hot
        // path from our streamer) uses a ring buffer backed by the same array:
        // rHead/rTail are unbounded counters; the array index is (counter & RB_MASK).
        // Ring buffer eliminates compacting arraycopy under backpressure — the only
        // copy is a one-time linearisation when a NAL payload crosses the buffer end
        // (~5% of NALs at full 512 KB utilisation).
        byte[] streamBuf = new byte[512 * 1024];   // shared backing store, power-of-2
        final int RB_MASK = streamBuf.length - 1;

        // Mode 0 linear pointers
        int sHead = 0;
        int sTail = 0;
        // Mode 1 ring-buffer counters (unbounded; use & RB_MASK for array index)
        int rHead = 0;
        int rTail = 0;
        // Scratch buffer for ring wrap-around linearisation (allocated on first wrap)
        byte[] nalLinBuf = null;

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
        long lastDrainAttemptMs = 0L;
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

            // ── Write ─────────────────────────────────────────────────────────
            if (streamMode != 1) {
                // Linear write for mode 0 / mode-detection phase
                int avail = sTail - sHead;
                if (sTail + count > streamBuf.length) {
                    if (avail + count > streamBuf.length) {
                        int keep = streamBuf.length - count;
                        if (keep <= 0) {
                            sHead = 0; sTail = 0; avail = 0;
                        } else {
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

                // Mode detection
                int availNow = sTail - sHead;
                if (streamMode < 0 && availNow >= 8) {
                    int probe = StreamNalUtils.findStartCode(streamBuf, sHead, Math.min(sHead + 128, sTail));
                    streamMode = (probe >= 0) ? 0 : 1;
                    if (streamMode == 1) {
                        // Seed ring buffer from the small linearly-buffered bootstrap bytes.
                        System.arraycopy(streamBuf, sHead, streamBuf, 0, availNow);
                        rHead = 0;
                        rTail = availNow;
                        continue; // process ring buffer on next iteration
                    }
                }
                if (streamMode != 0) continue; // still undetermined
            } else {
                // Ring-buffer write for mode 1
                int avail = rTail - rHead;
                if (avail + count > streamBuf.length) {
                    // Buffer full: drop oldest data (should be very rare at 512 KB)
                    int excess = avail + count - streamBuf.length;
                    rHead += excess;
                    droppedSec++;
                }
                int wp = rTail & RB_MASK;
                int c1 = Math.min(count, streamBuf.length - wp);
                System.arraycopy(readBuf, 0, streamBuf, wp, c1);
                if (c1 < count) System.arraycopy(readBuf, c1, streamBuf, 0, count - c1);
                rTail += count;
                bytes += count;
            }

            // ── Parse ─────────────────────────────────────────────────────────
            if (streamMode == 1) {
                int avail = rTail - rHead;
                while (avail >= 4) {
                    // Ring-aware 4-byte big-endian NAL size read
                    int nalSize = ((streamBuf[(rHead    ) & RB_MASK] & 0xFF) << 24)
                                | ((streamBuf[(rHead + 1) & RB_MASK] & 0xFF) << 16)
                                | ((streamBuf[(rHead + 2) & RB_MASK] & 0xFF) << 8)
                                |  (streamBuf[(rHead + 3) & RB_MASK] & 0xFF);
                    if (nalSize <= 0 || nalSize > streamBuf.length) {
                        rHead++; avail--; droppedSec++; continue;
                    }
                    if (avail < 4 + nalSize) break;

                    // Serve NAL payload — zero-copy if contiguous, one-time
                    // linearisation if it crosses the ring-buffer end.
                    int nalStart = (rHead + 4) & RB_MASK;
                    byte[] nalData;
                    int nalOff;
                    if (nalStart + nalSize <= streamBuf.length) {
                        nalData = streamBuf;
                        nalOff  = nalStart;
                    } else {
                        if (nalLinBuf == null || nalLinBuf.length < nalSize) {
                            nalLinBuf = new byte[Math.max(nalSize, 65536)];
                        }
                        int c1 = streamBuf.length - nalStart;
                        System.arraycopy(streamBuf, nalStart, nalLinBuf, 0, c1);
                        System.arraycopy(streamBuf, 0,         nalLinBuf, c1, nalSize - c1);
                        nalData = nalLinBuf;
                        nalOff  = 0;
                    }

                    frames++;
                    long nowMs = SystemClock.elapsedRealtime();
                    boolean shouldDrain = pendingDecodeQueue > 0
                            || lastDrainAttemptMs == 0L
                            || (nowMs - lastDrainAttemptMs) >= DRAIN_IDLE_CHECK_MS;
                    if (shouldDrain) {
                        MediaCodecBridge.drainLatestFrame(
                                codec, bufferInfo, drainStats, true,
                                pendingDecodeQueue >= decodeQueueMaxFrames ? 16_000 : 5_000);
                        lastDrainAttemptMs = nowMs;
                    } else {
                        drainStats.reset();
                    }
                    pendingDecodeQueue = Math.max(0, pendingDecodeQueue - drainStats.releasedCount);
                    if (drainStats.releasedCount > 0) {
                        lastDecodeProgressMs = nowMs;
                    } else if (pendingDecodeQueue >= decodeQueueMaxFrames
                            && (nowMs - lastDecodeProgressMs) > 300) {
                        pendingDecodeQueue = decodeQueueMaxFrames - 1;
                    }
                    outFrames += drainStats.renderedCount;
                    tooLateSec += drainStats.droppedLateCount;
                    renderNsMax = Math.max(renderNsMax, drainStats.renderNsMax);
                    renderQueueDepth = drainStats.renderedCount > 0 ? 1 : 0;

                    if (pendingDecodeQueue >= decodeQueueMaxFrames) {
                        if (StreamNalUtils.isRecoveryNal(nalData, nalOff, nalSize)) {
                            long t0 = SystemClock.elapsedRealtimeNanos();
                            if (MediaCodecBridge.queueNal(codec, nalData, nalOff, nalSize, frames * frameUs, 1_000)) {
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
                        if (MediaCodecBridge.queueNal(codec, nalData, nalOff, nalSize, frames * frameUs, 1_000)) {
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
                    rHead += 4 + nalSize;
                    avail  = rTail - rHead;
                }
            } else {
                // Mode 0: Annex-B start-code parsing (unchanged linear path)
                int avail = sTail - sHead;
                int nalStart = StreamNalUtils.findStartCode(streamBuf, sHead, sTail);
                if (nalStart < 0) {
                    sHead = Math.max(sHead, sTail - 3);
                } else {
                    sHead = nalStart;
                    while (true) {
                        int next = StreamNalUtils.findStartCode(streamBuf, sHead + 3, sTail);
                        if (next < 0) break;
                        int nalSize = next - sHead;
                        if (nalSize > 0) {
                            frames++;
                            long nowMs = SystemClock.elapsedRealtime();
                            boolean shouldDrain = pendingDecodeQueue > 0
                                    || lastDrainAttemptMs == 0L
                                    || (nowMs - lastDrainAttemptMs) >= DRAIN_IDLE_CHECK_MS;
                            if (shouldDrain) {
                                MediaCodecBridge.drainLatestFrame(
                                        codec, bufferInfo, drainStats, true,
                                        pendingDecodeQueue >= decodeQueueMaxFrames ? 16_000 : 5_000);
                                lastDrainAttemptMs = nowMs;
                            } else {
                                drainStats.reset();
                            }
                            pendingDecodeQueue = Math.max(0, pendingDecodeQueue - drainStats.releasedCount);
                            if (drainStats.releasedCount > 0) {
                                lastDecodeProgressMs = nowMs;
                            } else if (pendingDecodeQueue >= decodeQueueMaxFrames
                                    && (nowMs - lastDecodeProgressMs) > 300) {
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
                // bufAvail is the number of unprocessed bytes in the active buffer.
                int bufAvail = (streamMode == 1) ? (rTail - rHead) : (sTail - sHead);
                statusListener.onStats(
                        "fps in/out: " + inFrames + "/" + outFrames
                                + " | drops: " + runtimeState.getDroppedTotal()
                                + " | late: " + runtimeState.getTooLateTotal()
                                + " | q(t/d/r): "
                                + StreamBufferMath.estimateTransportDepthFrames(bufAvail, avgNalSize) + "/"
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
                        StreamBufferMath.estimateTransportDepthFrames(bufAvail, avgNalSize),
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
