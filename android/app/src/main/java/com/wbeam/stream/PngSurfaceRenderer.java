package com.wbeam.stream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.Surface;

final class PngSurfaceRenderer {

    private final Surface surface;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dstRect = new Rect();

    PngSurfaceRenderer(Surface surface) {
        this.surface = surface;
    }

    RenderResult render(byte[] payloadBuf, int payloadLen) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(payloadBuf, 0, payloadLen);
        if (bitmap == null) {
            return RenderResult.notRendered();
        }

        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            if (canvas == null) {
                return RenderResult.notRendered();
            }
            dstRect.set(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.drawBitmap(bitmap, null, dstRect, paint);
            return RenderResult.success();
        } catch (Exception e) {
            return RenderResult.notRendered();
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (Exception ignored) {
                    // Ignore exceptions when posting canvas
                }
            }
            bitmap.recycle();
        }
    }

    static final class RenderResult {
        final boolean rendered;

        private RenderResult(boolean rendered) {
            this.rendered = rendered;
        }

        static RenderResult success() {
            return new RenderResult(true);
        }

        static RenderResult notRendered() {
            return new RenderResult(false);
        }
    }
}
