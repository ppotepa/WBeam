package com.wbeam.startup;

public final class StartupOverlayControllerGuard {
    private StartupOverlayControllerGuard() {
    }

    public static void startPulse(StartupOverlayController controller) {
        if (controller != null) {
            controller.startPulse();
        }
    }

    public static void stopPulse(StartupOverlayController controller) {
        if (controller != null) {
            controller.stopPulse();
        }
    }

    public static void setVisible(StartupOverlayController controller, boolean visible) {
        if (controller != null) {
            controller.setVisible(visible);
        }
    }
}
