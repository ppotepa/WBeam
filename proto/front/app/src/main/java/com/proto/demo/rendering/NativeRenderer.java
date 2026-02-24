package com.proto.demo.rendering;

import android.util.Log;
import android.view.Surface;

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
    private int     outMaxW   = 0;
    private int     outMaxH   = 0;

    public NativeRenderer() {
        try {
            available = NativeBridge.nativeTurboAvailable();
        } catch (UnsatisfiedLinkError e) {
            available = false;
        }
        // Handle is created lazily in onSurfaceChanged once we know the actual display size.
        Log.i(TAG, "NativeRenderer available=" + available);
    }

    /** True if turbojpeg loaded successfully and the native handle is valid. */
    public boolean isAvailable() {
        return available && handle != 0;
    }

    /** Force-disable native path (used by emulator fallback). */
    public void disable() {
        if (handle != 0) {
            try { NativeBridge.nativeDestroy(handle); }
            catch (UnsatisfiedLinkError ignored) {}
            handle = 0;
        }
        available = false;
        Log.i(TAG, "NativeRenderer forced to Java fallback");
    }

    @Override
    public boolean render(byte[] data, int len) {
        if (!isAvailable()) return false;
        try {
            int rc = NativeBridge.nativeDecodeAndRender(
                    handle, data, len, outMaxW, outMaxH);
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
    public void onSurfaceChanged(Surface surface, int w, int h) {
        if (!available) return;
        boolean sizeChanged = (w != outMaxW || h != outMaxH) && w > 0 && h > 0;
        if (sizeChanged) {
            // Release old handle sized for different dimensions before creating a new one
            if (handle != 0) {
                try { NativeBridge.nativeDestroy(handle); }
                catch (UnsatisfiedLinkError ignored) {}
                handle = 0;
            }
            outMaxW = w;
            outMaxH = h;
            try {
                handle = NativeBridge.nativeCreate(outMaxW, outMaxH);
                if (handle == 0) { available = false; return; }
            } catch (UnsatisfiedLinkError e) {
                available = false;
                return;
            }
            Log.i(TAG, "NativeRenderer resized to " + w + "x" + h);
        }
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
