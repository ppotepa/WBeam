package com.wbeam.stream;

import android.util.Log;

import java.io.IOException;

final class WbtpPayloadBuffer {

    private WbtpPayloadBuffer() {
    }

    static void validatePayloadLength(int payloadLen, int hardCap) throws IOException {
        if (payloadLen <= 0 || payloadLen > hardCap) {
            throw new IOException("WBTP: bad payload length " + payloadLen);
        }
    }

    static byte[] ensureCapacity(
            byte[] payloadBuf,
            int payloadLen,
            int hardCap,
            String tag,
            String growMessage
    ) throws IOException {
        if (payloadLen <= payloadBuf.length) {
            return payloadBuf;
        }
        int newCap = Math.min(hardCap, WbtpFrameIo.nextPowerOfTwo(payloadLen));
        if (newCap < payloadLen) {
            throw new IOException("WBTP: payload exceeds dynamic cap " + payloadLen);
        }
        Log.w(tag, growMessage + payloadBuf.length + " -> " + newCap);
        return new byte[newCap];
    }
}
