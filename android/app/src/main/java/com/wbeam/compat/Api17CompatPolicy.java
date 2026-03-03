package com.wbeam.compat;

import org.json.JSONException;
import org.json.JSONObject;

public final class Api17CompatPolicy implements ApiCompatPolicy {
    @Override
    public String policyName() {
        return "API17_SAFE";
    }

    @Override
    public int minSdk() {
        return 1;
    }

    @Override
    public int maxSdk() {
        return 17;
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
            out.put("profile_hint", "API17_SAFE_60FPS_BALANCED");
            out.put("h264_reorder", 0);
            out.put("queue_mode", "bounded_latest");
        } catch (JSONException ignored) {
            // Keep defaults from base object if a write fails.
        }
        return out;
    }
}
