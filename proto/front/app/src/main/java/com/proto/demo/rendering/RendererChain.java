package com.proto.demo.rendering;

import android.view.Surface;

/**
 * Single responsibility: dispatch rendering to {@link NativeRenderer} first,
 * falling back to {@link JavaRenderer} only when native is unavailable.
 *
 * IMPORTANT: once the native path has claimed ANativeWindow ownership, mixing
 * it with Java lockCanvas would corrupt the surface.  Therefore after native
 * succeeds once, we never fall through to Java even on a soft failure.
 */
public class RendererChain implements FrameRenderer {

    private final NativeRenderer nativeRenderer;
    private final JavaRenderer   javaRenderer;

    public RendererChain(NativeRenderer nativeRenderer, JavaRenderer javaRenderer) {
        this.nativeRenderer = nativeRenderer;
        this.javaRenderer   = javaRenderer;
    }

    /** Returns {@code true} if the turbo/ANativeWindow path is active. */
    public boolean isNativeActive() {
        return nativeRenderer.isAvailable();
    }

    @Override
    public boolean render(byte[] data, int len) {
        if (nativeRenderer.isAvailable()) {
            return nativeRenderer.render(data, len);
        }
        return javaRenderer.render(data, len);
    }

    @Override
    public void onSurfaceChanged(Surface surface, int w, int h) {
        nativeRenderer.onSurfaceChanged(surface, w, h);
        // JavaRenderer derives its surface indirectly via SurfaceHolder
    }

    @Override
    public void onSurfaceDestroyed() {
        nativeRenderer.onSurfaceDestroyed();
    }

    @Override
    public void release() {
        nativeRenderer.release();
        javaRenderer.release();
    }
}
