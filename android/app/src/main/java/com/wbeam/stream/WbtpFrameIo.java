package com.wbeam.stream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

final class WbtpFrameIo {

    private WbtpFrameIo() {
    }

    static int parseFrameMagic(byte[] hdrBuf) {
        return ((hdrBuf[0] & 0xFF) << 24)
                | ((hdrBuf[1] & 0xFF) << 16)
                | ((hdrBuf[2] & 0xFF) << 8)
                | (hdrBuf[3] & 0xFF);
    }

    static void readFully(InputStream input, byte[] buf, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = input.read(buf, read, len - read);
            if (n < 0) {
                throw new IOException("stream closed");
            }
            read += n;
        }
    }

    static void readFully(InputStream input, byte[] buf, int offset, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = input.read(buf, offset + read, len - read);
            if (n < 0) {
                throw new IOException("stream closed");
            }
            read += n;
        }
    }

    static boolean tryResyncHeader(
            InputStream input,
            byte[] hdrBuf,
            int frameHeaderSize,
            int frameMagic,
            int scanLimit
    ) throws IOException {
        byte[] ring = Arrays.copyOf(hdrBuf, frameHeaderSize);
        int head = 0;
        int scanned = 0;
        while (scanned < scanLimit) {
            int n = input.read();
            if (n < 0) {
                return false;
            }
            ring[(head + frameHeaderSize - 1) % frameHeaderSize] = (byte) n;
            head = (head + 1) % frameHeaderSize;
            scanned++;
            if (parseFrameMagic(ring, head) == frameMagic) {
                unwrapRing(ring, head, hdrBuf);
                return true;
            }
        }
        return false;
    }

    static void skipFully(InputStream input, byte[] scratch, int len) throws IOException {
        int remaining = len;
        while (remaining > 0) {
            int chunk = Math.min(remaining, scratch.length);
            int read = input.read(scratch, 0, chunk);
            if (read < 0) {
                throw new IOException("stream closed");
            }
            remaining -= read;
        }
    }

    private static int parseFrameMagic(byte[] ring, int head) {
        return ((ring[head] & 0xFF) << 24)
                | ((ring[(head + 1) % ring.length] & 0xFF) << 16)
                | ((ring[(head + 2) % ring.length] & 0xFF) << 8)
                | (ring[(head + 3) % ring.length] & 0xFF);
    }

    private static void unwrapRing(byte[] ring, int head, byte[] hdrBuf) {
        int firstPart = ring.length - head;
        System.arraycopy(ring, head, hdrBuf, 0, firstPart);
        if (head > 0) {
            System.arraycopy(ring, 0, hdrBuf, firstPart, head);
        }
    }

    static int nextPowerOfTwo(int value) {
        if (value <= 1) {
            return 1;
        }
        if (value >= (1 << 30)) {
            return 1 << 30;
        }
        return Integer.highestOneBit(value - 1) << 1;
    }
}
