package com.wbeam.stream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.Surface;

@SuppressWarnings("java:S108")
final class PngSurfaceRenderer {

    private final Surface surface;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dstRect = new Rect();
    private final BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
    private Bitmap reusableBitmap;

    PngSurfaceRenderer(Surface surface) {
        this.surface = surface;
        decodeOptions.inMutable = true;
        decodeOptions.inScaled = false;
    }

    RenderResult render(byte[] payloadBuf, int payloadLen) {
        if (!surface.isValid()) {
            return RenderResult.notRendered();
        }
        Bitmap bitmap = decodeBitmap(payloadBuf, payloadLen);
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
            return RenderResult.ofRenderedFrame();
        } catch (Exception e) {
            return RenderResult.notRendered();
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (Exception ignored) {
                    // surface may already be invalidated during teardown
                }
            }
        }
    }

    private Bitmap decodeBitmap(byte[] payloadBuf, int payloadLen) {
        decodeOptions.inBitmap = reusableBitmap;
        try {
            Bitmap decoded = BitmapFactory.decodeByteArray(payloadBuf, 0, payloadLen, decodeOptions);
            reusableBitmap = decoded;
            return decoded;
        } catch (IllegalArgumentException ignored) {
            decodeOptions.inBitmap = null;
            Bitmap decoded = BitmapFactory.decodeByteArray(payloadBuf, 0, payloadLen, decodeOptions);
            reusableBitmap = decoded;
            return decoded;
        }
    }

    static final class RenderResult {
        final boolean rendered;

        private RenderResult(boolean rendered) {
            this.rendered = rendered;
        }

        static RenderResult ofRenderedFrame() {
            return new RenderResult(true);
        }

        static RenderResult notRendered() {
            return new RenderResult(false);
        }
    }
}
