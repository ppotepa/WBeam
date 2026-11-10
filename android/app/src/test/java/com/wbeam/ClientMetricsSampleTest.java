package com.wbeam;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClientMetricsSampleTest {
    private ClientMetricsSample sample(Double frametime) {
        return new ClientMetricsSample(60, 60, 59, 1_500_000L, 0.3, 0.6, 0.2,
                frametime, 1, 1, 0, 0, 3L, 0L, 4294967297L);
    }

    @Test public void unavailableE2eHasExplicitWireFlagWithoutInvalidJsonNumbers() throws Exception {
        ClientMetricsSample sample = sample(null);
        assertTrue(Double.isNaN(sample.e2eP95));
        JSONObject json = new JSONObject(sample.toJson().toString());
        assertFalse(json.getBoolean("e2e_latency_available"));
        assertEquals(0.0, json.getDouble("e2e_latency_ms_p50"), 0.0);
        assertEquals(0.0, json.getDouble("e2e_latency_ms_p95"), 0.0);
        assertTrue(json.isNull("frametime_ms_p95"));
        assertEquals(1_500_000L, json.getLong("recv_bps")); // bytes/sec stays compatible
    }

    @Test public void observedFrametimeIsSerializedWithoutConversion() throws Exception {
        assertEquals(31.25, sample(31.25).toJson().getDouble("frametime_ms_p95"), 0.0);
    }
}
