package com.wbeam.stream;

import java.util.Arrays;

final class StreamNalUtils {
    private StreamNalUtils() {}

    static int findStartCode(byte[] data, int from, int toExclusive) {
        int limit = toExclusive - 3;
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) return i;
                if (i + 3 < toExclusive && data[i + 2] == 0 && data[i + 3] == 1) return i;
            }
        }
        return -1;
    }

    static boolean isRecoveryNal(byte[] data, int offset, int size) {
        int type = firstNalType(data, offset, size);
        return type == 5 || type == 7 || type == 8; // IDR/SPS/PPS
    }

    @SuppressWarnings({"java:S3776", "java:S135"})
    static boolean containsRecoveryNal(byte[] data, int size, boolean isHevc, int scanLimit) {
        int limit = Math.min(size, scanLimit);
        int i = 0;
        while (i < limit - 3) {
            if (data[i] != 0 || data[i + 1] != 0) { i++; continue; }
            final int nalByte;
            if (data[i + 2] == 1) {
                nalByte = i + 3;
            } else if (i + 3 < limit && data[i + 2] == 0 && data[i + 3] == 1) {
                nalByte = i + 4;
            } else { i++; continue; }
            if (nalByte >= limit) break;
            final int type = isHevc
                    ? ((data[nalByte] & 0x7E) >> 1)   // HEVC: bits[6:1] of first header byte
                    : (data[nalByte] & 0x1F);         // H.264: bits[4:0]
            if (isHevc
                    ? (type == 19 || type == 20 || type == 21 || type == 32 || type == 33 || type == 34)
                    : (type == 5  || type == 7  || type == 8))
                return true;
            i = nalByte + 1;
        }
        return false;
    }

    @SuppressWarnings("java:S3776")
    static AvcCsd extractAvcCsd(byte[] data, int size) {
        byte[] sps = null;
        byte[] pps = null;
        int start = findStartCode(data, 0, size);
        if (start < 0) {
            if (size > 0) {
                int type = data[0] & 0x1F;
                if (type == 7) sps = Arrays.copyOf(data, size);
                if (type == 8) pps = Arrays.copyOf(data, size);
            }
            return new AvcCsd(sps, pps);
        }

        while (start >= 0 && start < size) {
            int nalHdrOff = (start + 2 < size && data[start + 2] == 1) ? (start + 3) : (start + 4);
            int next = findStartCode(data, nalHdrOff + 1, size);
            if (next < 0) next = size;
            int nalType = data[nalHdrOff] & 0x1F;
            if (nalType == 7 && sps == null) sps = Arrays.copyOfRange(data, nalHdrOff, next);
            if (nalType == 8 && pps == null) pps = Arrays.copyOfRange(data, nalHdrOff, next);
            if (sps != null && pps != null) break;
            start = next;
        }
        return new AvcCsd(sps, pps);
    }

    private static int firstNalType(byte[] data, int offset, int size) {
        if (size <= 0 || offset < 0 || offset >= data.length) return -1;
        int end = Math.min(data.length, offset + size);
        int i = offset;
        if (i + 3 < end && data[i] == 0 && data[i + 1] == 0) {
            if (data[i + 2] == 1)
                i += 3;
            else if (i + 4 < end && data[i + 2] == 0 && data[i + 3] == 1)
                i += 4;
        }
        if (i >= end) return -1;
        return data[i] & 0x1F;
    }

    static final class AvcCsd {
        final byte[] sps;
        final byte[] pps;

        AvcCsd(byte[] sps, byte[] pps) {
            this.sps = sps;
            this.pps = pps;
        }
    }
}
