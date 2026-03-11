package com.proto.demo.jni;

import android.util.Log;
import android.view.Surface;

/**
 * Thin JNI bridge to libwbeam.so / libturbojpeg.so.
 * Single responsibility: native library loading and method declarations.
 *
 * NOTE: C++ JNI function names must match this class:
 *   Java_com_proto_demo_jni_NativeBridge_nativeXxx
 */
public final class NativeBridge {

    private static final String TAG = "WBeam";

    static {
        try { System.loadLibrary("turbojpeg"); } // must load before wbeam
        catch (UnsatisfiedLinkError ignored) {}
        try { System.loadLibrary("wbeam"); }
        catch (UnsatisfiedLinkError e) { Log.w(TAG, "wbeam native not found: " + e.getMessage()); }
    }

    public static native long    nativeCreate(int outMaxW, int outMaxH);
    public static native void    nativeDestroy(long handle);
    public static native void    nativeSetSurface(long handle, Surface surface);
    public static native void    nativeClearSurface(long handle);
    public static native boolean nativeTurboAvailable();
    public static native String  nativeGetDiag(long handle);
    public static native int     nativeDecodeAndRender(long handle, byte[] jpegArr, int len,
                                                       int outMaxW, int outMaxH);

    private NativeBridge() { /* utility class — no instances */ }
}
