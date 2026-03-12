package com.wbeam.stream;

final class StreamBufferMath {

    private StreamBufferMath() {
    }

    static int computeFrameBufferBudget(long frameUs, int minFrames, int maxFrames) {
        long safeFrameUs = Math.max(1L, frameUs);
        long fps = Math.max(1L, 1_000_000L / safeFrameUs);
        long budget = (fps + 9L) / 10L;
        return (int) Math.max(minFrames, Math.min(maxFrames, budget));
    }

    static int computeSeqGapBudget(long frameUs, boolean isUltraMode) {
        long safeFrameUs = Math.max(1L, frameUs);
        long fps = Math.max(1L, 1_000_000L / safeFrameUs);
        long base = isUltraMode ? Math.max(30L, fps / 2L) : fps;
        return (int) Math.max(60L, Math.min(240L, base));
    }

    static double percentileMs(long[] buf, int n, double q, long[] scratch) {
        if (n <= 0) {
            return 0.0;
        }
        System.arraycopy(buf, 0, scratch, 0, n);
        int idx = (int) Math.min(n - 1, Math.max(0, (int) (n * q)));
        long selected = selectKth(scratch, n, idx);
        return selected / 1_000_000.0;
    }

    static double estimateE2eLatencyMs(long presentedPtsUs) {
        if (presentedPtsUs <= 0L) {
            return 0.0;
        }
        long nowUs = System.currentTimeMillis() * 1000L;
        long lagUs = nowUs - presentedPtsUs;
        if (lagUs <= 0L) {
            return 0.0;
        }
        return lagUs / 1000.0;
    }

    static int estimateTransportDepthFrames(int streamLen, int avgNalSize) {
        int denom = Math.max(512, avgNalSize);
        if (streamLen <= 0) {
            return 0;
        }
        return Math.min(8, streamLen / denom);
    }

    private static long selectKth(long[] values, int n, int k) {
        int left = 0;
        int right = n - 1;
        while (left < right) {
            int pivotIndex = partition(values, left, right, (left + right) >>> 1);
            if (k == pivotIndex) {
                return values[k];
            }
            if (k < pivotIndex) {
                right = pivotIndex - 1;
            } else {
                left = pivotIndex + 1;
            }
        }
        return values[left];
    }

    private static int partition(long[] values, int left, int right, int pivotIndex) {
        long pivotValue = values[pivotIndex];
        swap(values, pivotIndex, right);
        int store = left;
        for (int i = left; i < right; i++) {
            if (values[i] < pivotValue) {
                swap(values, store, i);
                store++;
            }
        }
        swap(values, right, store);
        return store;
    }

    private static void swap(long[] values, int i, int j) {
        long t = values[i];
        values[i] = values[j];
        values[j] = t;
    }
}
