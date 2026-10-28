package com.wbeam.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsPresetsTest {
    @Test public void valuesAreWithinUiSeekBarRanges() {
        for (SettingsPresets.Preset preset : new SettingsPresets.Preset[]{
                SettingsPresets.LOW_LATENCY, SettingsPresets.SMOOTH,
                SettingsPresets.QUALITY_LOW, SettingsPresets.QUALITY_MEDIUM,
                SettingsPresets.QUALITY_HIGH}) {
            assertTrue(preset.resolutionScalePercent >= 50 && preset.resolutionScalePercent <= 100);
            assertTrue(preset.fps == 0 || (preset.fps >= 24 && preset.fps <= 96));
            assertTrue(preset.bitrateMbps >= 5 && preset.bitrateMbps <= 300);
        }
    }

    @Test public void latencyPresetTargetsLowerBitrateThanSmooth() {
        assertTrue(SettingsPresets.LOW_LATENCY.bitrateMbps < SettingsPresets.SMOOTH.bitrateMbps);
        assertTrue(SettingsPresets.LOW_LATENCY.resolutionScalePercent < SettingsPresets.SMOOTH.resolutionScalePercent);
        assertTrue(SettingsPresets.LOW_LATENCY.forceH264);
    }

    @Test public void qualityPresetsPreserveFrameRate() {
        assertEquals(0, SettingsPresets.QUALITY_LOW.fps);
        assertEquals(0, SettingsPresets.QUALITY_MEDIUM.fps);
        assertEquals(0, SettingsPresets.QUALITY_HIGH.fps);
    }
}
