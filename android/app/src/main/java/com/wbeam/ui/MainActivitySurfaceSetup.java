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

    static final class Input {
        private final SurfaceView preview;
        private final SurfaceStateHandler onSurfaceCreated;
        private final SurfaceStateHandler onSurfaceChanged;
        private final Action onSurfaceDestroyed;
        private final CursorEnabledSupplier isCursorOverlayEnabled;
        private final CursorMotionHandler onCursorOverlayMotion;

        Input(
                SurfaceView preview,
                SurfaceStateHandler onSurfaceCreated,
                SurfaceStateHandler onSurfaceChanged,
                Action onSurfaceDestroyed,
                CursorEnabledSupplier isCursorOverlayEnabled,
                CursorMotionHandler onCursorOverlayMotion
        ) {
            this.preview = preview;
            this.onSurfaceCreated = onSurfaceCreated;
            this.onSurfaceChanged = onSurfaceChanged;
            this.onSurfaceDestroyed = onSurfaceDestroyed;
            this.isCursorOverlayEnabled = isCursorOverlayEnabled;
            this.onCursorOverlayMotion = onCursorOverlayMotion;
        }

        SurfaceView getPreview() { return preview; }
        SurfaceStateHandler getOnSurfaceCreated() { return onSurfaceCreated; }
        SurfaceStateHandler getOnSurfaceChanged() { return onSurfaceChanged; }
        Action getOnSurfaceDestroyed() { return onSurfaceDestroyed; }
        CursorEnabledSupplier getIsCursorOverlayEnabled() { return isCursorOverlayEnabled; }
        CursorMotionHandler getOnCursorOverlayMotion() { return onCursorOverlayMotion; }
    }

    private MainActivitySurfaceSetup() {
    }

    static void setup(Input input) {
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
