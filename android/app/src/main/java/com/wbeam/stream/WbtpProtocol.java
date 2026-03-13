package com.wbeam.stream;

import java.io.IOException;
import java.io.InputStream;

final class WbtpProtocol {

    private WbtpProtocol() {
    }

    static Hello readHello(InputStream input, byte[] helloBuf, int helloMagic, byte helloVersion, int helloHeaderSize)
            throws IOException {
        WbtpFrameIo.readFully(input, helloBuf, helloHeaderSize);
        int magic = ((helloBuf[0] & 0xFF) << 24)
                | ((helloBuf[1] & 0xFF) << 16)
                | ((helloBuf[2] & 0xFF) << 8)
                | (helloBuf[3] & 0xFF);
        int helloLen = ((helloBuf[6] & 0xFF) << 8) | (helloBuf[7] & 0xFF);
        if (magic != helloMagic || helloBuf[4] != helloVersion || helloLen != helloHeaderSize) {
            throw new IOException("WBTP: bad stream hello magic/version/len");
        }
        int flags = helloBuf[5] & 0xFF;
        long sessionId = ((helloBuf[8] & 0xFFL) << 56)
                | ((helloBuf[9] & 0xFFL) << 48)
                | ((helloBuf[10] & 0xFFL) << 40)
                | ((helloBuf[11] & 0xFFL) << 32)
                | ((helloBuf[12] & 0xFFL) << 24)
                | ((helloBuf[13] & 0xFFL) << 16)
                | ((helloBuf[14] & 0xFFL) << 8)
                | (helloBuf[15] & 0xFFL);
        return new Hello(flags, sessionId);
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

        Hello(int flags, long sessionId) {
            this.flags = flags;
            this.sessionId = sessionId;
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
}
