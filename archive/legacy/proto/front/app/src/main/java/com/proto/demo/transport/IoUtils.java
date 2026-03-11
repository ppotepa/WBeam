package com.proto.demo.transport;

import java.io.IOException;
import java.io.InputStream;

/**
 * Low-level I/O helpers shared by transport implementations.
 * Single responsibility: stream-reading utilities (no state, no threads).
 */
public final class IoUtils {

    /** Read exactly {@code len} bytes into {@code buf[0..len-1]}, blocking until done. */
    public static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("EOF after " + off + "/" + len + " bytes");
            off += n;
        }
    }

    /** Read one CRLF/LF-terminated line from the stream; returns null on EOF. */
    public static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return sb.toString().trim();
            if (b != '\r') sb.append((char) b);
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    /** Decode an unsigned 32-bit big-endian integer at offset {@code off} in {@code b}. */
    public static int u32be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
             | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8)
             |  (b[off + 3] & 0xFF);
    }

    private IoUtils() { /* utility class */ }
}
