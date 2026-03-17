package com.wbeam.ui;

import android.view.Surface;
import android.view.SurfaceView;

public final class MainActivitySurfaceSetup {
    @FunctionalInterface
    public interface SurfaceStateHandler {
        void onState(Surface nextSurface, boolean ready);
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

    @SuppressWarnings("java:S1104")
    public static final class Input {
        public SurfaceView preview;
        public SurfaceStateHandler onSurfaceCreated;
        public SurfaceStateHandler onSurfaceChanged;
        public Action onSurfaceDestroyed;
        public CursorEnabledSupplier isCursorOverlayEnabled;
        public CursorMotionHandler onCursorOverlayMotion;
    }

    private MainActivitySurfaceSetup() {
    }

    public static void setup(Input input) {
        MainActivityUiBinder.setupSurfaceCallbacks(
                input.preview,
                MainActivitySurfaceCallbacksFactory.create(
                        input.onSurfaceCreated::onState,
                        input.onSurfaceChanged::onState,
                        input.onSurfaceDestroyed::run,
                        input.isCursorOverlayEnabled::get,
                        input.onCursorOverlayMotion::onMotion
                )
        );
    }
}
