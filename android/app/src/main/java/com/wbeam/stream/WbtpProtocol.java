package com.wbeam.stream;

import java.io.IOException;
import java.io.InputStream;

@SuppressWarnings("java:S3776")
final class WbtpProtocol {

    private WbtpProtocol() {
    }

    /**
     * Read and validate the WBTP HELLO from the stream.
     *
     * <p>Supports both v1 (16-byte) and v2 (24-byte) hellos:
     * <ul>
     *   <li>v1: flags + sessionId only.</li>
     *   <li>v2: flags + sessionId + width + height + fps.</li>
     * </ul>
     *
     * <p>The caller must allocate {@code helloBuf} large enough for the maximum
     * supported hello size (use {@link #HELLO_BUF_SIZE}).
     */
    static final int HELLO_BUF_SIZE = 32; // headroom for future extensions

    static Hello readHello(InputStream input, byte[] helloBuf, int helloMagic, byte helloVersion, int helloHeaderSize)
            throws IOException {
        WbtpFrameIo.readFully(input, helloBuf, helloHeaderSize);
        int magic = parseMagic(helloBuf);
        int helloLen = parseHelloLength(helloBuf);
        if (magic != helloMagic) {
            throw new IOException("WBTP: bad stream hello magic");
        }
        if (helloBuf[4] < helloVersion) {
            throw new IOException(
                    "WBTP: server hello version " + (helloBuf[4] & 0xFF) + " < expected " + (helloVersion & 0xFF)
            );
        }
        if (helloLen < helloHeaderSize) {
            throw new IOException("WBTP: hello helloLen=" + helloLen + " < expected " + helloHeaderSize);
        }
        int flags = helloBuf[5] & 0xFF;
        long sessionId = parseSessionId(helloBuf);
        int extraBytes = helloLen - helloHeaderSize;
        HelloGeometry geometry = readHelloGeometry(input, helloBuf, helloHeaderSize, extraBytes);
        return new Hello(flags, sessionId, geometry.width, geometry.height, geometry.fps);
    }

    private static int parseMagic(byte[] helloBuf) {
        return ((helloBuf[0] & 0xFF) << 24)
                | ((helloBuf[1] & 0xFF) << 16)
                | ((helloBuf[2] & 0xFF) << 8)
                | (helloBuf[3] & 0xFF);
    }

    private static int parseHelloLength(byte[] helloBuf) {
        return ((helloBuf[6] & 0xFF) << 8) | (helloBuf[7] & 0xFF);
    }

    private static long parseSessionId(byte[] helloBuf) {
        return ((helloBuf[8] & 0xFFL) << 56)
                | ((helloBuf[9] & 0xFFL) << 48)
                | ((helloBuf[10] & 0xFFL) << 40)
                | ((helloBuf[11] & 0xFFL) << 32)
                | ((helloBuf[12] & 0xFFL) << 24)
                | ((helloBuf[13] & 0xFFL) << 16)
                | ((helloBuf[14] & 0xFFL) << 8)
                | (helloBuf[15] & 0xFFL);
    }

    private static HelloGeometry readHelloGeometry(
            InputStream input,
            byte[] helloBuf,
            int helloHeaderSize,
            int extraBytes
    ) throws IOException {
        if (extraBytes <= 0) {
            return new HelloGeometry(0, 0, 0);
        }
        int toRead = Math.min(extraBytes, helloBuf.length - helloHeaderSize);
        WbtpFrameIo.readFully(input, helloBuf, helloHeaderSize, toRead);
        drainExtendedHello(input, extraBytes - toRead);
        return parseHelloGeometry(helloBuf, helloHeaderSize, extraBytes);
    }

    private static void drainExtendedHello(InputStream input, int remaining) throws IOException {
        if (remaining <= 0) {
            return;
        }
        byte[] drain = new byte[Math.min(remaining, 256)];
        int drained = 0;
        while (drained < remaining) {
            int read = input.read(drain, 0, Math.min(drain.length, remaining - drained));
            if (read < 0) {
                throw new IOException("WBTP: EOF while draining extended hello");
            }
            drained += read;
        }
    }

