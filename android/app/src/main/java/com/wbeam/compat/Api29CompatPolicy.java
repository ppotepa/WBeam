package com.wbeam.compat;

import org.json.JSONObject;

public final class Api29CompatPolicy implements ApiCompatPolicy {
    @Override
    public String policyName() {
        return "API29_MODERN";
    }

    @Override
    public int minSdk() {
        return 29;
    }

    @Override
    public int maxSdk() {
        return Integer.MAX_VALUE;
    }

    @Override
    public JSONObject apply(JSONObject base) {
        JSONObject out = base == null ? new JSONObject() : new JSONObject(base.toString());
        out.put("profile_hint", "AUTO_60FPS_QUALITY");
        out.put("h264_reorder", 0);
        out.put("queue_mode", "modern_low_latency");
        return out;
    }
}
