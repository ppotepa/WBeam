package com.wbeam.stream;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

final class MediaCodecBridge {

    private MediaCodecBridge() {
    }

    static boolean queueNal(
            MediaCodec codec,
            byte[] data,
            int offset,
            int size,
            long ptsUs,
            long inputTimeoutUs
    ) {
        int inputIndex = codec.dequeueInputBuffer(inputTimeoutUs);
        if (inputIndex < 0) {
            return false;
        }
        ByteBuffer inputBuffer = getInputBuffer(codec, inputIndex);
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

    static boolean queueCodecConfig(MediaCodec codec, byte[] data, long inputTimeoutUs) {
        if (codec == null || data == null || data.length == 0) {
            return false;
        }
        int inputIndex = codec.dequeueInputBuffer(inputTimeoutUs);
        if (inputIndex < 0) {
            return false;
        }
        ByteBuffer inputBuffer = getInputBuffer(codec, inputIndex);
        if (inputBuffer == null) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0, 0);
            return false;
        }
        inputBuffer.clear();
        if (data.length > inputBuffer.remaining()) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0, 0);
            return false;
        }
        inputBuffer.put(data);
        codec.queueInputBuffer(inputIndex, 0, data.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
        return true;
    }

    static MediaCodec createAvcDecoderWithCsd(
            byte[] sps,
            byte[] pps,
            int decodeWidth,
            int decodeHeight,
            int maxInputSize,
            Surface surface
    ) throws IOException {
        try {
            MediaCodec codec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", decodeWidth, decodeHeight);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize);
            if (sps != null) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            }
            if (pps != null) {
                fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            }
            codec.configure(fmt, surface, null, 0);
            codec.start();
            return codec;
        } catch (Exception e) {
            throw new IOException("decoder init failed for legacy AVC", e);
        }
    }

    @SuppressWarnings({"java:S3776", "java:S135"})
    static void drainLatestFrame(
            MediaCodec codec,
            MediaCodec.BufferInfo info,
            DrainStats stats,
            boolean dropLateOutput,
            long firstTimeoutUs
    ) {
        stats.reset();
        int latestRenderableIndex = -1;
        long latestRenderablePtsUs = -1L;
        long timeoutUs = firstTimeoutUs;

        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(info, timeoutUs);
            timeoutUs = 0;
            if (outputIndex >= 0) {
                stats.releasedCount++;
                boolean renderable = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                if (!renderable) {
                    codec.releaseOutputBuffer(outputIndex, false);
                    continue;
                }
                if (dropLateOutput) {
                    if (latestRenderableIndex >= 0) {
                        codec.releaseOutputBuffer(latestRenderableIndex, false);
                        stats.droppedLateCount++;
                    }
                    latestRenderableIndex = outputIndex;
                    latestRenderablePtsUs = info.presentationTimeUs;
                } else {
                    long renderStartNs = SystemClock.elapsedRealtimeNanos();
                    codec.releaseOutputBuffer(outputIndex, true);
                    stats.renderedCount++;
                    stats.lastRenderedPtsUs = info.presentationTimeUs;
                    stats.renderNsMax = Math.max(
                            stats.renderNsMax,
                            SystemClock.elapsedRealtimeNanos() - renderStartNs
                    );
                }
                continue;
            }
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER
                    || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                break;
            }
            break;
        }

        if (dropLateOutput && latestRenderableIndex >= 0) {
            long renderStartNs = SystemClock.elapsedRealtimeNanos();
            codec.releaseOutputBuffer(latestRenderableIndex, true);
            stats.renderedCount = 1;
            stats.lastRenderedPtsUs = latestRenderablePtsUs;
            stats.renderNsMax = SystemClock.elapsedRealtimeNanos() - renderStartNs;
        }
    }

    private static ByteBuffer getInputBuffer(MediaCodec codec, int inputIndex) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return codec.getInputBuffer(inputIndex);
        }
        ByteBuffer[] inputBuffers = codec.getInputBuffers();
        return (inputBuffers != null && inputIndex < inputBuffers.length)
                ? inputBuffers[inputIndex]
                : null;
    }

    static final class DrainStats {
        int releasedCount;
        int renderedCount;
        int droppedLateCount;
        long renderNsMax;
        long lastRenderedPtsUs;

        void reset() {
            releasedCount = 0;
            renderedCount = 0;
            droppedLateCount = 0;
            renderNsMax = 0;
            lastRenderedPtsUs = -1L;
        }
    }
}
