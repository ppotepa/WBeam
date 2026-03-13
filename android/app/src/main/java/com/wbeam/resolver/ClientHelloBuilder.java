package com.wbeam.resolver;

import android.os.Build;

import com.wbeam.compat.ApiCompatPolicy;

import org.json.JSONException;
import org.json.JSONObject;

public final class ClientHelloBuilder {
    private static final String UNKNOWN_VALUE = "unknown";
    private ClientHelloBuilder() {}

    public static JSONObject build() {
        ApiCompatPolicy policy = ApiLevelResolver.resolveCurrentDevice();
        JSONObject payload = new JSONObject();

        try {
            payload.put("sdk_int", Build.VERSION.SDK_INT);
            payload.put("device_model", Build.MODEL == null ? UNKNOWN_VALUE : Build.MODEL);
            payload.put("device_manufacturer", Build.MANUFACTURER == null ? UNKNOWN_VALUE : Build.MANUFACTURER);
            payload.put("abi", Build.CPU_ABI == null ? UNKNOWN_VALUE : Build.CPU_ABI);
            payload.put("policy", policy.policyName());
            payload.put("preferred_fps", 60);
            payload.put("preferred_codec", "h264");
        } catch (JSONException ignored) {
            // Keep partial payload; resolver policy can still apply defaults.
        }

        return policy.apply(payload);
    }
}
