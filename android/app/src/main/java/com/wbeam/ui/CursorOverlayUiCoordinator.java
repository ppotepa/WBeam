package com.wbeam.ui;

import com.wbeam.input.CursorOverlayController;

public final class CursorOverlayUiCoordinator {
    private CursorOverlayUiCoordinator() {
    }

    public static void toggleMode(
            CursorOverlayController cursorOverlayController,
            String cursorMode,
            Runnable enforcePolicyPersist
    ) {
        if (cursorOverlayController == null) {
            return;
        }
        cursorOverlayController.toggleForCursorMode(cursorMode);
        enforcePolicyPersist.run();
    }

    public static void applyPolicy(
            CursorOverlayController cursorOverlayController,
            String cursorMode,
            boolean persist,
            Runnable onPersist
    ) {
        if (cursorOverlayController != null) {
            cursorOverlayController.applyPolicy(cursorMode);
        }
        if (persist) {
            onPersist.run();
        }
    }

    public static void updateOverlay(
            CursorOverlayController cursorOverlayController,
            float x,
            float y,
            int action
    ) {
        if (cursorOverlayController != null) {
            cursorOverlayController.updateOverlay(x, y, action);
        }
    }

    public static void hideOverlay(CursorOverlayController cursorOverlayController) {
        if (cursorOverlayController != null) {
            cursorOverlayController.hideOverlay();
        }
    }
}
