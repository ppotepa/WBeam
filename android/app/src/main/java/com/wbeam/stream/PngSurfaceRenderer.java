package com.wbeam.stream;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
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
        long decodeStartedNs = SystemClock.elapsedRealtimeNanos();
        Bitmap bitmap = decodeBitmap(payloadBuf, payloadLen);
        long decodeNs = SystemClock.elapsedRealtimeNanos() - decodeStartedNs;
        if (bitmap == null) {
            return RenderResult.notRendered();
        }

        Canvas canvas = null;
        long renderStartedNs = SystemClock.elapsedRealtimeNanos();
        boolean drawn = false;
        boolean posted = false;
        try {
            canvas = surface.lockCanvas(null);
            if (canvas == null) {
                return RenderResult.notRendered();
            }
            dstRect.set(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.drawBitmap(bitmap, null, dstRect, paint);
            drawn = true;
        } catch (Exception e) {
            // Return an unsuccessful sample after releasing the locked canvas.
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                    posted = true;
                } catch (Exception ignored) {
                    // surface may already be invalidated during teardown
                }
            }
        }
        long finishedNs = SystemClock.elapsedRealtimeNanos();
        return new RenderResult(drawn && posted, decodeNs,
                finishedNs - renderStartedNs, finishedNs);
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
        final long decodeNs;
        final long renderNs;
        final long presentedAtNs;

        private RenderResult(boolean rendered, long decodeNs, long renderNs, long presentedAtNs) {
            this.rendered = rendered;
            this.decodeNs = decodeNs;
            this.renderNs = renderNs;
            this.presentedAtNs = presentedAtNs;
        }

        static RenderResult notRendered() {
            return new RenderResult(false, 0L, 0L, 0L);
        }
    }
}
