package com.wbeam.hud;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimingValueFormatterTest {
    @Test
    public void unavailableTimingNeverLooksLikeZeroMilliseconds() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1.0}) {
            assertEquals("unavailable", TimingValueFormatter.milliseconds(value, 2));
            assertFalse(TimingValueFormatter.isAvailable(value));
        }
    }

    @Test
    public void measuredTimingKeepsItsValueAndUnits() {
        assertEquals("16.67 ms", TimingValueFormatter.milliseconds(16.6667, 2));
        assertEquals("0.0 ms", TimingValueFormatter.milliseconds(0.0, 1));
        assertTrue(TimingValueFormatter.isAvailable(0.0));
    }

    @Test
    public void fallbackHudDistinguishesMissingTimingFromMeasuredDecode() {
        String text = RuntimeHudFallbackFormatter.buildText(
                true, 60, 59.0, Double.NaN, 2.5, 0.7, Double.NaN,
                0, 0, 0, 2, 2, 2, 0, "hold", 0, 0, 0, ""
        );
        assertTrue(text.contains("frame unavailable"));
        assertTrue(text.contains("e2e unavailable"));
        assertTrue(text.contains("dec 2.50 ms"));
        assertFalse(text.contains("NaN"));
    }
}
