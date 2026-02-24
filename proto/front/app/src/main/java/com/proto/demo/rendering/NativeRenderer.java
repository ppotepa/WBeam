package com.proto.demo.rendering;

import android.util.Log;
import android.view.Surface;

import com.proto.demo.config.StreamConfig;
import com.proto.demo.jni.NativeBridge;

/**
 * Single responsibility: render JPEG frames via libturbojpeg → ANativeWindow.
 *
 * Uses the JNI handle lifecycle: create on construction, destroy on release().
 * Surface ownership is claimed by the native layer; do NOT mix with Java Canvas.
 */
public class NativeRenderer implements FrameRenderer {

    private static final String TAG = "WBeam";

    private long    handle    = 0;
    private boolean available = false;

    public NativeRenderer() {
        try {
            available = NativeBridge.nativeTurboAvailable();
        } catch (UnsatisfiedLinkError e) {
            available = false;
        }
        if (available) {
            try {
                handle = NativeBridge.nativeCreate(StreamConfig.OUT_W, StreamConfig.OUT_H);
                if (handle == 0) available = false;
            } catch (UnsatisfiedLinkError e) {
                available = false;
            }
        }
        Log.i(TAG, "NativeRenderer available=" + available);
    }

    /** True if turbojpeg loaded successfully and the native handle is valid. */
    public boolean isAvailable() {
        return available && handle != 0;
    }

    @Override
    public boolean render(byte[] data, int len) {
        if (!isAvailable()) return false;
        try {
            int rc = NativeBridge.nativeDecodeAndRender(
                    handle, data, len, StreamConfig.OUT_W, StreamConfig.OUT_H);
            if (rc == 0) return true;
            Log.w(TAG, "native rc=" + rc + " diag=" + NativeBridge.nativeGetDiag(handle));
            // surface ownership stays with native; don't fall through to Java
            return true;
        } catch (UnsatisfiedLinkError e) {
            available = false;
            return false;
        }
    }

    @Override
    public void onSurfaceChanged(Surface surface) {
        if (handle != 0) {
            try { NativeBridge.nativeSetSurface(handle, surface); }
            catch (UnsatisfiedLinkError ignored) {}
        }
    }

    @Override
    public void onSurfaceDestroyed() {
        if (handle != 0) {
            try { NativeBridge.nativeClearSurface(handle); }
            catch (UnsatisfiedLinkError ignored) {}
        }
    }

    @Override
    public void release() {
        if (handle != 0) {
            try { NativeBridge.nativeDestroy(handle); }
            catch (UnsatisfiedLinkError ignored) {}
            handle = 0;
        }
        available = false;
    }
}
