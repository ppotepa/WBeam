package com.wbeam.telemetry;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeTelemetryMapperTest {
    private RuntimeTelemetryMapper.Snapshot map(JSONObject kpi) throws Exception {
        return RuntimeTelemetryMapper.map(new JSONObject().put("kpi", kpi), 60, 3, 2, 1);
    }

    @Test public void legacyOrMissingLatencyFlagDoesNotClaimAZeroLatencyMeasurement() throws Exception {
        JSONObject kpi = new JSONObject().put("e2e_latency_ms_p95", 12.0);
        assertTrue(Double.isNaN(map(kpi).getE2eP95()));
        kpi.put("e2e_latency_available", false);
        assertTrue(Double.isNaN(map(kpi).getE2eP95()));
        kpi.put("e2e_latency_available", true);
        assertEquals(12.0, map(kpi).getE2eP95(), 0.0);
    }

    @Test public void nullAndInvalidTimingsRemainUnavailable() throws Exception {
        JSONObject kpi = new JSONObject().put("e2e_latency_available", true);
        for (Object value : new Object[] {JSONObject.NULL, -1.0, 1_700_000_000_000.0, "NaN", "Infinity"}) {
            kpi.put("e2e_latency_ms_p95", value).put("frametime_ms_p95", value);
            assertTrue(Double.isNaN(map(kpi).getE2eP95()));
            assertTrue(Double.isNaN(map(kpi).getFrametimeP95()));
        }
    }

    @Test public void observedFramePercentileIsNotReplacedWithInverseFps() throws Exception {
        JSONObject kpi = new JSONObject().put("present_fps", 60.0).put("frametime_ms_p95", 42.0);
        assertEquals(42.0, map(kpi).getFrametimeP95(), 0.0);
        kpi.remove("frametime_ms_p95");
        assertTrue(Double.isNaN(map(kpi).getFrametimeP95()));
    }

    @Test public void decoderActivityCannotHideAStalledRenderer() throws Exception {
        JSONObject kpi = new JSONObject().put("present_fps", 0.0)
                .put("decode_fps", 60.0).put("recv_fps", 60.0);
        assertEquals(0.0, map(kpi).getPresentFps(), 0.0);
    }
}
