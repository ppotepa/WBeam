package com.wbeam.stream;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class StreamTimestampMapperTest {
    @Test public void relativeAndEpochSourcesProduceTheSameLocalTimeline() {
        StreamTimestampMapper relative = new StreamTimestampMapper();
        StreamTimestampMapper epoch = new StreamTimestampMapper();
        long baseEpoch = 1_700_000_000_000_000L;
        for (int frame = 0; frame < 100; frame++) {
            long source = frame * 16_667L;
            long arrival = 10_000_000L + source + (frame % 3) * 300L;
            assertEquals(relative.map(source, arrival), epoch.map(baseEpoch + source, arrival));
        }
    }

    @Test public void captureResetAndWallClockJumpsReanchorWithoutPoisoningPlayback() {
        StreamTimestampMapper mapper = new StreamTimestampMapper();
        assertEquals(5_000_000L, mapper.map(100_000L, 5_000_000L));
        assertEquals(5_016_000L, mapper.map(116_000L, 5_016_000L));
        assertEquals(5_032_000L, mapper.map(0L, 5_032_000L));
        assertEquals(5_048_000L, mapper.map(1_700_000_000_000_000L, 5_048_000L));
        assertEquals(5_064_000L, mapper.map(1_700_000_000_016_000L, 5_064_000L));
    }

    @Test public void maliciousUnsignedTimestampsStayOnTheLocalClock() throws Exception {
        StreamTimestampMapper mapper = new StreamTimestampMapper();
        long[] timestamps = {Long.MIN_VALUE, -1L, Long.MAX_VALUE, Long.MAX_VALUE - 1L, 0L, 16_667L};
        long previous = -1L;
        for (int i = 0; i < timestamps.length; i++) {
            byte[] wire = ByteBuffer.allocate(22)
                    .putInt(0x57425450).put((byte) 1).put((byte) 2).putInt(i)
                    .putLong(timestamps[i]).putInt(0).array();
            WbtpProtocol.FrameHeader header = WbtpProtocol.readFrameHeader(
                    new ByteArrayInputStream(wire), new byte[22], 22, 0x57425450, 65536, 2);
            long arrival = 9_000_000L + i * 16_667L;
            long mapped = mapper.map(header.ptsUs, arrival);
            assertTrue(mapped > previous);
            assertTrue(Math.abs(mapped - arrival) <= 2_000_000L);
            previous = mapped;
        }
    }

    @Test public void duplicateAndBackwardsPtsDoNotOverflowOrStallTheTimeline() {
        StreamTimestampMapper mapper = new StreamTimestampMapper();
        long first = mapper.map(Long.MAX_VALUE, 1_000_000L);
        long duplicate = mapper.map(Long.MAX_VALUE, 1_000_000L);
        long recovered = mapper.map(50L, 1_016_667L);
        assertEquals(first + 1L, duplicate);
        assertEquals(1_016_667L, recovered);
    }
}
