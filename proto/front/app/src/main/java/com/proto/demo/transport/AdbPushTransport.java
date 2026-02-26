package com.proto.demo.transport;

import android.os.Process;
import android.util.Log;

import com.proto.demo.config.StreamConfig;
import com.proto.demo.ui.StatusUpdater;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Single responsibility: accept TCP connections on the ADB-forwarded port,
 * parse WBJ1-framed JPEG payloads, and deliver frames to a {@link FrameListener}.
 *
 * Protocol frame layout (big-endian):
 *   magic(4)  — "WBJ1" (JPEG) or "WBH1" (H264 NAL)
 *   seq(8)    — frame sequence number
 *   ts_ms(8)  — capture timestamp (ms)
 *   len(4)    — payload length in bytes
 *   payload   — JPEG data (len bytes)
 */
public class AdbPushTransport implements Transport {

    private static final String TAG = "WBeam";
    // Small async stack to preserve frame order under short jitter/reconnect bursts.
    private static final int  MAX_REORDER_FRAMES   = 8;
    private static final int  MIN_REORDER_BUFFER   = 3;
    private static final long REORDER_WAIT_MS      = 35L;

    private final FrameListener  listener;
    private final StatusUpdater  status;
    private final int            port;
    private final int            maxFrameBytes;
    private volatile boolean     running = true;

    private static final class QueuedFrame {
        final long seq;
        final long tsMs;
        final byte[] data;
        final int len;
        final long queuedAtMs;

        QueuedFrame(long seq, long tsMs, byte[] data, int len, long queuedAtMs) {
            this.seq = seq;
            this.tsMs = tsMs;
            this.data = data;
            this.len = len;
            this.queuedAtMs = queuedAtMs;
        }
    }

    public AdbPushTransport(FrameListener listener, StatusUpdater status) {
        this.listener      = listener;
        this.status        = status;
        this.port          = StreamConfig.ADB_PORT;
        this.maxFrameBytes = StreamConfig.MAX_FRAME_BYTES;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        status.set("ADB: listening on :" + port);

        ServerSocket ss = bindWithRetry();
        if (ss == null) {
            status.set("ADB: could not bind port " + port);
            return;
        }

        try (ServerSocket server = ss) {
            while (running && !Thread.currentThread().isInterrupted()) {
                Socket client;
                try {
                    client = server.accept();
                } catch (IOException e) {
                    if (running) sleep(100);
                    continue;
                }
                status.set("ADB: client connected — streaming");
                try {
                    client.setTcpNoDelay(true);
                    // Large kernel receive buffer: keeps the pipe full across JVM GC pauses
                    client.setReceiveBufferSize(1 * 1024 * 1024);
                    // Safety timeout: unblock if host disappears without TCP RST
                    client.setSoTimeout(5_000);
                    receiveWbj1(new BufferedInputStream(client.getInputStream(), 512 * 1024));
                } catch (IOException e) {
                    if (running) status.set("ADB: lost connection — waiting…");
                } finally {
                    try { client.close(); } catch (IOException ignored) {}
                }
                sleep(150);
            }
        } catch (IOException e) {
            if (running) status.set("ADB: error — " + describeError(e));
        }
    }

