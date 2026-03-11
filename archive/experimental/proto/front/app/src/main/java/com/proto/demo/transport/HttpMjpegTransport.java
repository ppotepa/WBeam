package com.proto.demo.transport;

import android.os.Process;

import com.proto.demo.config.StreamConfig;
import com.proto.demo.ui.StatusUpdater;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Single responsibility: pull an HTTP MJPEG multipart stream and deliver
 * each JPEG part as a frame to a {@link FrameListener}.
 */
public class HttpMjpegTransport implements Transport {

    private final FrameListener  listener;
    private final StatusUpdater  status;
    private final String         hostIp;
    private final int            maxFrameBytes;
    private volatile boolean     running = true;

    public HttpMjpegTransport(FrameListener listener, StatusUpdater status, String hostIp) {
        this.listener      = listener;
        this.status        = status;
        this.hostIp        = hostIp;
        this.maxFrameBytes = StreamConfig.MAX_FRAME_BYTES;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        while (running && !Thread.currentThread().isInterrupted()) {
            String url = "http://" + hostIp + ":" + StreamConfig.HTTP_PORT + "/mjpeg";
            status.set("HTTP: connecting to " + url);
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(10000);
                conn.connect();
                String boundary = extractBoundary(conn.getContentType());
                status.set("HTTP: streaming from " + hostIp);
                receiveMjpeg(new BufferedInputStream(conn.getInputStream(), 128 * 1024), boundary);
                conn.disconnect();
            } catch (Exception e) {
                if (running) {
                    status.set("HTTP: " + e.getMessage() + " — retrying…");
                    sleep(1000);
                }
            }
        }
    }

    // ── MJPEG multipart parser ────────────────────────────────────────────────
    private void receiveMjpeg(InputStream in, String boundary) throws IOException {
        byte[] buf = new byte[maxFrameBytes];
        while (running && !Thread.currentThread().isInterrupted()) {
            // Skip to next part boundary line
            String line;
            do {
                line = IoUtils.readLine(in);
                if (line == null) return;
            } while (!line.startsWith("--"));

            // Read part headers
            int len = -1;
            for (;;) {
                String h = IoUtils.readLine(in);
                if (h == null) return;
                if (h.isEmpty()) break;
                String lower = h.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try { len = Integer.parseInt(lower.substring(15).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }

            if (len > 0 && len <= maxFrameBytes) {
                if (buf.length < len) buf = new byte[len];
                IoUtils.readFully(in, buf, len);
                listener.onFrame(buf, len);
            }
        }
    }

    private static String extractBoundary(String contentType) {
        if (contentType == null) return "--myboundary";
        for (String part : contentType.split(";")) {
            String s = part.trim();
            if (s.startsWith("boundary=")) return "--" + s.substring(9).trim();
        }
        return "--myboundary";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
