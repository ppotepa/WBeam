package com.wbeam.compat;

import org.json.JSONException;
import org.json.JSONObject;

public final class Api21CompatPolicy implements ApiCompatPolicy {
    @Override
    public String policyName() {
        return "API21_BALANCED";
    }

    @Override
    public int minSdk() {
        return 18;
    }

    @Override
    public int maxSdk() {
        return 28;
    }

    @Override
    public JSONObject apply(JSONObject base) {
        JSONObject out;
        try {
            out = base == null ? new JSONObject() : new JSONObject(base.toString());
        } catch (JSONException ignored) {
            out = new JSONObject();
        }

        try {
            out.put("profile_hint", "AUTO_60FPS_BALANCED");
            out.put("h264_reorder", 0);
            out.put("queue_mode", "balanced");
        } catch (JSONException ignored) {
            // Keep defaults from base object if a write fails.
        }
        return out;
    }
}
