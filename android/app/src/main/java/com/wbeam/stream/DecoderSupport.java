package com.wbeam.stream;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

final class DecoderSupport {
    private DecoderSupport() {}

    /**
     * Returns true if this device has a hardware or software decoder for given MIME type.
     * Uses legacy static MediaCodecList API so it works on API 16+.
     */
    @SuppressWarnings("deprecation")
    static boolean codecSupported(String mimeType) {
        int count = MediaCodecList.getCodecCount();
        for (int i = 0; i < count; i++) {
            MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mimeType)) return true;
            }
        }
        return false;
    }
}

