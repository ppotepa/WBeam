package com.proto.demo.rendering;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

/**
 * Single responsibility: render JPEG frames via BitmapFactory + lockCanvas.
 * Pure-Java fallback — used only when libturbojpeg is unavailable.
 */
public class JavaRenderer implements FrameRenderer {

    private static final String TAG = "WBeam";

    private final SurfaceHolder holder;

    public JavaRenderer(SurfaceHolder holder) {
        this.holder = holder;
    }

    @Override
    public boolean render(byte[] data, int len) {
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, len);
            if (bmp == null) return false;
            Canvas c = holder.lockCanvas(null);
            if (c == null) { bmp.recycle(); return false; }
            c.drawBitmap(bmp, 0, 0, null);
            holder.unlockCanvasAndPost(c);
            bmp.recycle();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Java render: " + e.getMessage());
            return false;
        }
    }

    /** No-op: Java path derives its surface via the holder reference. */
    @Override public void onSurfaceChanged(Surface surface) {}

    /** No-op: Canvas lock/unlock is stateless between frames. */
    @Override public void onSurfaceDestroyed() {}

    /** No-op: nothing native to release. */
    @Override public void release() {}
}
