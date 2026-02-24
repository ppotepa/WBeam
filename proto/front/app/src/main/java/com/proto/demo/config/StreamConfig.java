package com.proto.demo.config;

import android.content.Intent;

/**
 * Immutable configuration parsed from Intent extras.
 * Single responsibility: hold and expose all stream parameters.
 */
public final class StreamConfig {

    public static final int    ADB_PORT        = 5006;
    public static final int    HTTP_PORT       = 5005;
    public static final int    MAX_FRAME_BYTES = 12 * 1024 * 1024;
    public static final String DEFAULT_HOST_IP = "192.168.42.129";

    public final String  hostIp;
    public final boolean useAdbPush;
    public final boolean useH264;
    public final String  captureSize; // e.g., "1280x800"

    public StreamConfig(Intent intent) {
        String ip  = intent.getStringExtra("host_ip");
        hostIp     = (ip != null && !ip.trim().isEmpty()) ? ip.trim() : DEFAULT_HOST_IP;
        useAdbPush = intent.getBooleanExtra("adb_push", true);
        useH264    = intent.getBooleanExtra("h264", false);
        String sz  = intent.getStringExtra("capture_size");
        captureSize = (sz != null && !sz.trim().isEmpty()) ? sz.trim() : null;
    }

    @Override
    public String toString() {
        return "StreamConfig{hostIp='" + hostIp + "', useAdbPush=" + useAdbPush + '}';
    }
}
