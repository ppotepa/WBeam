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
import java.util.Comparator;
import java.util.Locale;
import java.util.PriorityQueue;

/**
 * H264 over WBH1 framing → MediaCodec Surface decode.
 * Minimal latency path; assumes Annex B byte-stream with SPS/PPS present.
 */
public class H264Transport implements Transport {

    private static final String TAG = "WBeamH264";
    private static final int    PORT = StreamConfig.ADB_PORT;
    private static final int    MAX_NAL = 2 * 1024 * 1024; // guard
    private static final int    MAX_REORDER_FRAMES = 5;
    private static final long   REORDER_WAIT_MS = 4;
    private static final long   STAT_INTERVAL_MS = 2_000;

    private final StatusUpdater status;
    private final Surface       surface;
    private final String        sizeHint; // e.g., "1280x800"
    private final boolean       useReorder;

    private volatile boolean running = true;

    private static final class EncodedFrame {
        final long   seq;
        final long   tsMs;
        final byte[] data;
        final int    len;
        final int    nalType;

        EncodedFrame(long seq, long tsMs, byte[] data, int len, int nalType) {
            this.seq = seq;
            this.tsMs = tsMs;
            this.data = data;
            this.len = len;
            this.nalType = nalType;
        }
    }

    public H264Transport(StatusUpdater status, Surface surface, String sizeHint, boolean useReorder) {
        this.status   = status;
        this.surface  = surface;
        this.sizeHint = sizeHint;
        this.useReorder = useReorder;
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
                    // Keep socket blocking; portal/source stalls should not force reconnect storms.
                    client.setSoTimeout(0);
                    status.set("H264: client connected");
                    decodeStream(new BufferedInputStream(client.getInputStream(), 512 * 1024));
                } catch (IOException e) {
                    if (running) status.set("H264: connection lost (" + e.getMessage() + ")");
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

    private void decodeStream(final BufferedInputStream in) throws IOException {
        if (useReorder) {
            decodeStreamReorder(in);
            return;
        }
        decodeStreamDirect(in);
    }

    // Direct decode path (no jitter buffer) - lowest latency and best for weak API17 devices.
    private void decodeStreamDirect(final BufferedInputStream in) throws IOException {
        MediaCodec codec = null;
        byte[] header = new byte[24]; // WBH1 header
        byte[] nalBuf = new byte[MAX_NAL];

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
            long decodedFrames = 0;
            long dropCount = 0;
            long statStart = System.currentTimeMillis();

            while (running && !Thread.currentThread().isInterrupted()) {
                IoUtils.readFully(in, header, 24);
                if (!(header[0] == 'W' && header[1] == 'B' && header[2] == 'H' && header[3] == '1')) {
                    throw new IOException("bad magic");
                }
                long tsMs = u64be(header, 12);
                int nalLen = IoUtils.u32be(header, 20);
                if (nalLen <= 0 || nalLen > MAX_NAL) throw new IOException("bad len " + nalLen);
                IoUtils.readFully(in, nalBuf, nalLen);

                int nalHdrOff = findNalHeaderOffset(nalBuf, nalLen);
                int nalType = (nalHdrOff >= 0 && nalHdrOff < nalLen) ? (nalBuf[nalHdrOff] & 0x1F) : -1;
                if (nalType == 7 && nalHdrOff >= 0) { sawSps = true; sps = Arrays.copyOfRange(nalBuf, nalHdrOff, nalLen); } // SPS
                if (nalType == 8 && nalHdrOff >= 0) { sawPps = true; pps = Arrays.copyOfRange(nalBuf, nalHdrOff, nalLen); } // PPS

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
                if (idx < 0) {
                    dropCount++;
                    drainOutput(codec);
                    continue;
                }
                ByteBuffer buf = inBuf(codec, idx);
                if (buf == null) {
                    dropCount++;
                    continue;
                }
                buf.clear();
                buf.put(nalBuf, 0, nalLen);
                int flags = (nalType == 5) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0; // IDR=5
                codec.queueInputBuffer(idx, 0, nalLen, tsMs * 1000L, flags);

                drainOutput(codec);
                decodedFrames++;

                long now = System.currentTimeMillis();
                if (now - statStart >= STAT_INTERVAL_MS) {
                    double fps = decodedFrames * 1000.0 / (now - statStart);
                    status.set(String.format(Locale.US, "H264 %.1f fps drop=%d", fps, dropCount));
                    decodedFrames = 0;
                    dropCount = 0;
                    statStart = now;
                }
            }
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignore) {}
                try { codec.release(); } catch (Exception ignore) {}
            }
        }
    }

    // Optional jitter-buffer path for out-of-order mitigation (higher CPU/latency).
    private void decodeStreamReorder(final BufferedInputStream in) throws IOException {
        MediaCodec codec = null;
        final Object queueLock = new Object();
        final PriorityQueue<EncodedFrame> reorder = new PriorityQueue<>(
            MAX_REORDER_FRAMES + 2,
            new Comparator<EncodedFrame>() {
                @Override
                public int compare(EncodedFrame a, EncodedFrame b) {
                    if (a.seq < b.seq) return -1;
                    if (a.seq > b.seq) return 1;
                    if (a.tsMs < b.tsMs) return -1;
                    if (a.tsMs > b.tsMs) return 1;
                    return 0;
                }
            });
        final boolean[] rxDone = new boolean[]{false};
        final IOException[] rxErr = new IOException[1];
        final long[] reorderDrops = new long[]{0};

        Thread rxThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] header = new byte[24]; // WBH1 header
                byte[] nalBuf = new byte[MAX_NAL];

                try {
                    while (running && !Thread.currentThread().isInterrupted()) {
                        IoUtils.readFully(in, header, 24);
                        if (!(header[0] == 'W' && header[1] == 'B' && header[2] == 'H' && header[3] == '1')) {
                            throw new IOException("bad magic");
                        }
                        long seq = u64be(header, 4);
                        long tsMs = u64be(header, 12);
                        int nalLen = IoUtils.u32be(header, 20);
                        if (nalLen <= 0 || nalLen > MAX_NAL) throw new IOException("bad len " + nalLen);
                        IoUtils.readFully(in, nalBuf, nalLen);

                        int nalHdrOff = findNalHeaderOffset(nalBuf, nalLen);
                        int nalType = (nalHdrOff >= 0 && nalHdrOff < nalLen) ? (nalBuf[nalHdrOff] & 0x1F) : -1;
                        byte[] payload = Arrays.copyOf(nalBuf, nalLen);

                        synchronized (queueLock) {
                            if (reorder.size() >= MAX_REORDER_FRAMES) {
                                reorder.poll();
                                reorderDrops[0]++;
                            }
                            reorder.offer(new EncodedFrame(seq, tsMs, payload, nalLen, nalType));
                            queueLock.notifyAll();
                        }
                    }
                } catch (IOException e) {
                    rxErr[0] = e;
                } finally {
                    synchronized (queueLock) {
                        rxDone[0] = true;
                        queueLock.notifyAll();
                    }
                }
            }
        }, "wbeam-h264-rx");
        rxThread.start();

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
            long nextSeq = -1;
            long decodedFrames = 0;
            long statStart = System.currentTimeMillis();

            while (running && !Thread.currentThread().isInterrupted()) {
                EncodedFrame frame = null;
                synchronized (queueLock) {
                    while (running && reorder.isEmpty() && !rxDone[0]) {
                        waitNoThrow(queueLock, REORDER_WAIT_MS);
                    }
                    if (!reorder.isEmpty()) {
                        EncodedFrame head = reorder.peek();
                        if (nextSeq < 0) nextSeq = head.seq;
                        if (head.seq == nextSeq || reorder.size() >= MAX_REORDER_FRAMES || rxDone[0]) {
                            frame = reorder.poll();
                            if (frame != null) {
                                if (frame.seq > nextSeq) {
                                    reorderDrops[0] += (frame.seq - nextSeq);
                                }
                                nextSeq = frame.seq + 1;
                            }
                        }
                    }
                }

                if (frame == null) {
                    if (rxDone[0]) break;
                    continue;
                }

                int nalHdrOff = findNalHeaderOffset(frame.data, frame.len);
                int nalType = frame.nalType;
                if (nalType == 7 && nalHdrOff >= 0) { sawSps = true; sps = Arrays.copyOfRange(frame.data, nalHdrOff, frame.len); } // SPS
                if (nalType == 8 && nalHdrOff >= 0) { sawPps = true; pps = Arrays.copyOfRange(frame.data, nalHdrOff, frame.len); } // PPS

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
                    if (buf != null) {
                        buf.clear();
                        buf.put(frame.data, 0, frame.len);
                        int flags = (nalType == 5) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0; // IDR=5
                        codec.queueInputBuffer(idx, 0, frame.len, frame.tsMs * 1000L, flags);
                    }
                }

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outIdx;
                while ((outIdx = codec.dequeueOutputBuffer(info, 0)) >= 0) {
                    codec.releaseOutputBuffer(outIdx, true);
                }

                decodedFrames++;
                long now = System.currentTimeMillis();
                if (now - statStart >= STAT_INTERVAL_MS) {
                    double fps = decodedFrames * 1000.0 / (now - statStart);
                    long drops;
                    synchronized (queueLock) {
                        drops = reorderDrops[0];
                        reorderDrops[0] = 0;
                    }
                    status.set(String.format(Locale.US, "H264 %.1f fps drop=%d", fps, drops));
                    decodedFrames = 0;
                    statStart = now;
                }
            }

            if (rxErr[0] != null) {
                String msg = rxErr[0].getMessage();
                if (msg != null && !msg.contains("EOF")) {
                    throw rxErr[0];
                }
            }
        } finally {
            try {
                rxThread.interrupt();
                rxThread.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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

    private static void drainOutput(MediaCodec codec) {
        if (codec == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outIdx;
        while ((outIdx = codec.dequeueOutputBuffer(info, 0)) >= 0) {
            codec.releaseOutputBuffer(outIdx, true);
        }
    }

    private static int findNalHeaderOffset(byte[] buf, int len) {
        if (buf == null || len <= 0) return -1;
        // Annex B (00 00 01 or 00 00 00 01)
        for (int i = 0; i + 4 < len; i++) {
            if (buf[i] == 0 && buf[i + 1] == 0) {
                if (buf[i + 2] == 1) return i + 3;
                if (buf[i + 2] == 0 && buf[i + 3] == 1) return i + 4;
            }
        }
        // Fallback: assume buffer begins at NAL header.
        return 0;
    }

    private static long u64be(byte[] src, int off) {
        return ((long) (src[off]     & 0xFF) << 56)
            | ((long) (src[off + 1] & 0xFF) << 48)
            | ((long) (src[off + 2] & 0xFF) << 40)
            | ((long) (src[off + 3] & 0xFF) << 32)
            | ((long) (src[off + 4] & 0xFF) << 24)
            | ((long) (src[off + 5] & 0xFF) << 16)
            | ((long) (src[off + 6] & 0xFF) << 8)
            | ((long) (src[off + 7] & 0xFF));
    }

    private static void waitNoThrow(Object lock, long ms) {
        try {
            lock.wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
