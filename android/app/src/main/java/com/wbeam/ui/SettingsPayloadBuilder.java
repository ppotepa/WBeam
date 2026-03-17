package com.wbeam.ui;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds daemon start payload from resolved UI settings.
 */
public final class SettingsPayloadBuilder {
    private SettingsPayloadBuilder() {}

    public static JSONObject buildPayload(
            String profile,
            String selectedEncoder,
            String cursorMode,
            StreamConfigResolver.Resolved stream,
            boolean intraOnlyEnabled,
            boolean legacyAndroidDevice
    ) {
        JSONObject payload = new JSONObject();
        try {
            String encoder = normalizeEncoder(selectedEncoder, legacyAndroidDevice);
            boolean intraOnly = "h265".equals(encoder) && intraOnlyEnabled;
            payload.put("profile", profile);
            payload.put("encoder", encoder);
            payload.put("cursor_mode", cursorMode);
            payload.put("size", stream.width + "x" + stream.height);
            payload.put("fps", stream.fps);
            payload.put("bitrate_kbps", stream.bitrateMbps * 1000);
            payload.put("debug_fps", 0);
            payload.put("intra_only", intraOnly);
        } catch (JSONException ignored) {
            // org.json uses checked exceptions; payload remains best-effort when put() fails.
        }
        return payload;
    }

    private static String normalizeEncoder(String selectedEncoder, boolean legacyAndroidDevice) {
        String encoder = "raw-png".equals(selectedEncoder) ? "rawpng" : selectedEncoder;
        if (legacyAndroidDevice && !"rawpng".equals(encoder)) {
            return "h264";
        }
        return encoder;
    }
}
