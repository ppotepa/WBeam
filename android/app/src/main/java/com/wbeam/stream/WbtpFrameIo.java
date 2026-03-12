package com.wbeam.stream;

import java.io.IOException;
import java.io.InputStream;

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

    static boolean tryResyncHeader(
            InputStream input,
            byte[] hdrBuf,
            int frameHeaderSize,
            int frameMagic,
            int scanLimit
    ) throws IOException {
        int scanned = 0;
        while (scanned < scanLimit) {
            System.arraycopy(hdrBuf, 1, hdrBuf, 0, frameHeaderSize - 1);
            int n = input.read(hdrBuf, frameHeaderSize - 1, 1);
            if (n < 0) {
                return false;
            }
            scanned++;
            if (parseFrameMagic(hdrBuf) == frameMagic) {
                return true;
            }
        }
        return false;
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