    private static HelloGeometry parseHelloGeometry(byte[] helloBuf, int helloHeaderSize, int extraBytes) {
        if (helloHeaderSize + 6 > helloBuf.length || extraBytes < 6) {
            return new HelloGeometry(0, 0, 0);
        }
        int width = ((helloBuf[helloHeaderSize] & 0xFF) << 8)
                | (helloBuf[helloHeaderSize + 1] & 0xFF);
        int height = ((helloBuf[helloHeaderSize + 2] & 0xFF) << 8)
                | (helloBuf[helloHeaderSize + 3] & 0xFF);
        int fps = ((helloBuf[helloHeaderSize + 4] & 0xFF) << 8)
                | (helloBuf[helloHeaderSize + 5] & 0xFF);
        return new HelloGeometry(width, height, fps);
    }

    static FrameHeader readFrameHeader(
            InputStream input,
            byte[] hdrBuf,
            int frameHeaderSize,
            int frameMagic,
            int frameResyncScanLimit,
            int frameFlagKeyframe
    ) throws IOException {
        WbtpFrameIo.readFully(input, hdrBuf, frameHeaderSize);
        int magic = WbtpFrameIo.parseFrameMagic(hdrBuf);
        boolean resynced = false;
        if (magic != frameMagic) {
            resynced = WbtpFrameIo.tryResyncHeader(
                    input,
                    hdrBuf,
                    frameHeaderSize,
                    frameMagic,
                    frameResyncScanLimit
            );
            if (!resynced) {
                throw new IOException("WBTP: bad frame magic 0x" + Integer.toHexString(magic) + " – resync failed");
            }
        }
        boolean frameIsKey = (hdrBuf[5] & frameFlagKeyframe) != 0;
        long seqU32 = ((hdrBuf[6] & 0xFFL) << 24)
                | ((hdrBuf[7] & 0xFFL) << 16)
                | ((hdrBuf[8] & 0xFFL) << 8)
                | (hdrBuf[9] & 0xFFL);
        long ptsUs = ((hdrBuf[10] & 0xFFL) << 56)
                | ((hdrBuf[11] & 0xFFL) << 48)
                | ((hdrBuf[12] & 0xFFL) << 40)
                | ((hdrBuf[13] & 0xFFL) << 32)
                | ((hdrBuf[14] & 0xFFL) << 24)
                | ((hdrBuf[15] & 0xFFL) << 16)
                | ((hdrBuf[16] & 0xFFL) << 8)
                | (hdrBuf[17] & 0xFFL);
        int payloadLen = ((hdrBuf[18] & 0xFF) << 24)
                | ((hdrBuf[19] & 0xFF) << 16)
                | ((hdrBuf[20] & 0xFF) << 8)
                | (hdrBuf[21] & 0xFF);
        return new FrameHeader(frameIsKey, seqU32, ptsUs, payloadLen, resynced);
    }

    static final class Hello {
        final int flags;
        final long sessionId;
        /** Authoritative stream width from v2 Hello; 0 when not provided (fall back to UI config). */
        final int width;
        /** Authoritative stream height from v2 Hello; 0 when not provided (fall back to UI config). */
        final int height;
        /** Authoritative stream fps from v2 Hello; 0 when not provided. */
        final int fps;

        Hello(int flags, long sessionId, int width, int height, int fps) {
            this.flags = flags;
            this.sessionId = sessionId;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }

    static final class FrameHeader {
        final boolean frameIsKey;
        final long seqU32;
        final long ptsUs;
        final int payloadLen;
        final boolean resynced;

        FrameHeader(boolean frameIsKey, long seqU32, long ptsUs, int payloadLen, boolean resynced) {
            this.frameIsKey = frameIsKey;
            this.seqU32 = seqU32;
            this.ptsUs = ptsUs;
            this.payloadLen = payloadLen;
            this.resynced = resynced;
        }
    }

    private static final class HelloGeometry {
        final int width;
        final int height;
        final int fps;

        HelloGeometry(int width, int height, int fps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }
}
