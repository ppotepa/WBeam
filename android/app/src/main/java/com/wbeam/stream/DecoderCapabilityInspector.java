package com.wbeam.stream;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Log;

import java.util.Locale;

public final class DecoderCapabilityInspector {
    private DecoderCapabilityInspector() {}

    public static String preferredVideoEncoder() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return "h264";
        }
        try {
            for (MediaCodecInfo info : gatherCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if ("video/hevc".equalsIgnoreCase(type)) {
                        return "h265";
                    }
                }
            }
        } catch (Exception ignored) {
            // Ignore exceptions during codec inspection
        }
        return "h264";
    }

    public static boolean hasHardwareAvcDecoder(String logTag) {
        try {
            for (MediaCodecInfo info : gatherCodecInfos()) {
                if (isHardwareAvcDecoder(info)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(logTag, "failed to inspect codecs", e);
        }
        return false;
    }

    private static MediaCodecInfo[] gatherCodecInfos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        }
        int count = MediaCodecList.getCodecCount();
        MediaCodecInfo[] infos = new MediaCodecInfo[count];
        for (int i = 0; i < count; i++) {
            infos[i] = MediaCodecList.getCodecInfoAt(i);
        }
        return infos;
    }

    private static boolean isHardwareAvcDecoder(MediaCodecInfo info) {
        if (info == null || info.isEncoder()) {
            return false;
        }
        for (String type : info.getSupportedTypes()) {
            if (!"video/avc".equalsIgnoreCase(type)) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return info.isHardwareAccelerated();
            }
            String name = info.getName().toLowerCase(Locale.US);
            return !name.startsWith("omx.google.")
                    && !name.startsWith("c2.android.")
                    && !name.contains("sw");
        }
        return false;
    }
}
