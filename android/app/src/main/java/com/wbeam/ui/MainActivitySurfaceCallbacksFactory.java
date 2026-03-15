package com.wbeam.ui;

import android.view.Surface;

public final class MainActivitySurfaceCallbacksFactory {
    @FunctionalInterface
    public interface SurfaceStateHandler {
        void onState(Surface surface, boolean ready);
    }

    @FunctionalInterface
    public interface Action {
        void run();
    }

    @FunctionalInterface
    public interface CursorEnabledSupplier {
        boolean get();
    }

    @FunctionalInterface
    public interface CursorMotionHandler {
        void onMotion(float x, float y, int actionMasked);
    }

    private MainActivitySurfaceCallbacksFactory() {
    }

    public static MainActivityUiBinder.SurfaceCallbacks create(
            SurfaceStateHandler onSurfaceCreated,
            SurfaceStateHandler onSurfaceChanged,
            Action onSurfaceDestroyed,
            CursorEnabledSupplier cursorEnabledSupplier,
            CursorMotionHandler cursorMotionHandler
    ) {
        return new MainActivityUiBinder.SurfaceCallbacks() {
            @Override
            public void onSurfaceCreated(Surface nextSurface, boolean ready) {
                onSurfaceCreated.onState(nextSurface, ready);
            }

            @Override
            public void onSurfaceChanged(Surface nextSurface, boolean ready) {
                onSurfaceChanged.onState(nextSurface, ready);
            }

            @Override
            public void onSurfaceDestroyed() {
                onSurfaceDestroyed.run();
            }

            @Override
            public boolean isCursorOverlayEnabled() {
                return cursorEnabledSupplier.get();
            }

            @Override
            public void onCursorOverlayMotion(float x, float y, int actionMasked) {
                cursorMotionHandler.onMotion(x, y, actionMasked);
            }
        };
    }
}
