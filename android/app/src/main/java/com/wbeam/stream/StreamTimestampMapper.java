package com.wbeam.stream;

/** Maps opaque sender PTS to a bounded local monotonic playback timeline. */
final class StreamTimestampMapper {
    private static final long MAX_STEP_US = 10_000_000L;
    private static final long MAX_DRIFT_US = 2_000_000L;
    private long previousSourceUs = -1L;
    private long previousLocalUs = -1L;

    long map(long sourceUs, long receivedMonotonicUs) {
        long nowUs = Math.max(0L, receivedMonotonicUs);
        long mappedUs = nowUs;
        // Source time may be relative (GStreamer), epoch-based (older senders),
        // reset after capture restarts, or an unsigned value outside Java's range.
        // Only small forward deltas are useful; never compare it with wall time.
        if (sourceUs >= 0L && previousSourceUs >= 0L && sourceUs >= previousSourceUs) {
            long deltaUs = sourceUs - previousSourceUs;
            if (deltaUs <= MAX_STEP_US && previousLocalUs <= Long.MAX_VALUE - deltaUs) {
                long candidateUs = previousLocalUs + deltaUs;
                long distanceUs = candidateUs >= nowUs ? candidateUs - nowUs : nowUs - candidateUs;
                if (distanceUs <= MAX_DRIFT_US) {
                    mappedUs = candidateUs;
                }
            }
        }
        if (previousLocalUs >= 0L && mappedUs <= previousLocalUs) {
            mappedUs = previousLocalUs == Long.MAX_VALUE ? Long.MAX_VALUE : previousLocalUs + 1L;
        }
        previousSourceUs = sourceUs >= 0L ? sourceUs : -1L;
        previousLocalUs = mappedUs;
        return mappedUs;
    }
}
