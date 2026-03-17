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
        private SurfaceView preview;
        private SurfaceStateHandler onSurfaceCreated;
        private SurfaceStateHandler onSurfaceChanged;
        private Action onSurfaceDestroyed;
        private CursorEnabledSupplier isCursorOverlayEnabled;
        private CursorMotionHandler onCursorOverlayMotion;

        public SurfaceView getPreview() {
            return preview;
        }

        public void setPreview(SurfaceView preview) {
            this.preview = preview;
        }

        public SurfaceStateHandler getOnSurfaceCreated() {
            return onSurfaceCreated;
        }

        public void setOnSurfaceCreated(SurfaceStateHandler onSurfaceCreated) {
            this.onSurfaceCreated = onSurfaceCreated;
        }

        public SurfaceStateHandler getOnSurfaceChanged() {
            return onSurfaceChanged;
        }

        public void setOnSurfaceChanged(SurfaceStateHandler onSurfaceChanged) {
            this.onSurfaceChanged = onSurfaceChanged;
        }

        public Action getOnSurfaceDestroyed() {
            return onSurfaceDestroyed;
        }

        public void setOnSurfaceDestroyed(Action onSurfaceDestroyed) {
            this.onSurfaceDestroyed = onSurfaceDestroyed;
        }

        public CursorEnabledSupplier getIsCursorOverlayEnabled() {
            return isCursorOverlayEnabled;
        }

        public void setIsCursorOverlayEnabled(CursorEnabledSupplier isCursorOverlayEnabled) {
            this.isCursorOverlayEnabled = isCursorOverlayEnabled;
        }

        public CursorMotionHandler getOnCursorOverlayMotion() {
            return onCursorOverlayMotion;
        }

        public void setOnCursorOverlayMotion(CursorMotionHandler onCursorOverlayMotion) {
            this.onCursorOverlayMotion = onCursorOverlayMotion;
        }
    }

    private MainActivitySurfaceSetup() {
    }

    public static void setup(Input input) {
        MainActivityUiBinder.setupSurfaceCallbacks(
                input.getPreview(),
                MainActivitySurfaceCallbacksFactory.create(
                        input.getOnSurfaceCreated()::onState,
                        input.getOnSurfaceChanged()::onState,
                        input.getOnSurfaceDestroyed()::run,
                        input.getIsCursorOverlayEnabled()::get,
                        input.getOnCursorOverlayMotion()::onMotion
                )
        );
    }
}
