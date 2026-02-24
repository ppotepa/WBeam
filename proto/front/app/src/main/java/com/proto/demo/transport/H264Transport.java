package com.proto.demo.transport;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
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

/**
 * H264 over WBH1 framing → MediaCodec Surface decode.
 * Minimal latency path; assumes Annex B byte-stream with SPS/PPS present.
 */
public class H264Transport implements Transport {

    private static final String TAG = "WBeamH264";
    private static final int    PORT = StreamConfig.ADB_PORT;
    private static final int    MAX_NAL = 2 * 1024 * 1024; // guard

    private final StatusUpdater status;
    private final Surface       surface;
    private final String        sizeHint; // e.g., "1280x800"

    private volatile boolean running = true;

    public H264Transport(StatusUpdater status, Surface surface, String sizeHint) {
        this.status   = status;
        this.surface  = surface;
        this.sizeHint = sizeHint;
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
                    client.setSoTimeout(5_000);
                    status.set("H264: client connected");
                    decodeStream(new BufferedInputStream(client.getInputStream(), 512 * 1024));
                } catch (IOException e) {
                    if (running) status.set("H264: connection lost");
                }
                sleep(150);
            }
        } catch (Exception e) {
            if (running) status.set("H264: error " + e.getMessage());
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
                status.set("H264: port busy (" + (70 - i) + "s) " + e.getMessage());
                sleep(1000);
            }
        }
        return null;
    }

    private void decodeStream(BufferedInputStream in) throws IOException {
        MediaCodec codec = null;
        byte[] header = new byte[24]; // WBH1 header
        byte[] nalBuf = new byte[MAX_NAL];
        int    nalLen;

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

            while (running) {
                IoUtils.readFully(in, header, 24);
                if (!(header[0] == 'W' && header[1] == 'B' && header[2] == 'H' && header[3] == '1')) {
                    throw new IOException("bad magic");
                }
                nalLen = IoUtils.u32be(header, 20);
                if (nalLen <= 0 || nalLen > MAX_NAL) throw new IOException("bad len " + nalLen);
                IoUtils.readFully(in, nalBuf, nalLen);

                int nalType = nalBufLenientType(nalBuf, nalLen);
                if (nalType == 7) { sawSps = true; sps = Arrays.copyOf(nalBuf, nalLen); } // SPS
                if (nalType == 8) { sawPps = true; pps = Arrays.copyOf(nalBuf, nalLen); } // PPS

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
                    buf.clear();
                    buf.put(nalBuf, 0, nalLen);
                    int flags = (nalType == 5) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0; // IDR=5
                    codec.queueInputBuffer(idx, 0, nalLen, 0, flags);
                }

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outIdx;
                while ((outIdx = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    codec.releaseOutputBuffer(outIdx, true);
                }
            }
        } finally {
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
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

            if (sps != null) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            if (pps != null) fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps));

            codec.configure(fmt, surface, null, 0);
            codec.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "configureWithCsd failed", e);
            status.set("H264: configure fail " + e.getMessage());
            return false;
        }
    }

    private void queueConfig(MediaCodec codec, byte[] data, boolean sps) {
        if (data == null) return;
        int idx = codec.dequeueInputBuffer(5_000);
        if (idx < 0) return;
        ByteBuffer buf = inBuf(codec, idx);
        buf.clear();
        buf.put(data, 0, data.length);
        int flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG | (sps ? 0 : 0);
        codec.queueInputBuffer(idx, 0, data.length, 0, flags);
    }

    private static ByteBuffer inBuf(MediaCodec codec, int idx) {
        if (Build.VERSION.SDK_INT >= 21) {
            return codec.getInputBuffer(idx);
        }
        return codec.getInputBuffers()[idx];
    }

    private static int nalBufLenientType(byte[] buf, int len) {
        // Find first non-zero after optional 3/4-byte start code
        int i = 0;
        while (i < len && buf[i] == 0) i++;
        if (i >= len) return -1;
        int nalHeader = buf[i] & 0x1F;
        return nalHeader;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
