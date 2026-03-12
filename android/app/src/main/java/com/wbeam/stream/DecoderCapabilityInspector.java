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
            MediaCodecInfo[] infos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            } else {
                int count = MediaCodecList.getCodecCount();
                infos = new MediaCodecInfo[count];
                for (int i = 0; i < count; i++) {
                    infos[i] = MediaCodecList.getCodecInfoAt(i);
                }
            }
            for (MediaCodecInfo info : infos) {
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
        }
        return "h264";
    }

    public static boolean hasHardwareAvcDecoder(String logTag) {
        try {
            MediaCodecInfo[] infos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            } else {
                int count = MediaCodecList.getCodecCount();
                infos = new MediaCodecInfo[count];
                for (int i = 0; i < count; i++) {
                    infos[i] = MediaCodecList.getCodecInfoAt(i);
                }
            }

            for (MediaCodecInfo info : infos) {
                if (info == null || info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if ("video/avc".equalsIgnoreCase(type)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (info.isHardwareAccelerated()) {
                                return true;
                            }
                        } else {
                            String name = info.getName().toLowerCase(Locale.US);
                            if (!name.startsWith("omx.google.")
                                    && !name.startsWith("c2.android.")
                                    && !name.contains("sw")) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(logTag, "failed to inspect codecs", e);
        }
        return false;
    }
}