    // ── Bind with retry ───────────────────────────────────────────────────────
    // Previous process may hold the port in TIME_WAIT/CLOSE_WAIT for up to 70 s.
    private ServerSocket bindWithRetry() {
        for (int attempt = 0; attempt < 70 && running; attempt++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                // Hint the kernel to keep a large accept backlog buffer
                ss.setReceiveBufferSize(1 * 1024 * 1024);
                ss.bind(new InetSocketAddress(port));
                return ss;
            } catch (IOException e) {
                int secsLeft = 70 - attempt;
                status.set("ADB: port busy (" + secsLeft + "s) " + describeError(e));
                sleep(1000);
            }
        }
        return null;
    }

    // ── WBJ1 frame parser ─────────────────────────────────────────────────────
    private void receiveWbj1(InputStream in) throws IOException {
        byte[] magic  = new byte[4];
        byte[] header = new byte[20]; // seq(8) + ts_ms(8) + len(4)
        PriorityQueue<QueuedFrame> reorder = new PriorityQueue<>(
            MAX_REORDER_FRAMES,
            new Comparator<QueuedFrame>() {
                @Override
                public int compare(QueuedFrame a, QueuedFrame b) {
                    if (a.seq < b.seq) return -1;
                    if (a.seq > b.seq) return 1;
                    if (a.tsMs < b.tsMs) return -1;
                    if (a.tsMs > b.tsMs) return 1;
                    return 0;
                }
            }
        );
        long nextSeq = -1L;
        long reorderDrops = 0L;
        long lastStatMs = System.currentTimeMillis();

        while (running && !Thread.currentThread().isInterrupted()) {
            IoUtils.readFully(in, magic, 4);
            if (!(magic[0] == 'W' && magic[1] == 'B' && magic[3] == '1')) {
                throw new IOException("bad magic: "
                    + (char) magic[0] + (char) magic[1]
                    + (char) magic[2] + (char) magic[3]);
            }
            IoUtils.readFully(in, header, 20);
            long seq  = u64be(header, 0);
            long tsMs = u64be(header, 8);
            int len = IoUtils.u32be(header, 16);
            if (len <= 0 || len > maxFrameBytes)
                throw new IOException("bad len=" + len);

            byte[] buf = new byte[len];
            IoUtils.readFully(in, buf, len);
            enqueueFrame(reorder, seq, tsMs, buf, len);
            if (reorder.size() > MAX_REORDER_FRAMES) {
                reorder.poll();
                reorderDrops++;
            }
            if (nextSeq < 0 && !reorder.isEmpty()) {
                nextSeq = reorder.peek().seq;
            }
            nextSeq = drainOrderedFrames(reorder, nextSeq);

            long now = System.currentTimeMillis();
            if (now - lastStatMs >= 2000) {
                Log.d(TAG, "ADB reorder queue=" + reorder.size() + " drop=" + reorderDrops);
                lastStatMs = now;
                reorderDrops = 0;
            }
        }

        // Flush whatever is left on shutdown/disconnect to avoid losing tail frames.
        while (!reorder.isEmpty()) {
            QueuedFrame frame = reorder.poll();
            listener.onFrame(frame.data, frame.len);
        }
    }

    private static void enqueueFrame(PriorityQueue<QueuedFrame> reorder, long seq, long tsMs, byte[] data, int len) {
        reorder.offer(new QueuedFrame(seq, tsMs, data, len, System.currentTimeMillis()));
    }

    private long drainOrderedFrames(PriorityQueue<QueuedFrame> reorder, long nextSeq) {
        long now = System.currentTimeMillis();
        while (!reorder.isEmpty()) {
            QueuedFrame head = reorder.peek();
            boolean inOrder = nextSeq < 0 || head.seq == nextSeq;
            boolean backlogPressure = reorder.size() >= MIN_REORDER_BUFFER;
            boolean aged = now - head.queuedAtMs >= REORDER_WAIT_MS;
            if (!inOrder && !(backlogPressure && aged)) {
                break;
            }
            QueuedFrame frame = reorder.poll();
            listener.onFrame(frame.data, frame.len);
            nextSeq = frame.seq + 1;
        }
        return nextSeq;
    }

    private static long u64be(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 56)
            | ((long) (b[off + 1] & 0xFF) << 48)
            | ((long) (b[off + 2] & 0xFF) << 40)
            | ((long) (b[off + 3] & 0xFF) << 32)
            | ((long) (b[off + 4] & 0xFF) << 24)
            | ((long) (b[off + 5] & 0xFF) << 16)
            | ((long) (b[off + 6] & 0xFF) << 8)
            | ((long) (b[off + 7] & 0xFF));
        }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describeError(Throwable err) {
        if (err == null) return "unknown";
        String msg = err.getMessage();
        if (msg != null) {
            msg = msg.trim();
            if (!msg.isEmpty()) return msg;
        }
        String name = err.getClass().getSimpleName();
        return name.isEmpty() ? "unknown" : name;
    }
}
