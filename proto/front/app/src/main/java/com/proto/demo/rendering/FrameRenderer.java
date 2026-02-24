package com.proto.demo.rendering;

import android.view.Surface;

/**
 * Contract for JPEG frame renderers.
 * Single responsibility: define what a renderer can do (render, surface lifecycle, teardown).
 */
public interface FrameRenderer {

    /** Render one JPEG frame. Returns {@code true} on success. */
    boolean render(byte[] data, int len);

    /** Called when the Surface becomes available or changes size/format. */
    void onSurfaceChanged(Surface surface, int w, int h);

    /** Called when the Surface is about to be destroyed. */
    void onSurfaceDestroyed();

    /** Release any native resources held by this renderer. */
    void release();
}
