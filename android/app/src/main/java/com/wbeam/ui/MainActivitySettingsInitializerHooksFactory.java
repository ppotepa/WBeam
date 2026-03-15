package com.wbeam.ui;

public final class MainActivitySettingsInitializerHooksFactory {
    @FunctionalInterface
    public interface CursorPolicyHandler {
        void enforce(boolean persist);
    }

    @FunctionalInterface
    public interface SettingsRefreshHandler {
        void refresh(boolean includeSimpleMenuButtons);
    }

    private MainActivitySettingsInitializerHooksFactory() {
    }

    public static MainActivitySettingsInitializer.Hooks create(
            CursorPolicyHandler cursorPolicyHandler,
            SettingsRefreshHandler settingsRefreshHandler
    ) {
        return new MainActivitySettingsInitializer.Hooks() {
            @Override
            public void enforceCursorOverlayPolicy(boolean persist) {
                cursorPolicyHandler.enforce(persist);
            }

            @Override
            public void refreshSettingsUi(boolean includeSimpleMenuButtons) {
                settingsRefreshHandler.refresh(includeSimpleMenuButtons);
            }
        };
    }
}
