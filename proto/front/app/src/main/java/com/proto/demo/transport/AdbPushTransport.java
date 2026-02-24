package com.proto.demo.transport;

import android.os.Process;

import com.proto.demo.config.StreamConfig;
import com.proto.demo.ui.StatusUpdater;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private final FrameListener  listener;
    private final StatusUpdater  status;
    private final int            port;
    private final int            maxFrameBytes;
    private volatile boolean     running = true;

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
            if (running) status.set("ADB: error — " + e.getMessage());
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
                status.set("ADB: port busy (" + secsLeft + "s) " + e.getMessage());
                sleep(1000);
            }
        }
        return null;
    }

    // ── WBJ1 frame parser ─────────────────────────────────────────────────────
    private void receiveWbj1(InputStream in) throws IOException {
        byte[] magic  = new byte[4];
        byte[] header = new byte[20]; // seq(8) + ts_ms(8) + len(4)
        byte[] buf    = new byte[maxFrameBytes];

        while (running && !Thread.currentThread().isInterrupted()) {
            IoUtils.readFully(in, magic, 4);
            if (!(magic[0] == 'W' && magic[1] == 'B' && magic[3] == '1')) {
                throw new IOException("bad magic: "
                    + (char) magic[0] + (char) magic[1]
                    + (char) magic[2] + (char) magic[3]);
            }
            IoUtils.readFully(in, header, 20);
            int len = IoUtils.u32be(header, 16);
            if (len <= 0 || len > maxFrameBytes)
                throw new IOException("bad len=" + len);

            if (buf.length < len) buf = new byte[len];
            IoUtils.readFully(in, buf, len);

            listener.onFrame(buf, len);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
